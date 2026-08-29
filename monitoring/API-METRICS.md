# Success, failure and response time per API

How much traffic each REST API of `airtel-aaa-user-provisioning-service` took,
how much of it worked, and how long it took to answer.

```
http_server_requests (Spring Boot's own timer)
  + the `api` tag from the endpoint catalog
        |
        v
  /actuator/prometheus --> Prometheus --> Grafana
```

Three numbers per API, and nothing in the service had to be written to produce
them - Spring Boot already times every request, and the endpoint catalog already
labels it with which API served it.

This is one of the four monitoring documents here, and the one that answers
"how is this API doing?":

| Document | Question it answers |
| --- | --- |
| **API-METRICS.md** (this file) | How many calls each API served, how many failed, and how long they took on average. |
| [RESPONSE-TIME.md](RESPONSE-TIME.md) | How long each individual request took - percentiles, the distribution, and the slow ones by name. |
| [HEALTH.md](HEALTH.md) | Whether each endpoint is fit to serve - including the ones nobody called. |
| [CONNECTIVITY.md](CONNECTIVITY.md) | Whether Oracle, Redis and Kafka are reachable at all. |

[README.md](README.md) covers what they have in common: the endpoint catalog and
the scrape.

## The three metrics

All three come from one Micrometer timer, `http_server_requests`, which Prometheus
exposes as `http_server_requests_seconds_count` and `http_server_requests_seconds_sum`.
Every series carries the `api` tag, so every number below is per API.

| Shown as | Is | Read from |
| --- | --- | --- |
| **Success** | Requests that answered below 400 | `_count` where `outcome` is not `CLIENT_ERROR` or `SERVER_ERROR` |
| **Failed** | Requests that answered 4xx or 5xx | `_count` where `outcome` is `CLIENT_ERROR` or `SERVER_ERROR` |
| **Avg response time** | Seconds spent serving, per request served | `_sum` over `_count` |

### Why the split is drawn there

`outcome` is Micrometer's own summary of the status code, and the two error
values cover exactly 4xx and 5xx. Everything else - 2xx, 3xx, and the `UNKNOWN`
outcome of a response with no status - counts as success, because the split is
written as `outcome!~"CLIENT_ERROR|SERVER_ERROR"` rather than as a list of the
good outcomes. Success and Failed therefore always add up to Total, with no
request falling between them.

**A 4xx is counted as a failure.** From the caller's side a rejected request did
not work, so it belongs in the failure count. But it is usually the caller's
fault and not the service's, which is why the table keeps
`Client errors (4xx)` and `Server errors (5xx)` as separate columns and the
`Failures by status code` panel breaks the count down further. A rising Failed
count that is entirely 4xx is a client sending bad requests; the same count in
5xx is this service breaking. Per-endpoint health, by contrast, only counts 5xx
- see [HEALTH.md](HEALTH.md) for why the two differ.

### A request that never reached a handler

Not every failure gets as far as a controller. `ChannelAuthFilter` answers 400
for a missing `channel` header and 401 for credentials that did not check out,
the rate limiter answers 429, and Spring Security's request firewall answers 400
- all of them from the filter chain, before Spring has matched a handler.

Those requests have no URI template, and Micrometer reports them as
`uri="UNKNOWN"`. Reading the `api` tag off the template alone therefore filed
every one of them under `api="other"`, which the **API** picker never selects:
the 4xx disappeared from the API's Failed count, from `Client errors (4xx)`, and
from the totals across the top, while `Uncatalogued` quietly grew. An API being
hammered with bad credentials read as an API where nothing had failed.

`ApiNameObservationConvention` now matches the path the caller asked for against
the catalog when there is no template, so the rejection is counted against the
API it was aimed at. Cardinality is unchanged - the path only ever picks a
catalog entry, and anything unrecognised is still `other`. The method has to
match as well, so a 405 on a catalogued path stays on `other`: nothing served
it, and it is not a failure of the verb that *is* mapped there.

### A request that failed on its way out

The observation is stopped as the request leaves the filter chain, which is
before the container's error dispatch has set a status on the response. An
exception nothing handled - thrown by a filter, or by anything
`GlobalExceptionHandler` does not cover - was therefore timed with whatever
status the response still carried, normally 200, and counted as a **success**
even though the caller received a 500.

The convention overrides `status` and `outcome` for that case: an observation
that carries an error and a status still below 400 is recorded as `500` /
`SERVER_ERROR`, the answer the caller is about to be given. That is the rule
`ApiResponseTimeRecorder` already applied to its own `status` tag, so the timer
and the per-request metrics beside it now say the same thing about the same
request - the recorder resolves a rejected request's API the same way too, and
would otherwise have dropped it entirely, since `include-uncatalogued` is off.

Both corrections matter in the same direction. Neither one leaves a gap on the
dashboard when it is missing - both report a success instead, which is the one
kind of wrong a failure count must not be.

### Why the average and not a percentile

The average is exact: it is the timer's own total time divided by its own
request count, not an estimate off a histogram. It moves when the whole API
slows down, which is the question "how fast is this API" usually means.

What it will not show you is a slow tail. A handful of very slow calls barely
move an average computed over thousands of fast ones. That is what
[RESPONSE-TIME.md](RESPONSE-TIME.md) is for: the same timer's histogram buckets,
read as p50, p95 and p99 per API, on a dashboard of their own. No panel here
reads them - when the average on this page looks fine and somebody is still
calling the service slow, that is the page to open.

### Counts are over the dashboard's time range

The stat row, the table and the two charts on the right of "Per API" all count
over whatever range the time picker is set to, through `increase(...[$__range])`.
Change the range from 6h to 24h and every count grows. The charts under "Over
time" instead count over one step of the chart, so their shape follows traffic
and their height moves with the zoom level - the exact totals are the ones in the
table.

`increase()` extrapolates to the edges of its window and corrects for counter
resets, so a count can come back very slightly fractional. The panels round to
whole requests.

## Setting it up

The metrics need nothing beyond the scrape [README.md](README.md#scraping)
already sets up, and the dashboard reads them directly - there are no recording
rules to deploy and no alerts.

1. Confirm the timer is being exported with the `api` tag, on any pod:

   ```sh
   curl -s localhost:8089/actuator/prometheus | grep 'http_server_requests_seconds_count.*api='
   ```

   Nothing back means either no traffic yet or `monitoring.api.enabled: false`.

2. Import `grafana/user-provisioning-api-metrics.json` in Grafana
   (Dashboards -> New -> Import), and pick the Prometheus data source that
   scrapes this service.

The dashboard uid is `aaa-user-prov-api-metrics`; re-importing over it keeps the
same URL.

## Reading the dashboard

| Row | Panel | What it is for |
| --- | --- | --- |
| Totals | Requests / Successful / Failed / Failure rate / Average response time | The whole service in one line, for the selected range. |
| Totals | Uncatalogued | Traffic tagged `api="other"`. Should be a trickle; anything more means the catalog is missing an entry. |
| Per API | Success, failure and response time by API | **The main panel.** One row per API, sorted so a failing one comes to the top. |
| Per API | Success and failure count by API | The same two counts as stacked bars - bar height is total traffic, red is what failed. |
| Per API | Average response time by API | Which API is the slow one, ordered. |
| Over time | Successful / Failed requests over time | When the traffic and the failures happened. |
| Over time | Average response time by API | Whether an API got slower, and when. |
| What failed | Failures by status code | 400s and 401s (callers) versus 500s and 503s (this service). |
| What failed | Failures by API, status and exception | The exact failing combinations, worst first. |

Two selectors at the top: **Microservice**, so the dashboard still works on a
Prometheus shared with the rest of the AAA stack, and **API**, which is
populated from the endpoint catalog and so lists every API whether or not it has
taken traffic.

### An API nobody called

An API with no traffic has no `http_server_requests` series at all, so its row
would simply be missing. The catalog fills the gap: `api_endpoint_info` is a
constant 1 per catalogued API, published from startup, and the count queries end
in `or sum by (api) (0 * api_endpoint_info{...})` so an untouched API reads 0
rather than dropping out of the table.

The response time columns deliberately do **not** get that fallback. No requests
means no response time, and a 0 there would read as instant rather than as
untested - so they stay blank, and the bar gauge leaves the API out.

"Nothing failed" and "never called" therefore look different on this dashboard,
which is the distinction a count of requests cannot make on its own. Whether an
untouched API is actually alright is [HEALTH.md](HEALTH.md)'s question, not this
one's.

### The colours

Amber and red on Failure % are 1% and 10% - the same thresholds
`monitoring.api.health.degraded-error-ratio` and `unhealthy-error-ratio` use, so
a row that turns amber here is the one the health dashboard will call DEGRADED.

Amber and red on Avg response time are 1s and 3s. Those are **display cues
only**. No API here has a latency budget, nothing alerts on them, and they are
in the panel's `fieldConfig.overrides` - change them to whatever reads well for
this service.

## Testing the queries

The tags the queries read are covered by `ApiNameObservationConventionTest` and
`ApiEndpointRegistryTest`: that a request rejected before the handler is counted
against its API, that an unrecognised path still collapses to `other`, and that
an exception which escaped the chain is recorded as a 500 rather than a success.

`prometheus/user-provisioning-api-metrics-queries_test.yaml` is a `promtool`
unit test over the panel expressions themselves: it asserts the success/failure
split, the exact average, the 0 for an API with no traffic and the blank
response time beside it.

```sh
cd monitoring/prometheus
promtool test rules user-provisioning-api-metrics-queries_test.yaml
```

It needs no rule files - the expressions are inline. They are the dashboard's
own, with the four Grafana variables substituted; the header of the file lists
the substitutions, and they have to be kept in step when a panel query changes.

## Changing which APIs appear

The API list is the endpoint catalog, so an API appears here as soon as it is in
`monitoring.api.endpoints` - see
[README.md](README.md#changing-the-catalog). No dashboard change is needed: the
panels group by the `api` label and the picker is populated from the catalog.
