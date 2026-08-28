# Response time per API

How long `airtel-aaa-user-provisioning-service` actually takes to answer each
request, and which API the request was for.

```
http_server_requests (Spring Boot's own timer)
  + the `api` tag from the endpoint catalog
  + the histogram buckets from management.metrics.distribution.slo
        |                                    ^
        |                                    | percentiles, heatmap, "slower than"
        v                                    |
  /actuator/prometheus --> Prometheus --> Grafana
        ^                                    |
        |                                    | slow request count
  api_slow_requests_total  <-- ApiResponseTimeRecorder --> WARN line per slow
  api_request_duration_last_seconds              request, with its exact duration
```

This is the fourth of the monitoring documents here, and the one that answers
"how long is this API taking?":

| Document | Question it answers |
| --- | --- |
| **RESPONSE-TIME.md** (this file) | How long each request took, per API - the distribution, and the individual slow ones. |
| [API-METRICS.md](API-METRICS.md) | How many calls each API served, how many failed, and their average response time. |
| [HEALTH.md](HEALTH.md) | Whether each endpoint is fit to serve - including the ones nobody called. |
| [CONNECTIVITY.md](CONNECTIVITY.md) | Whether Oracle, Redis and Kafka are reachable at all. |

[README.md](README.md) covers what they have in common: the endpoint catalog and
the scrape.

## What "each individual request" can and cannot mean

Prometheus stores samples per scrape, not per request. Whatever the service
measures, what a scrape can carry is a counter, a total and a set of buckets - so
no dashboard reading Prometheus can plot one dot per request. What it can do,
and what this dashboard does, is show the **distribution** every individual
request falls into, and then name the individual requests that matter:

| Question | Where the answer comes from |
| --- | --- |
| What did a typical request take? | `histogram_quantile(0.5, ...)` over `http_server_requests_seconds_bucket` |
| What did a slow request take? | the same, at 0.95 and 0.99 |
| How were all of them spread out? | the heatmap - one row per bucket, one column per time step, coloured by how many requests landed there |
| What did the slowest single request take? | `http_server_requests_seconds_max`, the timer's own max over a rolling two-minute window |
| How many requests were slower than a second? | the total minus the `le="1.0"` bucket |
| **Which** request was slow, and when? | the WARN line `ApiResponseTimeRecorder` writes for it, with the exact duration and the request identifier |
| What did the last request take? | `api_request_duration_last_seconds`, one real request per API per scrape |

The last two are the only per-request numbers here. Everything else is an
aggregate - an accurate one, and still an aggregate.

### Why percentiles rather than the average

[API-METRICS.md](API-METRICS.md) already publishes an average response time per
API, and it is exact: the timer's own total divided by its own count. What it
cannot show is a slow tail. Ten requests of four seconds inside a thousand
100ms ones move the average by 39ms and move nothing else; the p99 moves to four
seconds, which is what the callers of those ten requests experienced.

Read them together. An average that is fine with a p95 that is not means a few
slow requests, and the panels under "Individual requests" will name them. An
average that has risen with the p95 means everything got slower, and the cause is
usually below the service - see [CONNECTIVITY.md](CONNECTIVITY.md).

### What a percentile off a histogram costs

The percentiles are estimates. Prometheus knows how many requests fell between
750ms and 1s, not where in that range they fell, so it interpolates linearly
inside the bucket. Two consequences worth knowing before reading a number off
this dashboard as if it were exact:

- **A percentile is only as precise as its bucket.** A p99 that lands between 3s
  and 5s is reported somewhere in that range, and its exact value depends on an
  assumption - an even spread inside the bucket - that is rarely true.
- **Past the last bucket there is nothing to interpolate.** The largest boundary
  is 10s, so a p99 reported as exactly 10s means "at least 10s", and the
  `Slowest single request` panel is the one to read instead.

The bucket list is `management.metrics.distribution.slo` in `application.yml`.
Adding boundaries makes the percentiles more precise and costs one time series
per endpoint per boundary; there is no alert and no budget attached to any of
them.

## The individual slow request

`ApiResponseTimeRecorder` is a filter registered ahead of the rest of the chain.
It times every request the catalog knows about, and for the ones at or over
`monitoring.api.response-time.slow-request-threshold-ms` it writes:

```
WARN  Slow response time: api=create_user POST /api/user status=200 duration_ms=1840 UUID=8b1e...
```

`duration_ms` is that one request's response time. `UUID` is the request
identifier `RequestUuidInterceptor` put in the MDC, so the line sits beside the
request and response the logging interceptor wrote for the same call - which is
how a point on a chart becomes a request with a payload and a caller.

The same requests are counted, so that "how many were slow" is a chart rather
than a log search:

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `api_slow_requests_total` | counter | `api`, `method`, `status`, `severity`, `microservice` | One per request at or over the slow threshold. `severity` is `slow` or `very_slow`. |
| `api_request_duration_last_seconds` | gauge | `api`, `microservice` | The response time of the most recent request that API served. |

Cardinality is bounded by the catalog: nine APIs times the handful of statuses
they answer, times two severities, and only for requests that were actually slow.

Two things to know about these numbers:

- **The recorder measures more than the timer does.** It is on the stack for the
  whole filter chain; `http_server_requests` starts inside the dispatcher
  servlet. The recorder's duration is a few hundred microseconds larger, and
  larger still when a filter ahead of the handler is what is slow - which is why
  `api_slow_requests_total` can sit slightly above the bucket-derived count on
  the panel beside it. Neither is wrong: the recorder's number is closer to what
  the caller waited for.
- **`api_request_duration_last_seconds` is a sample, not a summary.** It holds
  the last request before the scrape, so on a busy API it shows one request in
  hundreds. It is most useful on a quiet endpoint, where the last request is most
  of them.

Uncatalogued traffic - everything tagged `api="other"`, the actuator endpoints
included - is not recorded at all unless `include-uncatalogued` is turned on. It
has no API to be reported under.

## Setting it up

The distribution needs nothing beyond the scrape [README.md](README.md#scraping)
already sets up. The per-request half needs the service running with
`monitoring.api.response-time.enabled: true`, which is the default.

1. Confirm the histogram is being exported with the `api` tag, on any pod:

   ```sh
   curl -s localhost:8089/actuator/prometheus | grep 'http_server_requests_seconds_bucket.*api='
   ```

   Nothing back means either no traffic yet, `monitoring.api.enabled: false`, or
   a `management.metrics.distribution.slo` that no longer names
   `[http.server.requests]`.

2. Import `grafana/user-provisioning-api-response-time.json` in Grafana
   (Dashboards -> New -> Import), and pick the Prometheus data source that
   scrapes this service.

The dashboard uid is `aaa-user-prov-api-response-time`; re-importing over it
keeps the same URL.

## Configuration

```yaml
monitoring:
  api:
    response-time:
      enabled: true
      slow-request-threshold-ms: 1000
      very-slow-request-threshold-ms: 3000
      log-every-request: false
      include-uncatalogued: false
```

| Setting | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master switch for the recorder. The dashboard's percentiles, heatmap and slowest-request panels keep working without it; the "Individual requests" row goes empty. |
| `slow-request-threshold-ms` | `1000` | At or over this, one WARN line and one `api_slow_requests_total` increment per request. |
| `very-slow-request-threshold-ms` | `3000` | At or over this, the same with `severity="very_slow"`. |
| `log-every-request` | `false` | Logs every request's duration at INFO, not just the slow ones. One line per request - turn it on where that is affordable. |
| `include-uncatalogued` | `false` | Also record traffic outside the catalog, actuator scrapes included. |

The thresholds are reporting thresholds, not budgets. No request is treated
differently for crossing one, and nothing alerts on them.

## Reading the dashboard

| Row | Panel | What it is for |
| --- | --- | --- |
| Totals | p50 / p95 / p99 | The service in one line. p95 is the one to read first when somebody says it is slow. |
| Totals | Slowest single request | The worst individual request in the range, exactly as measured. |
| Totals | Slower than threshold / Share | How many requests crossed the picker at the top, and what share of traffic that was. |
| Per API | **Response time by API** | The main panel. One row per API, sorted so the slow one comes to the top. Percentiles beside the exact Slowest and Avg. |
| Per API | Distribution of individual response times | The heatmap. A single low band is a service answering consistently; a second band higher up is a slow path a percentile would average away. |
| Per API | p95 response time by API | Which API is the slow one, ordered. |
| Over time | p95 / p99 by API | When it got slow, and which API it was. |
| Over time | Slowest request by API | The peaks. The line falls back on its own as Micrometer's rolling max decays - a step down is not a recovery. |
| Over time | Requests slower than the threshold | How many requests crossed the line, per step, stacked by API. |
| Individual requests | Slow requests recorded by the service | The recorder's own count, split by severity. Every request on this chart is also a WARN line in the log. |
| Individual requests | Slow requests by API, status and severity | The breakdown. A slow 200 is the service being slow; a slow 500 is usually a timeout underneath it. |
| Individual requests | Most recent request, by API | One real request per API per scrape. |

Three selectors at the top: **Microservice** and **API**, the same two the other
dashboards carry, and **Slow at**, which sets what the "slower than threshold"
panels count.

### The Slow at picker

Its values are the histogram bucket boundaries, spelled the way Micrometer
publishes them - a 1 second boundary is `le="1.0"`, not `le="1"`. A boundary that
is not a bucket has nothing to count and the panels would read empty, which is
why the picker is a fixed list rather than free text. **Change
`management.metrics.distribution.slo` and this list has to change with it**, in
the `threshold` variable of the dashboard JSON.

### An API nobody called

The Requests column falls back to 0 through `api_endpoint_info`, exactly as on
the API metrics dashboard. The timing columns deliberately do not: no requests
means no response time, and a 0 there would read as instant rather than as
untested. Whether an untouched API is alright at all is
[HEALTH.md](HEALTH.md)'s question.

### The colours

Amber and red on every response time panel are 1s and 3s, matching the two
recorder thresholds, so a row that turns amber here is one that is writing WARN
lines in the log. They are **display cues only** - they live in each panel's
`fieldConfig`, no API has a latency budget, and nothing alerts on them. Change
them to whatever reads well for this service.

## Testing the queries

`prometheus/user-provisioning-api-response-time-queries_test.yaml` is a
`promtool` unit test over the panel expressions themselves. It asserts the
percentiles against a fixture whose bucket counts are chosen so the expected
values can be worked out by hand, the exact slowest request beside them, the
over-threshold counts, and the blank a percentile leaves for an API with no
traffic.

```sh
cd monitoring/prometheus
promtool test rules user-provisioning-api-response-time-queries_test.yaml
```

It needs no rule files - the expressions are inline. They are the dashboard's
own, with the five Grafana variables substituted; the header of the file lists
the substitutions, and they have to be kept in step when a panel query changes.

## Turning it off

`monitoring.api.response-time.enabled: false` removes the filter, and with it the
slow request log lines and both `api_*` series above. The dashboard still works -
everything outside the "Individual requests" row reads `http_server_requests`,
which Spring Boot publishes either way.

`monitoring.api.enabled: false` goes further and drops the `api` tag itself,
which takes this dashboard, the API metrics dashboard and per-endpoint health
with it. See [README.md](README.md#turning-it-off).
