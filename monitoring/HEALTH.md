# Per-endpoint REST health

Whether each REST endpoint of `airtel-aaa-user-provisioning-service` is fit to
serve, endpoint by endpoint, evaluated inside the service every 15 seconds and
published to Prometheus for Grafana.

```
handler mapped in this build? --\
every declared dependency up? ---> EndpointHealthMonitor --> /actuator/prometheus --> Grafana + alerts
5xx ratio over the window?    --/     (every 15s)         \-> GET /monitoring/endpoints
```

This is one of the two monitoring documents here, and the one that answers
"is this endpoint alright?":

| Document | Question it answers |
| --- | --- |
| **HEALTH.md** (this file) | Whether each endpoint is fit to serve - including the ones nobody called. |
| [CONNECTIVITY.md](CONNECTIVITY.md) | Whether Oracle, Redis and Kafka are reachable at all. |

[README.md](README.md) covers what the two have in common: the endpoint catalog
and the scrape.

## Why a request ratio was not enough

The obvious way to monitor an endpoint is availability - the share of requests
that did not answer 5xx. That makes it exactly as informative as the traffic
behind it, and three real situations slip through it entirely.

**An endpoint nobody called reads 100%.** No requests, no failures, 100%
available - and the database it reads from may have been unreachable for an
hour. 100% over 0 req/s means "nothing failed", not "this works". Health is what
turns that into an answer.

**An endpoint with no handler reads 100%.** If a controller path is renamed and
the catalog in `application.yml` is not, every call to it answers 404. A 404 is
a client error, so the ratio stays perfect while every caller of that
endpoint is broken. `ApiMonitoringCatalogTest` catches this in the build; it
cannot catch a catalog and a war that came from different commits, or a
controller excluded by a profile in one environment.

**A dependency outage looks like an endpoint problem.** A request ratio says
`create_user` is failing. It does not say Kafka is down and that six other
endpoints are about to follow.

Health starts from what can be checked without a request, and only then looks at
traffic.

## The three checks

Evaluated in this order, every `monitoring.api.health.evaluation-interval-ms`
(15s by default). All three produce UNHEALTHY, so what the ordering decides is
the **reason** - and a cause is more useful on a page than a symptom.

| # | Check | Reason when it fails | Where the answer comes from |
| --- | --- | --- | --- |
| 1 | Is a handler for this method and path mapped in this build? | `not_mapped` | Spring's `RequestMappingHandlerMapping`, read once at startup |
| 2 | Is every dependency the endpoint declares reachable? | `dependency_down` | The connectivity probes that already run every 15s |
| 3 | Is the 5xx ratio over the window under the thresholds? | `errors` | The `http_server_requests` timer Spring Boot already publishes |

None of them costs anything new. Check 1 is one pass over data Spring already
holds, check 2 reuses [CONNECTIVITY.md](CONNECTIVITY.md)'s probes rather than
probing anything itself, and check 3 reads counters that are already being
published - sampled in the JVM as a sliding window rather than as a Prometheus
rate, which is what lets `GET /monitoring/endpoints` answer without a round trip
to Prometheus.

### The four states

| State | `api_endpoint_health` | Meaning |
| --- | --- | --- |
| Healthy | 3 | Serving cleanly, or idle with checks 1 and 2 passing |
| Degraded | 2 | Serving, but above `degraded-error-ratio` - or a failure was seen with too little traffic to judge a ratio |
| Unhealthy | 1 | Any of the three checks failed |
| Unknown | 0 | No verdict yet - the first evaluation has not run |

Higher is better, deliberately: `min by (api)` gives the worst pod, `min()`
gives the worst endpoint, and an alert can say `== 1` without enumerating
states. **Never renumber these** - the dashboard, the alerts and the recording
rules are written against these values.

### The six reasons

`api_endpoint_health_reason` publishes a 1 for whichever reason is active and a
0 for the rest, so every reason is a continuous series rather than one that
appears and vanishes. Exactly one is 1 per endpoint at any moment, which is what
lets the alerts fire on the reason series and carry `reason` as a label.

| Reason | State | What to do |
| --- | --- | --- |
| `ok` | Healthy | Nothing. Traffic, none of it failing. |
| `idle` | Healthy | Nothing. No traffic, and nothing else wrong. |
| `errors` | Degraded or Unhealthy | The endpoint is failing on its own. Start at the *5xx share by endpoint* panel, then the pod logs. |
| `dependency_down` | Unhealthy | Something it needs is unreachable. Go to the connectivity dashboard - this endpoint is a symptom. |
| `not_mapped` | Unhealthy | The build does not serve this path. `application.yml` and the controllers were changed apart. |
| `unknown` | Unknown | The evaluation schedule is not running. Check the log for `Endpoint health evaluation failed`. |

## What "idle" does and does not claim

An endpoint with no traffic reads **Healthy, reason `idle`**. That is a real
claim, not a shrug: its handler exists in this build and every dependency it
declares is reachable, both actively verified. What it does not claim is that
the endpoint's own logic works - nothing has exercised it. `idle` is called out
as its own reason rather than folded into `ok` precisely so the difference stays
visible, on the dashboard and in the alert labels.

Turning off `use-dependency-state` removes most of the evidence behind that
claim, and idle then means little more than a 100% request ratio would.

## Dependencies are declared per endpoint

Check 2 works off a list on each catalog entry:

```yaml
      - name: create_user
        title: Create user
        method: POST
        uris:
          - /api/user
        dependencies:
          - database
          - kafka
```

The labels are the ones connectivity monitoring publishes: `database`, `redis`,
`kafka`. What ships:

| Endpoint | Dependencies | Why |
| --- | --- | --- |
| `create_user`, `update_user`, `delete_user` | database, kafka | The write itself is a Kafka event - `publishUserCreatedEvents` turns a complete publish failure into a 500. |
| `activate_user`, `update_service`, `delete_service` | database, kafka, redis | The same, plus `ServiceTTLManager`, which is Redis. |
| `get_user`, `get_users`, `query_user_information` | database | Reads. |

**List only what the endpoint cannot serve without.** Everything in this list
can mark the endpoint UNHEALTHY on its own, so a dependency the endpoint
degrades gracefully without does not belong here - putting it there manufactures
outages. An empty list is honest and means "judge this one from traffic alone".

A label connectivity monitoring does not track is treated as reachable and warned
about at startup. The alternative - treating an unrecognised label as an outage -
would turn one typo into every endpoint going red.

## The thresholds

These are the only numbers in endpoint monitoring that are a judgement rather
than a measurement, which is why they are configuration:

```yaml
monitoring:
  api:
    health:
      enabled: true
      evaluation-interval-ms: 15000
      window-ms: 300000
      degraded-error-ratio: 0.01
      unhealthy-error-ratio: 0.10
      minimum-requests: 20
      use-dependency-state: true
      verify-mappings: true
```

`window-ms` is a 5 minute sliding window, long enough that a single failure does
not swing the ratio and short enough that a recovery shows up promptly.

`minimum-requests` is the one worth understanding. One failure out of two
requests is a 50% error ratio, which is a fact about the sample size and not
about the endpoint. Below the floor the ratios are not applied - but the failure
is not discarded either: the endpoint reads **Degraded** with reason `errors`,
which charts and does not page. Raise the floor for a quiet endpoint that pages
on nothing; lower it for a busy one where twenty requests is a moment.

## Metrics published

All tagged `api`, plus the `microservice` common tag.

| Metric | Type | Extra labels | Meaning |
| --- | --- | --- | --- |
| `api_endpoint_health` | gauge | `title`, `method` | 3 / 2 / 1 / 0 as above |
| `api_endpoint_health_reason` | gauge | `reason` | 1 for the active reason, 0 for the others |
| `api_endpoint_mapped` | gauge | | 1 when a handler exists in this instance |
| `api_endpoint_requests_window` | gauge | | Requests over the health window |
| `api_endpoint_errors_window` | gauge | | Of those, how many answered 5xx |
| `api_endpoint_error_ratio` | gauge | | The 5xx share, 0..1 |
| `api_endpoint_unhealthy_seconds` | gauge | | Length of the unhealthy stretch in progress, 0 when not unhealthy |
| `api_endpoint_health_transitions_total` | counter | `to` | One per state change |
| `api_endpoint_last_failure_timestamp_seconds` | gauge | | Epoch seconds of the last 5xx, 0 if never |
| `api_endpoint_dependency_required` | gauge | `dependency` | Constant 1 per declared dependency |

Cardinality is bounded by the catalog: nine endpoints, six reasons, at most three
dependencies each. Traffic to an uncatalogued path is tagged `api="other"` by
the endpoint catalog and is not evaluated here at all.

Every series exists from startup, before any traffic - so an endpoint missing
from the dashboard means the catalog is not being scraped, not that the endpoint
is quiet.

## The JSON view

`GET /monitoring/endpoints` serves the same state as one document, for the cases
a metrics scrape is the wrong tool - a smoke test after a deploy, or an on-call
engineer on a pod with no Grafana in front of them. It carries one thing
Prometheus cannot: `detail`, a sentence rather than a label.

```sh
curl -s localhost:8089/monitoring/endpoints | jq
```

```json
{
  "status": "unhealthy",
  "endpoints": {
    "create_user": {
      "title": "Create user",
      "method": "POST",
      "health": "unhealthy",
      "reason": "dependency_down",
      "detail": "required dependency down: kafka",
      "mapped": true,
      "requestsInWindow": 0,
      "errorsInWindow": 0,
      "dependencies": ["database", "kafka"],
      "dependenciesDown": ["kafka"],
      "unhealthySeconds": 95
    }
  }
}
```

It answers 503 as soon as one endpoint is anything but healthy, so a smoke test
can key off the status code alone. That is stricter than a readiness probe
should be - an endpoint degraded by a handful of errors is no reason to take a
pod out of service - so run it **against** a deployment, do not wire it **into**
one. It sits next to `GET /monitoring/connectivity`, which answers the same
question one layer down. Neither is behind the channel auth filter.

## Setting it up

**1. Confirm the service is exporting.** On any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep -c api_endpoint_health
curl -s localhost:8089/monitoring/endpoints | jq '.status'
```

The first should return well over nine lines - nine endpoints plus six reason
series each. Zero means `monitoring.api.health.enabled` or `monitoring.api.enabled`
is off. A `status` of `unknown` within the first 15 seconds of startup is
expected; after a minute it is not, and the pod log will say why.

**2. Scrape it.** Nothing new - the ServiceMonitor in
`prometheus/servicemonitor.yaml` covers these metrics. See
[README.md](README.md#scraping).

**3. Alerts.**

```sh
oc -n airtel-aaa apply -f prometheus/user-provisioning-endpoint-health-rules.yaml
```

| Alert | Severity | Fires when |
| --- | --- | --- |
| `UserProvisioningEndpointUnhealthy` | critical | An endpoint is unhealthy for 2 minutes. Carries `reason`. |
| `UserProvisioningEndpointDegraded` | warning | An endpoint is degraded for 10 minutes. |
| `UserProvisioningEndpointNotMapped` | critical | A catalogued endpoint has no handler for 5 minutes. |
| `UserProvisioningEndpointHealthUnknown` | warning | No verdict for 10 minutes - the evaluation is not running. |
| `UserProvisioningEndpointFlapping` | warning | More than six state changes in an hour. |
| `UserProvisioningNoEndpointHealth` | critical | `api_endpoint_health` is absent for 5 minutes. |

`UserProvisioningEndpointUnhealthy` fires on the *reason* series rather than the
state, which is what puts `reason` in its labels - so the page itself says
whether this is a dependency outage, a missing handler, or the endpoint failing
under its own steam, and routing can tell them apart. Exactly one reason is 1
per endpoint, so it cannot double-fire.

Everything aggregates with `min`/`max` across pods, so one bad pod is reported
rather than averaged away.

Run the rule tests after changing any of it - the header of
`prometheus/user-provisioning-endpoint-health-rules_test.yaml` has the commands.

**4. Dashboard.** Grafana -> Dashboards -> New -> Import -> upload
`grafana/user-provisioning-endpoint-health.json`, then pick the Prometheus data
source. Its UID is a dashboard variable, so the JSON is not tied to any one
Grafana instance, and the dashboard queries Prometheus directly - it works
whether or not step 3 was done.

## Reading the dashboard

| Panel | What it is for |
| --- | --- |
| **Worst endpoint** | The one number for a wall board. |
| **Health by endpoint** | One coloured tile per endpoint. The at-a-glance view. |
| **Health over time** | The same states as history. Where a red band starts is when the endpoint broke. |
| **Endpoint health detail** | The table behind the verdict - reason, mapped, requests, 5xx, share, how long. |
| **Endpoints by reason** | `dependency_down` or `not_mapped` climbing off zero is the shape of an outage. |
| **5xx share by endpoint** | The ratio each verdict was computed from, with the thresholds drawn on. |
| **State changes per hour** | Flapping. A sawtooth means the thresholds need widening, not silencing. |
| **Endpoints blocked by a dependency outage** | Which endpoints a dependency outage is currently taking down. Empty is healthy. |

The order to read them in during an incident: **Worst endpoint** to see if
anything is wrong, **Health by endpoint** to see how much, **Endpoints by
reason** to see what kind, and then either the connectivity dashboard
(`dependency_down`) or the *5xx share by endpoint* panel (`errors`).

## Changing the catalog

Adding an endpoint is an edit to the shared catalog - see
[README.md](README.md#changing-the-catalog) - plus a `dependencies` list. Both
`application.yml` and `application-telco_aaa_dev.yml` must be edited, and
`ApiMonitoringCatalogTest` fails the build if an entry stops matching a real
request mapping.

No dashboard change is needed: every panel is driven by the `api` label and the
endpoint variable is populated from `api_endpoint_health`.

## Turning it off

`monitoring.api.health.enabled: false` stops the evaluation and drops every
`api_endpoint_*` health series; `GET /monitoring/endpoints` then answers 503 with
`status: unknown` and says so. Connectivity monitoring is unaffected.

`monitoring.api.enabled: false` turns off the endpoint catalog and takes health
with it - health reads that catalog.

The two checks can also be turned off individually:
`use-dependency-state: false` drops check 2, and health falls back to traffic
alone; `verify-mappings: false` drops check 1, and every endpoint is treated as
mapped.
