# REST endpoint availability and response time monitoring

Availability and response time of the REST endpoints of
`airtel-aaa-user-provisioning-service`, per endpoint, from the service's own
Micrometer metrics through Prometheus into Grafana.

```
Spring MVC request
  -> ApiNameObservationConvention   adds the `api` tag to http_server_requests
  -> /actuator/prometheus           scraped by the ServiceMonitor
  -> Grafana dashboard + PrometheusRule alerts
```

There is nothing to tune: no per-endpoint budgets, no alert thresholds on
latency, no synthetic probe. Both figures are read straight off the one request
timer Spring Boot already publishes.

This file covers the REST endpoints. Whether the service can reach the Oracle
database, Redis and Kafka is monitored separately - see
[CONNECTIVITY.md](CONNECTIVITY.md). The two answer different questions: this one
says whether requests are being served, that one says whether the infrastructure
behind them is reachable, and a dependency outage usually shows up there first.

## What is in here

| File | Purpose |
| --- | --- |
| `grafana/user-provisioning-endpoint-availability.json` | The dashboard. Import into Grafana. |
| `prometheus/servicemonitor.yaml` | Makes the Prometheus Operator scrape the pods. |
| `prometheus/user-provisioning-endpoint-rules.yaml` | Recording rules for availability and response time, plus the availability alerts. |
| `prometheus/user-provisioning-endpoint-rules_test.yaml` | `promtool` unit test pinning down what those rules return in each case. |
| `CONNECTIVITY.md` | Dependency connectivity monitoring (Oracle / Redis / Kafka) - the doc for the two files below. |
| `grafana/user-provisioning-dependency-connectivity.json` | The connectivity dashboard. Import into Grafana. |
| `prometheus/user-provisioning-connectivity-rules.yaml` | Connectivity alerts (dependency down, pool exhausted, flapping, slow probes). |

## Metrics published

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `http_server_requests_seconds_count` | counter | `api`, `method`, `uri`, `status`, `outcome`, `microservice` | Every served request. The `api` label is what everything groups by. |
| `http_server_requests_seconds_sum` | counter | same | Total time spent in those requests. With `_count` this gives an exact average. |
| `http_server_requests_seconds_bucket` | histogram | same, plus `le` | How many requests finished within each bucket. This is what percentiles are read from. |
| `http_server_requests_seconds_max` | gauge | same | The slowest single request in a short rolling window. |
| `api_endpoint_info` | gauge | `api`, `method`, `title`, `microservice` | Constant 1, one per catalogued endpoint. This is the catalog itself: it exists from startup, before any traffic. |

All four request series come from one Micrometer timer, so response time costs
no extra instrumentation - only the buckets, which are configured in
`application.yml` (see below).

The `api` label comes from `monitoring.api.endpoints[].name` in
`application.yml`. Traffic to a path that is not in the catalog is tagged
`api="other"`, so the label can never blow up Prometheus cardinality no matter
what gets requested. Every dashboard query filters `other` out.

## How availability is computed

One expression, used identically by the dashboard and by the
`api:availability_ratio:rate5m` recording rule:

```promql
1
-
(
  sum by (api) (rate(http_server_requests_seconds_count{api!="other", status=~"5.."}[5m]))
  or
  sum by (api) (rate(http_server_requests_seconds_count{api!="other"}[5m])) * 0
)
/
sum by (api) (rate(http_server_requests_seconds_count{api!="other"}[5m]))
or
max by (api) (api_endpoint_info)
```

Read it in three parts:

1. **the 5xx rate.** The `or <total> * 0` tail is not decoration. A Prometheus
   selector that matches nothing returns *no series*, not zero - so without it
   an endpoint that served a thousand clean requests and zero errors would have
   an empty numerator and therefore no availability figure at all.
2. **divided by the total request rate, subtracted from 1** - the share of
   responses that were not 5xx.
3. **`or api_endpoint_info`** covers endpoints that took no traffic in the
   window. They have no request series, so parts 1 and 2 are empty, and the
   constant 1 published per catalogued endpoint stands in: nothing was called,
   so nothing failed. This is what keeps all nine endpoints on the dashboard
   instead of only the ones that happen to be busy.

`prometheus/user-provisioning-endpoint-rules_test.yaml` runs this against a real
Prometheus engine and asserts all four outcomes - busy and clean, partly
failing, entirely failing, and idle. Run it after changing the expression; the
header of that file has the two commands.

Two things follow from this and are worth being explicit about:

- **4xx counts as available.** Rejecting a malformed request is the endpoint
  doing its job. Watch validation failures on the *Response status mix* panel
  instead.
- **An idle endpoint reads 100%.** That is "nothing failed", not "proven
  healthy". The dashboard puts *Requests/s* next to *Availability* in the table
  for exactly this reason - 100% over 0 req/s says very little. Nothing that
  never reaches the application (a pod that is down, a request rejected by the
  ingress) can appear here either; `UserProvisioningNoMetrics` covers the case
  where no pod is reporting at all.

## How response time is measured

Three views of the same timer, all per endpoint:

| Panel / rule | Expression | What it is |
| --- | --- | --- |
| Average | `rate(..._sum) / rate(..._count)` | Exact. Moves when the whole endpoint slows down. |
| p95 / p99 | `histogram_quantile(q, sum by (api, le) (rate(..._bucket)))` | An estimate read off the buckets. Moves when the slow tail grows. |
| Slowest single request | `max by (api) (..._max)` | The one outlier percentiles smooth away. |

Read the average and p95 together. An endpoint whose average is flat while p95
climbs has a growing tail, not a general slowdown; the p99 panel and the
slowest-request panel are where a single stuck call shows up.

**Percentiles are bucket estimates.** The buckets are set once, for all
endpoints, in `application.yml`:

```yaml
management:
  metrics:
    distribution:
      slo:
        "[http.server.requests]": 25ms,50ms,100ms,200ms,300ms,500ms,750ms,1s,2s,3s,5s,10s
```

`histogram_quantile` interpolates inside whichever bucket the percentile lands
in, so a figure is only as precise as the bucket edges around it - an endpoint
that always answers in 40 ms reports a p95 of about 49 ms, because every one of
its requests lands in the 25-50 ms bucket and the estimate is interpolated
across the whole of it. Add an edge where you need resolution. Every
edge is another time series per endpoint, so add them deliberately; anything
slower than the last edge is reported as that edge.

These buckets are *not* thresholds. Nothing alerts on them, and no endpoint has
a target of its own - they only decide how finely the histogram is sliced. The
recording rules `api:latency_seconds:avg_rate5m`, `:p95_rate5m` and `:p99_rate5m`
are there if you later want to alert on one; no latency alert ships.

**An endpoint with no traffic has no response time.** Unlike availability, which
falls back to 100% for an idle endpoint, the latency panels simply draw nothing
- there is no response time to report, and inventing one would be a lie.

## Setting it up

**1. Confirm the service is exporting.** On any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep api_endpoint_info
curl -s localhost:8089/actuator/prometheus | grep -c http_server_requests_seconds_bucket
```

Nine lines should come back from the first command, one per endpoint in the
catalog. If it is empty, nothing downstream will work - check
`monitoring.api.enabled` first. The second should be non-zero once the service
has taken a request; if it is 0, the histogram buckets are not configured and
the percentile panels will stay empty - check
`management.metrics.distribution.slo` in `application.yml`.

**2. Scrape it.** Check the label your Service actually carries, then apply the
ServiceMonitor:

```sh
oc -n airtel-aaa get svc --show-labels
oc -n airtel-aaa apply -f prometheus/servicemonitor.yaml
```

Confirm the target is up in Prometheus under Status -> Targets. A ServiceMonitor
whose selector matches nothing is accepted without complaint and simply never
scrapes, so do not skip this check.

**3. Alerts.**

```sh
oc -n airtel-aaa apply -f prometheus/user-provisioning-endpoint-rules.yaml
```

| Alert | Fires when |
| --- | --- |
| `UserProvisioningNoMetrics` | `api_endpoint_info` is absent for 5 minutes - no pod is being scraped. |
| `UserProvisioningEndpointAvailabilityLow` | An endpoint is below 99% for 5 minutes. |
| `UserProvisioningEndpointAvailabilityCritical` | An endpoint is below 95% for 2 minutes. |

No response time alert ships, since that would need a threshold per endpoint.
The latency recording rules are in place if you want to add one.

**4. Dashboard.** Grafana -> Dashboards -> New -> Import -> upload
`grafana/user-provisioning-endpoint-availability.json`, then pick the Prometheus
data source. Its UID is a dashboard variable, so the JSON is not tied to any one
Grafana instance. The dashboard queries Prometheus directly and does not depend
on the recording rule, so it works whether or not step 3 was done.

## Changing the catalog

Endpoints are configured, not hard coded. To add one, add an entry under
`monitoring.api.endpoints` in `src/main/resources/application.yml` **and** in
`application-telco_aaa_dev.yml`:

```yaml
      - name: get_bng            # becomes the `api` label - never change it later
        title: Get BNG           # shown on the dashboard
        method: GET
        uris:
          - /api/bng/{bng_id}    # the Spring URI template, path variable names ignored
```

`uris` must match the controller's request mapping structure exactly - path
variable *names* are ignored, but the path shape is not. A handler mapped to
several paths lists all of them, and they all report as one endpoint.
`ApiMonitoringCatalogTest` scans the controllers and fails the build if an entry
stops matching a real request mapping, so a silent drift cannot reach
production.

No dashboard change is needed: the panels are driven by the `api` label and the
endpoint variable is populated from `api_endpoint_info`.

## Turning it off

`monitoring.api.enabled: false` drops the `api` tag, the `microservice` common
tag and `api_endpoint_info`. `http_server_requests` keeps working with its stock
Spring Boot tags; the dashboard, which groups by `api`, will go empty.

Removing `management.metrics.distribution.slo` turns off the histogram buckets
on their own: availability, request rate and the average response time carry on
working, and only the p95/p99 panels go empty.
