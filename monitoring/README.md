# Monitoring

Monitoring for `airtel-aaa-user-provisioning-service`, from the service's own
Micrometer metrics through Prometheus into Grafana.

Three questions are monitored, each with its own document and dashboard:

| Document | Question it answers |
| --- | --- |
| [API-METRICS.md](API-METRICS.md) | How many calls each API served, how many failed, and how long they took. |
| [HEALTH.md](HEALTH.md) | Whether each REST endpoint is fit to serve - including the ones nobody called. |
| [CONNECTIVITY.md](CONNECTIVITY.md) | Whether Oracle, Redis and Kafka are reachable at all. |

Read them in that order when something is wrong. The API metrics say what the
callers saw - the failure count went up, or the responses got slower. Health
says whether the endpoint behind it is fit to take the next request at all,
which is the question traffic alone cannot answer for an API nobody called.
Connectivity says whether the infrastructure underneath is up: when an endpoint
reports `dependency_down`, that is where the cause is.

Health and connectivity ship alerts; the API metrics are a dashboard only, since
a threshold on a request count or a response time would have to be set per API.

This file covers what the three have in common - the endpoint catalog they group
by, and the scrape that carries all of them.

## What is in here

| File | Purpose |
| --- | --- |
| `prometheus/servicemonitor.yaml` | Makes the Prometheus Operator scrape the pods. Shared by everything below. |
| `API-METRICS.md` | Success count, failure count and average response time per API - the doc for the two files below. |
| `grafana/user-provisioning-api-metrics.json` | The API metrics dashboard. Import into Grafana. |
| `prometheus/user-provisioning-api-metrics-queries_test.yaml` | `promtool` unit test for that dashboard's queries. No rules to deploy. |
| `HEALTH.md` | Per-endpoint health monitoring - the doc for the three files below. |
| `grafana/user-provisioning-endpoint-health.json` | The health dashboard. Import into Grafana. |
| `prometheus/user-provisioning-endpoint-health-rules.yaml` | Health alerts (unhealthy, degraded, not mapped, flapping). |
| `prometheus/user-provisioning-endpoint-health-rules_test.yaml` | `promtool` unit test for those rules. |
| `CONNECTIVITY.md` | Dependency connectivity monitoring (Oracle / Redis / Kafka) - the doc for the two files below. |
| `grafana/user-provisioning-dependency-connectivity.json` | The connectivity dashboard. Import into Grafana. |
| `prometheus/user-provisioning-connectivity-rules.yaml` | Connectivity alerts (dependency down, pool exhausted, flapping, slow probes). |

## The endpoint catalog

`ApiMonitoringConfig` reads `monitoring.api.endpoints` from `application.yml` at
startup and turns it into two things:

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `api_endpoint_info` | gauge | `api`, `method`, `title`, `microservice` | Constant 1, one per catalogued endpoint. This is the catalog itself: it exists from startup, before any traffic. |
| `http_server_requests_seconds_*` | timer | `api`, `method`, `uri`, `status`, `outcome`, `microservice` | Spring Boot's own request timer, with the `api` tag added. [API-METRICS.md](API-METRICS.md) reads the counts and the response time off it; health's 5xx check reads the same series. |

The `api` label comes from `monitoring.api.endpoints[].name`. Traffic to a path
that is not in the catalog is tagged `api="other"`, so the label can never blow
up Prometheus cardinality no matter what gets requested.

Every meter also carries a `microservice` common tag, which is what lets the
alert rules stay scoped to this service on a Prometheus shared with the rest of
the AAA stack.

Per-endpoint health publishes a further ten `api_endpoint_*` series from the
same catalog - see [HEALTH.md](HEALTH.md#metrics-published).

## Scraping

All three dashboards read the same `/actuator/prometheus` endpoint, so there is
one scrape to set up. Check the label your Service actually carries, then apply
the ServiceMonitor:

```sh
oc -n airtel-aaa get svc --show-labels
oc -n airtel-aaa apply -f prometheus/servicemonitor.yaml
```

Confirm the target is up in Prometheus under Status -> Targets. A ServiceMonitor
whose selector matches nothing is accepted without complaint and simply never
scrapes, so do not skip this check.

To confirm the service is exporting at all, on any pod:

```sh
curl -s localhost:8089/actuator/prometheus | grep api_endpoint_info
```

Nine lines should come back, one per endpoint in the catalog. If it is empty,
nothing downstream will work - check `monitoring.api.enabled` first.

From here, [API-METRICS.md](API-METRICS.md#setting-it-up),
[HEALTH.md](HEALTH.md#setting-it-up) and [CONNECTIVITY.md](CONNECTIVITY.md) each
cover their own dashboard, and the last two their alerts.

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

A `dependencies` list goes on each entry as well; it is what per-endpoint health
checks, and [HEALTH.md](HEALTH.md#dependencies-are-declared-per-endpoint) covers
how to choose it.

`uris` must match the controller's request mapping structure exactly - path
variable *names* are ignored, but the path shape is not. A handler mapped to
several paths lists all of them, and they all report as one endpoint.
`ApiMonitoringCatalogTest` scans the controllers and fails the build if an entry
stops matching a real request mapping, so a silent drift cannot reach
production.

No dashboard change is needed, on any of the three: the panels are driven by the
`api` label and the endpoint variable is populated from the catalog.

## Turning it off

`monitoring.api.enabled: false` drops the `api` tag, the `microservice` common
tag and `api_endpoint_info` - and takes per-endpoint health and the API metrics
dashboard with it, since both read the same catalog. To turn off health alone,
use `monitoring.api.health.enabled: false`. `http_server_requests` keeps working
with its stock Spring Boot tags either way, so response times and status counts
are still queryable in Prometheus - just not per API.
