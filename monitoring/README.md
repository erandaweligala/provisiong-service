# REST endpoint availability monitoring

Availability of the REST endpoints of `airtel-aaa-user-provisioning-service`,
from the service's own Micrometer metrics through Prometheus into Grafana.

```
Spring MVC request
  -> ApiNameObservationConvention   adds the `api` tag
  -> /actuator/prometheus           scraped by the ServiceMonitor
  -> Grafana dashboard + PrometheusRule alerts
```

**Availability is the share of responses that were not `5xx`.** 4xx counts as
available on purpose: rejecting a malformed request is the endpoint doing its
job. If you want validation failures on the dashboard, watch the *Response
status mix* panel instead of moving them into the error rate.

## What is in here

| File | Purpose |
| --- | --- |
| `grafana/user-provisioning-endpoint-availability.json` | The dashboard. Import into Grafana. |
| `prometheus/servicemonitor.yaml` | Makes the Prometheus Operator scrape the pods. |
| `prometheus/user-provisioning-endpoint-rules.yaml` | Availability recording rule and alerts. |

## Metrics published

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `http_server_requests_seconds_count` | counter | `api`, `method`, `uri`, `status`, `outcome`, `microservice` | Every served request. The `api` label is what everything groups by. |
| `api_endpoint_info` | gauge | `api`, `method`, `title`, `microservice` | Constant 1, one per catalogued endpoint. This is the catalog: it exists from startup, before any traffic. |

Both come from stock Spring Boot metrics plus one extra tag - there is no custom
request timing, no interceptor and no background thread in this solution.

The `api` label comes from `monitoring.api.endpoints[].name` in
`application.yml`. Traffic to a path that is not in the catalog is tagged
`api="other"`, so the label can never blow up Prometheus cardinality no matter
what gets requested.

## Setting it up

**1. Confirm the service is exporting.** On any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep api_endpoint_info
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

| Alert | Fires when |
| --- | --- |
| `UserProvisioningNoMetrics` | Nothing is publishing `api_endpoint_info` for 5m - every pod is down, or the scrape is broken. |
| `UserProvisioningEndpointErrorRateHigh` | An endpoint's availability is under 99% for 5m. |
| `UserProvisioningEndpointErrorRateCritical` | An endpoint's availability is under 95% for 2m. |

**4. Dashboard.** Grafana -> Dashboards -> New -> Import -> upload
`grafana/user-provisioning-endpoint-availability.json`, then pick the Prometheus
data source. Its UID is a dashboard variable, so the JSON is not tied to any one
Grafana instance.

## An endpoint with no traffic

Request metrics only exist once somebody makes a request, so an endpoint that
has had no calls since the last restart shows up on the dashboard - because
`api_endpoint_info` lists it - but with no availability figure. **That is not
the same as healthy.** Availability here is measured from real traffic; it
cannot tell a broken endpoint from an idle one on a quiet night.

## Changing the catalog

Endpoints are configured, not hard coded. To add one, add an entry under
`monitoring.api.endpoints` in `src/main/resources/application.yml` (and in
`application-telco_aaa_dev.yml`, which carries the same catalog):

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
`ApiMonitoringCatalogTest` fails the build if an entry names a request mapping
no controller declares, so drift is caught at build time rather than showing up
as an endpoint that silently reports zero traffic forever.

No dashboard change is needed: the panels are driven by the `api` label and the
endpoint variable is populated from the metrics themselves.

## Turning it off

`monitoring.api.enabled: false` switches the whole configuration off: the `api`
tag, the `api_endpoint_info` catalog gauge and the `microservice` common tag all
stop being published. `http_server_requests` keeps working with its stock Spring
Boot tags, but the dashboard and the alert rules - which group by `api` and
filter on `microservice` - will go empty.
