# REST endpoint availability monitoring

Availability, error rate and response time for the REST endpoints of
`airtel-aaa-user-provisioning-service`, from the service's own Micrometer
metrics through Prometheus into Grafana.

```
Spring MVC request
  -> ApiNameObservationConvention   adds the `api` tag
  -> ApiLatencyThresholdInterceptor counts responses over the endpoint's budget
  -> /actuator/prometheus           scraped by the ServiceMonitor
  -> Grafana dashboard + PrometheusRule alerts
```

## What is in here

| File | Purpose |
| --- | --- |
| `grafana/user-provisioning-endpoint-availability.json` | The full dashboard: availability, error rate, latency vs threshold, status mix, synthetic probe. |
| `grafana/endpoint-availability-simple.json` | Just "is it up": one overall availability number and one line per endpoint. Import this instead if the full dashboard is more than you need. |
| `prometheus/servicemonitor.yaml` | Makes the Prometheus Operator scrape the pods. |
| `prometheus/user-provisioning-endpoint-rules.yaml` | Recording rules and alerts. |

## Metrics published

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `http_server_requests_seconds_*` | histogram | `api`, `method`, `uri`, `status`, `outcome`, `microservice` | Every served request. The `api` label is what everything groups by. |
| `api_requests_over_threshold_total` | counter | `api`, `method`, `microservice` | Responses slower than that endpoint's own threshold. |
| `api_endpoint_threshold_milliseconds` | gauge | `api`, `method`, `title`, `microservice` | The configured threshold. Also the catalog: it exists for every endpoint from startup, before any traffic. |
| `api_endpoint_up` | gauge | `api`, `title`, `microservice` | Synthetic probe result, 1 or 0. Only when the probe is enabled. |
| `api_endpoint_probe_duration_seconds_*` | histogram | `api`, `microservice` | Synthetic probe round trip time. |
| `api_endpoint_probe_failures_total` | counter | `api`, `reason`, `microservice` | Synthetic probes that did not get the expected status. |

The `api` label comes from `monitoring.api.endpoints[].name` in
`application.yml`. Traffic to a path that is not in the catalog is tagged
`api="other"`, so the label can never blow up Prometheus cardinality no matter
what gets requested.

## Setting it up

**1. Confirm the service is exporting.** On any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep api_endpoint_threshold
```

Nine lines should come back, one per endpoint in the catalog. If that is empty,
nothing downstream will work - check `monitoring.api.enabled` first.

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

**4. Dashboard.** Grafana -> Dashboards -> New -> Import -> upload
`grafana/user-provisioning-endpoint-availability.json` (the full dashboard) or
`grafana/endpoint-availability-simple.json` (just the availability number, if
that is all you need), then pick the Prometheus data source. Its UID is a
dashboard variable, so neither JSON is tied to any one Grafana instance.

## Reading the dashboard

- **Availability** is the share of responses that were not `5xx`. 4xx is counted
  as available on purpose: rejecting a malformed request is the endpoint doing
  its job. If you want validation failures on the dashboard, watch the
  *Response status mix* panel instead of moving them into the error rate.
- **Within threshold** is the share of requests that answered inside that
  endpoint's own `threshold-ms`. This is the "Response Time Threshold" column of
  the API sheet, at 2000 ms for every endpoint today.
- **Latency vs response time threshold** draws each endpoint's budget as a
  dashed red line straight from `api_endpoint_threshold_milliseconds`, so it
  follows `application.yml` and cannot drift out of date.
- **An endpoint with no traffic shows a threshold and nothing else.** That is
  not the same as healthy - request metrics only exist once somebody makes a
  request. Turn on the synthetic probe if you need the difference.

## Changing the catalog

Endpoints are configured, not hard coded. To add one, add an entry under
`monitoring.api.endpoints` in `src/main/resources/application.yml`:

```yaml
      - name: get_bng            # becomes the `api` label - never change it later
        title: Get BNG           # shown on the dashboard
        method: GET
        uris:
          - /api/bng/{bng_id}    # the Spring URI template, path variable names ignored
        threshold-ms: 1000       # optional, defaults to default-threshold-ms
```

`uris` must match the controller's request mapping structure exactly - path
variable *names* are ignored, but the path shape is not. A handler mapped to
several paths lists all of them, and they all report as one endpoint.

No dashboard change is needed: the panels are driven by the `api` label and the
endpoint variable is populated from the metrics themselves.

## Synthetic probe (optional, off by default)

Request metrics only exist while requests are arriving. On a quiet night a
broken endpoint and an idle one look identical. The probe closes that gap by
calling endpoints on a schedule and publishing `api_endpoint_up`.

**It issues real HTTP calls, so only side effect free reads may be probed.** An
endpoint takes part only if it declares a `probe-path`, and the probe always
sends `GET` - a catalog entry for a POST, PATCH or DELETE with a `probe-path`
is refused at startup and logged as an error rather than being called. Never
give `create_user`, `update_user`, `delete_user`, `activate_user`,
`update_service` or `delete_service` a probe path.

To enable it, point it at a dedicated read-only identity that exists in the
environment:

```yaml
monitoring:
  api:
    probe:
      enabled: true
      base-url: http://localhost:8089    # the pod's own listener, so each replica reports itself
      interval: 60s
      timeout: 5s
    endpoints:
      - name: get_user
        # ... existing fields ...
        probe-path: /api/user/AAA-SYNTHETIC-MONITOR
        probe-expected-statuses: [ 200, 404 ]
```

`probe-expected-statuses` defaults to `[200]`. Listing `404` as acceptable
turns the check into "the endpoint answered correctly" rather than "the test
user exists", which is usually what you want from an availability probe.

Probe traffic hits the service's own endpoints and is therefore counted in
`http_server_requests` like any other request. Keep the interval well above the
scrape interval so it does not distort the traffic figures on a low volume
endpoint.

## Turning it all off

`monitoring.api.enabled: false` removes the `api` tag and the threshold
interceptor. `http_server_requests` keeps working with its stock Spring Boot
tags; the dashboard, which groups by `api`, will go empty.
