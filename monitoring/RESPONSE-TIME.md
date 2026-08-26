# API response time

How long each request to `airtel-aaa-user-provisioning-service` actually took.

This is deliberately not a document about average response time. The average is
the one summary of a latency distribution that cannot answer the question people
ask of it. It is pulled around by the slow tail, so it does not describe the
typical request; and it is diluted by volume, so it does not describe the slow
one either. Ten thousand requests served in 50ms alongside ten that took thirty
seconds average out to about 80ms - a number that is true of nothing that
happened, and that hides ten callers who waited half a minute.

So nothing here is an average. There are three ways to see a real request:

| What | Series | What it is |
| --- | --- | --- |
| The distribution | `http_server_requests_seconds_bucket` | Every request, placed in the latency band it fell into. The heatmap draws this directly. |
| Individual requests | exemplars on those buckets | Single real requests, each with its exact duration and the request id that produced it. |
| The slowest request | `http_server_requests_seconds_max` | Not a statistic - the duration of the single slowest request in Micrometer's window. |

Percentiles are computed from the first of these and are on the dashboard too,
because `p99 = 2s` is a true statement about one request in a hundred in a way
that a mean is not about anything.

## Exemplars: the actual duration of one request

A Prometheus histogram bucket is a counter. It can say that eleven requests took
between 500ms and 750ms, but the eleven durations themselves are discarded the
moment they are counted, and no query can get them back. An exemplar is the one
exception in the exposition format: a single observed value, its timestamp, and
a small label set, carried alongside the bucket that counted it.

This service labels each exemplar with the id of the request that produced the
duration:

```
http_server_requests_seconds_bucket{api="get_user",le="0.5"} 41.0 # {span_id="0f2c...",trace_id="0f2c..."} 0.42 1756211043.201
                                                                                                          ^^^^ this request took 420ms
```

`trace_id` is the request id - the same value `RequestUuidInterceptor` puts in
the logging MDC, taken from the caller's `UUID` header when one is sent and
generated when it is not. So a slow point on the dashboard is followable: hover
it, take the id, and it appears in the pod logs and in the `ACTION_LOG` row for
that request. There is no distributed tracing backend here; the names `trace_id`
and `span_id` are used because they are what Prometheus and Grafana already look
for, and one request is one span.

### What exemplars cannot do

The exposition format carries **at most one exemplar per bucket per scrape**. So
the ceiling is one named request per latency band, per endpoint, per scrape
interval - twelve buckets and a 30s scrape means up to twelve real requests
named per endpoint every 30 seconds. Requests that share a band between two
scrapes are counted in the histogram, but only the last one is named.

That ceiling is why `min-retention-ms` defaults to `0`. Prometheus's own
`DefaultExemplarSampler` holds an exemplar for about seven seconds before it
will take another, because it is sampling traces to store; here the point is to
see requests, so every request replaces the exemplar for its bucket and each
scrape reports the most recent real request in each band.

For the requests that fall through anyway, there is a log line - see
[Slow request logging](#slow-request-logging).

### What has to be true of Prometheus

Exemplars are exported by the service unconditionally, but they only survive the
scrape if two things hold at the Prometheus end. Neither produces an error when
missing; the dashboard simply draws percentile lines with no points on them.

1. **Exemplar storage is enabled.** Prometheus must run with
   `--enable-feature=exemplar-storage`. On the Prometheus Operator, that is
   `spec.enableFeatures` on the Prometheus resource:

   ```yaml
   spec:
     enableFeatures:
       - exemplar-storage
   ```

2. **The scrape is in OpenMetrics format.** The older `text/plain` exposition
   has no syntax for exemplars at all. Prometheus negotiates OpenMetrics by
   default, so this normally follows from the first point - but a scrape config
   that pins the accept header will silently drop them.

Then, in the Grafana Prometheus datasource, under **Exemplars**, add an internal
or external link on the label `trace_id`. Without a link configured Grafana
still draws the exemplars and still shows the labels on hover, which is enough
to read the request id; the link only saves a copy and paste.

Check the service side from any pod:

```sh
curl -s -H 'Accept: application/openmetrics-text; version=1.0.0; charset=utf-8' \
  localhost:8089/actuator/prometheus | grep 'seconds_bucket.*# {'
```

Bucket lines with a `# {trace_id="..."}` suffix mean the service is doing its
part, and anything missing after that is Prometheus or Grafana configuration. If
that command returns nothing at all, work down the list in
[Nothing is showing up](#nothing-is-showing-up).

## The slowest single request

`http_server_requests_seconds_max` is a Micrometer gauge holding the largest
value the timer has seen in its own decaying window. It is worth its own panel
and its own alert because it is the only series here that volume cannot dilute:
one 30 second request among ten thousand fast ones moves no percentile and
barely moves a mean, but it shows on this line at full height.

It decays back to zero on its own once the slow requests stop, so a spike here
that is not accompanied by a raised p99 is exactly what it looks like - a small
number of very slow requests against a healthy majority.

## Slow request logging

`ResponseTimeFilter` also times each request itself and writes a WARN line for
any that crosses `slow-request-threshold-ms`:

```
Slow request: requestId=0f2c... method=POST uri=/api/user status=200 responseTimeMs=4210 thresholdMs=1000
```

This is the backstop for the exemplar ceiling above: a slow request that shared
a bucket with a later one is never named in Prometheus, but it is always named
here. It is a log line rather than a metric on purpose - a count of slow
requests is something the histogram already gives, and what is missing at that
point is *which* request.

The filter is registered at `Ordered.HIGHEST_PRECEDENCE`, immediately outside
Spring's `ServerHttpObservationFilter`, so what it measures is very nearly what
the metric measures. That position is also what makes the exemplars possible at
all: the request timer is stopped inside the observation filter, outside every
`HandlerInterceptor`, so by the time a duration is recorded the MDC has already
been cleared and the request id would otherwise be gone.

## Metrics published

Everything on the dashboard comes from Spring Boot's own request timer. This
feature adds no new series - only exemplars on the ones that already exist,
which cost no cardinality because an exemplar is not a time series.

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `http_server_requests_seconds_bucket` | histogram | `api`, `method`, `uri`, `status`, `outcome`, `microservice`, `le` | Requests that completed within `le` seconds. Carries the exemplars. |
| `http_server_requests_seconds_count` | counter | as above | Requests completed. |
| `http_server_requests_seconds_sum` | counter | as above | Total time spent. Only useful as a divisor, which is the average - see the top of this file. |
| `http_server_requests_seconds_max` | gauge | as above without `le` | The slowest single request in the current window. |

The `api` label is the catalog slug from `monitoring.api.endpoints`; see
[README.md](README.md#the-endpoint-catalog). Untracked traffic reports as
`api="other"`, so the label cannot blow up cardinality.

## Setting it up

The dashboard and rules assume the scrape in
[README.md](README.md#scraping) is already in place.

```sh
# Alerts
oc -n airtel-aaa apply -f prometheus/user-provisioning-response-time-rules.yaml
```

Import `grafana/user-provisioning-response-time.json` in Grafana, pick the
Prometheus datasource, and enable exemplar storage as above.

To run the rule tests, which need the `spec` out of the PrometheusRule first:

```sh
cd prometheus
python3 -c "import yaml; \
  yaml.safe_dump(yaml.safe_load(open('user-provisioning-response-time-rules.yaml'))['spec'], \
                 open('response-time-rules.yaml','w'), sort_keys=False)"
promtool test rules user-provisioning-response-time-rules_test.yaml
rm response-time-rules.yaml
```

## Reading the dashboard

| Panel | Question it answers |
| --- | --- |
| Slowest single request | Did anybody wait a very long time? One real request, not a statistic. |
| p99 / median | How slow is the unlucky tail, and how far is it from the typical request? |
| Slowest endpoint | Which endpoint to look at first. |
| Where every request landed | The whole distribution over time. A band brightening near the top is a few slow requests hiding inside healthy averages. |
| Individual requests against the percentiles | Real requests plotted at their real duration, against the percentile lines. Click a point for its request id. |
| Response time by endpoint | The same numbers per endpoint, sorted worst first. |
| p99 by endpoint | Which endpoint went slow, and when. |
| Slowest single request by endpoint | Spikes that no percentile will show. |

A useful shape to recognise: **median flat, p99 climbing** means the endpoint is
failing a minority of its callers - usually a slow dependency on one code path,
or one pod. **Everything climbing together** is usually not the endpoint at all;
check [CONNECTIVITY.md](CONNECTIVITY.md), where probe latency across Oracle,
Redis and Kafka explains every endpoint at once.

## Alerts

| Alert | Fires when |
| --- | --- |
| `UserProvisioningEndpointSlow` | p99 over 2s for 10 minutes - warning. |
| `UserProvisioningEndpointVerySlow` | p99 over 5s for 5 minutes - critical; callers are timing out. |
| `UserProvisioningRequestExtremelySlow` | A single request took over 10s. Deliberately separate: this is invisible to every percentile. |
| `UserProvisioningResponseTimeHistogramMissing` | Requests are being served but no buckets are exported - every response time panel is blank rather than wrong. |

The thresholds are the only numbers in this document that are a judgement rather
than a measurement, and they are set above normal behaviour rather than at it.
Tune them in the rules file once the dashboard has shown what this service's p99
does under real load.

Note what is *not* alerted on: response time never marks an endpoint unhealthy.
A slow endpoint is still serving, and [HEALTH.md](HEALTH.md) is about whether it
can serve at all. The two answer different questions and are meant to be read
together - a slow endpoint whose dependencies are all up is a different problem
from a slow endpoint whose database is struggling.

## Configuration

```yaml
monitoring:
  api:
    response-time:
      enabled: true                   # master switch for exemplars and the filter
      min-retention-ms: 0             # 0 = every request replaces its bucket's exemplar
      log-slow-requests: true
      slow-request-threshold-ms: 1000
```

The buckets themselves are configured separately, because they belong to Spring
Boot rather than to this feature:

```yaml
management:
  metrics:
    distribution:
      slo:
        "[http.server.requests]": 25ms,50ms,100ms,200ms,300ms,500ms,750ms,1s,2s,3s,5s,10s
```

Adding a bucket narrows every percentile and lets each scrape name one more
individual request, at the cost of one time series per endpoint. Both files -
`application.yml` and `application-telco_aaa_dev.yml` - need the change.

## Turning it off

`monitoring.api.response-time.enabled: false` removes the exemplars and the slow
request log. The histogram, the percentiles, the heatmap and every alert keep
working; only the individual requests disappear.

Removing the `management.metrics.distribution.slo` block goes much further: it
takes the buckets, and with them the percentiles, the heatmap and the exemplars
in one go. `UserProvisioningResponseTimeHistogramMissing` exists to catch exactly
that being done by accident.

## Nothing is showing up

In the order worth checking, because each step rules out everything below it:

1. **No `http_server_requests_seconds_bucket` at all.** The `slo` block is
   missing from the profile that is actually running. Check the active profile,
   not just `application.yml`.
2. **Buckets present, no `# {` suffix on any line.** Either
   `monitoring.api.response-time.enabled` is false, or the request never went
   through the filter. Confirm with the `curl` above - and note the
   `Accept` header matters, since `text/plain` never shows exemplars.
3. **Exemplars in the scrape, none in Grafana.** Prometheus is not storing them:
   `--enable-feature=exemplar-storage`. Check Status -> Runtime Information in
   the Prometheus UI for the enabled features.
4. **Exemplars stored, none on the panel.** The panel's query has `exemplar`
   turned off, or the time range is wider than Prometheus's exemplar retention,
   which is much shorter than its metric retention.
5. **Exemplars show but the id leads nowhere.** The caller sent its own `UUID`
   header and it was sanitised - characters outside `[A-Za-z0-9._:-]` are
   stripped before the value reaches the exposition, since a quote or a newline
   there would corrupt the scrape. Compare with the `requestId` in the logs.
