# Dependency connectivity monitoring (Oracle / Redis / Kafka)

Whether `airtel-aaa-user-provisioning-service` can reach the infrastructure it cannot
run without, from the service's own Micrometer metrics through Prometheus into Grafana
- the same metrics, the same reason tags and the same REST endpoint as the DB write
service, so one dashboard reads either.

```
probe every 15s ----\
                     >-- ConnectivityMonitoringService --> /actuator/prometheus --> Grafana + alerts
failures on the  ---/          (up/down state machine)  \-> GET /monitoring/connectivity
request path
```

## Two signals, one state machine

| Signal | What it catches | Where it comes from |
| --- | --- | --- |
| **Active probes** | Outages while no traffic is flowing, and recovery | A scheduler pings each dependency every `connectivity.probe-interval-ms` (default 15s) |
| **Live traffic failures** | Real request failures as they happen | `GlobalExceptionHandler` offers every failure it handles to `recordThrowable`, and `KafkaEventPublisher` reports the outcome of every publish |

A dependency flips to **DOWN** after `connectivity.failure-threshold` (default 3)
consecutive connectivity failures, and back to **UP** on the first success from either
signal. Both transitions are logged (`Connectivity DOWN for ...` /
`Connectivity RESTORED for ...`).

| `dependency` | What it is | Probe |
| --- | --- | --- |
| `database` | Oracle, through the Hikari pool the repositories use | `SELECT 1 FROM DUAL` |
| `redis` | Redis, through the connection factory the app uses | `PING` |
| `kafka` | The Kafka cluster provisioning events go to | AdminClient `describeCluster` |

Probing through the application's own pools is deliberate: a pool that cannot hand out
a connection is an outage from this service's point of view even when the dependency
itself is healthy, and that is what the `pool_exhausted` reason is for.

**The probes carry most of the weight here.** Live traffic only reports a failure when
the exception still carries its cause by the time it reaches the global handler; a
service layer that catches a `DataAccessException` and rethrows `AAAException` (which
does not keep a cause) is invisible to it. That is why the probe interval matters more
in this service than in the DB write service, and why a call site that knows better
should say so explicitly:

```java
connectivityMonitoringService.recordFailure(Dependency.REDIS, e);   // or recordSuccess(...)
```

## What counts as a connectivity issue

`ConnectivityFailureClassifier` walks the cause chain of each failure and tags it with a
reason. Only the transport reasons drive the up/down state; everything else is counted
as `application_error` and never takes a dependency down.

| `reason` tag | Typical cause |
| --- | --- |
| `connection_refused` | Nothing listening (`ConnectException`, ORA-12541, ORA-17002) |
| `connection_timeout` | Connect/read/command timed out, Kafka batch expiry |
| `connection_closed` | Reset, broken pipe, ORA-03113 |
| `host_unreachable` | DNS failure, no route to host |
| `pool_exhausted` | Hikari or Lettuce could not hand out a connection in time |
| `authentication_failed` | ORA-01017, Redis `NOAUTH`/`WRONGPASS`, Kafka SASL |
| `tls_failure` | Handshake or certificate validation failure |
| `broker_unavailable` | Kafka broker/coordinator missing, topic not in metadata |
| `service_unavailable` | Reached but unusable - listener down, cluster down |
| `application_error` | Not a connectivity problem; the dependency answered with an error |

Classification works **from the root cause outwards**. Spring wraps everything, and it is
the innermost link that names the actual fault: a refused Oracle connection arrives as
`DataAccessResourceFailureException -> SQLTransientConnectionException -> ConnectException`,
and the reason tag has to say `connection_refused`, not the pool timeout that was merely
how it surfaced. The wrappers only decide when nothing deeper explains the failure.

## Metrics

Scraped from `/actuator/prometheus`. Counter names carry the `_total` suffix Prometheus
adds; gauges appear exactly as named.

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `dependency_up` | gauge | `service`, `dependency` | 1 = reachable, 0 = down |
| `dependency_connectivity_failure_count_total` | counter | `service`, `dependency`, `reason` | Transport failures |
| `dependency_error_count_total` | counter | `service`, `dependency`, `reason` | All failures, connectivity or not |
| `dependency_connectivity_failure_daily_count` | gauge | `service`, `dependency` | Failures in the current 24h window (resets 00:00) |
| `dependency_consecutive_failure_count` | gauge | `service`, `dependency` | Current failure streak |
| `dependency_outage_count_total` | counter | `service`, `dependency` | UP -> DOWN transitions |
| `dependency_downtime_seconds` | gauge | `service`, `dependency` | Length of the outage in progress (0 when up) |
| `dependency_last_failure_timestamp_seconds` | gauge | `service`, `dependency` | Epoch seconds of the last failure |
| `dependency_last_success_timestamp_seconds` | gauge | `service`, `dependency` | Epoch seconds of the last success |
| `dependency_outage_duration_seconds_*` | timer | `service`, `dependency` | One observation per completed outage |
| `dependency_probe_latency_seconds_*` | timer | `service`, `dependency`, `outcome` | Probe round trip, `outcome` = success/failure |

`dependency` is one of `database`, `redis` or `kafka`. A `reason` series only exists once
that reason has actually occurred, which is why a healthy service publishes so few of
them.

### The `service` tag

Every AAA service exports these metric names, so **each series carries a `service` tag** -
`airtel-aaa-user-provisioning-service` here, from `connectivity.service-name`. Always
scope queries by it; without it `dependency_up{dependency="redis"}` matches every
service in the estate at once. The shipped dashboard and alert rules are already scoped.

(The `microservice` tag that `ApiMonitoringConfig` puts on every meter is on these series
too. `service` is the one to use: it is what the DB write service publishes, so a query
written against one service works against the other.)

Useful queries:

```promql
# Anything down right now in this service
dependency_up{service="airtel-aaa-user-provisioning-service"} == 0

# Connectivity failure rate, split by dependency and reason
sum by (dependency, reason) (rate(dependency_connectivity_failure_count_total{service="airtel-aaa-user-provisioning-service"}[5m]))

# Probe latency (average of successful probes)
  rate(dependency_probe_latency_seconds_sum{service="airtel-aaa-user-provisioning-service",outcome="success"}[5m])
/ rate(dependency_probe_latency_seconds_count{service="airtel-aaa-user-provisioning-service",outcome="success"}[5m])

# Outages in the last hour
increase(dependency_outage_count_total{service="airtel-aaa-user-provisioning-service"}[1h])

# Every AAA service at a glance
min by (service, dependency) (dependency_up)
```

## REST endpoint

For a quick human check without scraping metrics:

```
GET /monitoring/connectivity
```

`200` when everything is reachable, `503` when at least one dependency is down, so an
uptime check can key off the status code alone. The path sits outside `/api/**`, so the
channel-auth and rate-limit filters do not apply to it.

```json
{
  "status": "DOWN",
  "dependencies": {
    "database": {
      "dependency": "database",
      "up": true,
      "consecutiveFailures": 0,
      "connectivityFailureCount": 0,
      "dailyConnectivityFailureCount": 0,
      "outageCount": 0,
      "downtimeSeconds": 0,
      "lastFailureEpochSeconds": 0,
      "lastSuccessEpochSeconds": 1755791234,
      "lastFailureReason": null
    },
    "redis": { "up": false, "lastFailureReason": "connection_refused", "...": "..." },
    "kafka": { "up": true, "...": "..." }
  }
}
```

Note what this endpoint is *not*: it reports the state connectivity monitoring last
observed, not a fresh check. A dependency that died two seconds ago still reads UP until
the next probe round or the next failed request.

## Setting it up

**1. Confirm the service is exporting.** On any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep '^dependency_up'
curl -s localhost:8089/monitoring/connectivity
```

Three lines should come back from the first command, one per dependency. If it is empty,
nothing downstream will work - check the startup log for
`Connectivity monitoring initialised: ...`, which lists the probes that were wired.

**2. Scrape it.** The existing `prometheus/servicemonitor.yaml` already scrapes
`/actuator/prometheus`, so there is nothing to add if endpoint monitoring is set up.

**3. Alerts.**

```sh
oc -n airtel-aaa apply -f prometheus/user-provisioning-connectivity-rules.yaml
```

| Alert | Fires when |
| --- | --- |
| `UserProvisioningDependencyDown` | A dependency has been down for 1 minute. |
| `UserProvisioningAllDependenciesDown` | All three are down on one pod - look at the pod's network, not at three dependencies. |
| `UserProvisioningDependencyConnectivityErrors` | Sustained transport failures that retries are still covering. |
| `UserProvisioningDependencyPoolExhausted` | Calls cannot borrow a connection. |
| `UserProvisioningDependencyAuthenticationFailing` | Credentials are being rejected. |
| `UserProvisioningDependencyFlapping` | 3+ outages in 30 minutes. |
| `UserProvisioningDependencyProbeSlow` | Successful probes average over 500ms - the earliest warning. |
| `UserProvisioningNoConnectivityMetrics` | `dependency_up` absent for 10 minutes. |

**4. Dashboard.** Grafana -> Dashboards -> New -> Import -> upload
`grafana/user-provisioning-dependency-connectivity.json`, then pick the Prometheus data
source. UID: `aaa-user-provisioning-connectivity`. It is organised as connectivity status,
availability over time, connectivity failures, and health probes.

## Configuration

```yaml
connectivity:
  service-name: airtel-aaa-user-provisioning-service   # the `service` tag on every dependency_* metric
  enabled: true               # false stops the probes; live traffic failures still count
  failure-threshold: 3        # consecutive connectivity failures before a dependency is marked DOWN
  probe-interval-ms: 15000    # gap between probe rounds
  probe-timeout-ms: 2000      # per-probe budget; a slower answer counts as a failure
  probe-database: true        # SELECT 1 FROM DUAL
  probe-redis: true           # PING
  probe-kafka: true           # AdminClient describeCluster
  database-probe-query: SELECT 1 FROM DUAL
  daily-reset-cron: "0 0 0 * * *"   # when dependency_connectivity_failure_daily_count resets
```

Keep the block in step in `application.yml` **and** `application-telco_aaa_dev.yml`.

Tuning notes:

- **Flapping** (`UserProvisioningDependencyFlapping` firing): raise `failure-threshold`, or
  lengthen `probe-timeout-ms` if the dependency is simply slow rather than absent.
- **Slow detection**: lower `probe-interval-ms`. Each round is one round trip per
  dependency per pod, so 15s is cheap; below 5s the value drops off.
- **`probe-timeout-ms` has to stay below the pool timeouts it probes through.** Hikari's
  `connection-timeout` is 10s: when the pool is starved, the probe gives up at 2s and
  reports `connection_timeout` while the borrow attempt is still running on its own
  thread. That is the intended behaviour - the round does not wait 10s - but it is why
  `pool_exhausted` mostly arrives from live traffic rather than from the probe.
- **Probe cost**: the Kafka probe holds one long-lived `AdminClient` per pod, created on
  the first probe and closed with the context. Set the matching `probe-*` flag to `false`
  to opt out of any individual probe.
- The probes run on their own threads, started together and collected against their own
  deadlines, so one dead dependency delays neither the others nor the next round.

## Where the wiring lives

| File | Role |
| --- | --- |
| `application/monitoring/connectivity/ConnectivityMonitoringService.java` | State machine, metrics, probe rounds |
| `application/monitoring/connectivity/ConnectivityFailureClassifier.java` | Connectivity failure vs application error |
| `application/monitoring/connectivity/ConnectivityFailureReason.java` | The `reason` tag values |
| `application/monitoring/connectivity/DependencyResolver.java` | Which dependency an unattributed failure came from |
| `application/monitoring/connectivity/*ConnectivityProbe.java` | The three probes |
| `application/monitoring/connectivity/ConnectivityMonitoringProperties.java` | The `connectivity.*` block |
| `application/config/ConnectivityMonitoringConfig.java` | Assembles the probes, enables scheduling |
| `application/controller/ConnectivityController.java` | `GET /monitoring/connectivity` |
| `domain/exception/GlobalExceptionHandler.java` | Feeds live traffic failures in |
| `application/config/KafkaEventPublisher.java` | Reports every publish outcome |

Adding a dependency (a downstream HTTP service, say) means adding a value to
`Dependency`, a `DependencyProbe` bean for it - `ConnectivityMonitoringConfig` picks up
every such bean - and nothing else: the metrics, the state machine, the endpoint and the
dashboard follow from the tag.
