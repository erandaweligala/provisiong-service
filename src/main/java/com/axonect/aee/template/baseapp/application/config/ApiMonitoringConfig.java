/**
 * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 * <p>
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 * <p>
 * ADL retains all title to and intellectual property rights in these
 * materials.
 */
package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.ApiLatencyThresholdInterceptor;
import com.axonect.aee.template.baseapp.application.monitoring.ApiMonitoringProperties;
import com.axonect.aee.template.baseapp.application.monitoring.ApiNameObservationConvention;
import com.axonect.aee.template.baseapp.application.monitoring.EndpointAvailabilityProbe;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Wires REST endpoint availability monitoring onto the Prometheus registry that
 * Grafana scrapes from {@code /actuator/prometheus}.
 *
 * <p>Three things are published for every endpoint in the
 * {@code monitoring.api.endpoints} catalog:</p>
 * <ul>
 *   <li>{@code http_server_requests_seconds_*} carrying an extra {@code api}
 *       tag, which is where availability (non-5xx share) and latency come
 *       from;</li>
 *   <li>{@code api_requests_over_threshold_total}, the numerator of the
 *       response time SLO;</li>
 *   <li>{@code api_endpoint_threshold_milliseconds}, the budget itself, so the
 *       dashboard can draw it without hard coding a number - and so every
 *       catalogued endpoint shows up in Prometheus even before it takes its
 *       first request.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ApiMonitoringProperties.class)
public class ApiMonitoringConfig {

    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";
    private static final String THRESHOLD_GAUGE = "api.endpoint.threshold.milliseconds";

    @Bean
    public ApiEndpointRegistry apiEndpointRegistry(ApiMonitoringProperties properties) {
        log.info("REST availability monitoring enabled={}, microservice={}, default threshold={}ms",
                properties.isEnabled(), properties.getMicroservice(), properties.getDefaultThresholdMs());
        return new ApiEndpointRegistry(properties.getEndpoints(), properties.getDefaultThresholdMs());
    }

    /**
     * Tags every meter with the microservice name so a Prometheus instance shared
     * across the AAA stack can tell this service's series apart.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> apiMonitoringCommonTags(ApiMonitoringProperties properties) {
        return registry -> registry.config().commonTags("microservice", properties.getMicroservice());
    }

    /**
     * Adds the {@code api} tag to {@code http_server_requests}.
     *
     * <p>Declaring a {@link ServerRequestObservationConvention} bean replaces the
     * default one Spring Boot would otherwise use; the convention extends that
     * default rather than reimplementing it, so all the standard tags stay.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "monitoring.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ServerRequestObservationConvention apiNameObservationConvention(ApiEndpointRegistry registry) {
        return new ApiNameObservationConvention(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "monitoring.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ApiLatencyThresholdInterceptor apiLatencyThresholdInterceptor(ApiEndpointRegistry registry,
                                                                         MeterRegistry meterRegistry) {
        return new ApiLatencyThresholdInterceptor(registry, meterRegistry);
    }

    /**
     * Active availability checks. Off unless {@code monitoring.api.probe.enabled}
     * is explicitly turned on, because the probe issues real HTTP calls.
     */
    @Bean
    @ConditionalOnProperty(prefix = "monitoring.api.probe", name = "enabled", havingValue = "true")
    public EndpointAvailabilityProbe endpointAvailabilityProbe(ApiMonitoringProperties properties,
                                                               ApiEndpointRegistry registry,
                                                               MeterRegistry meterRegistry) {
        return new EndpointAvailabilityProbe(properties, registry, meterRegistry);
    }

    /**
     * Gives {@code http_server_requests} the latency buckets the dashboard needs.
     *
     * <p>Each endpoint's own response time threshold is added to its buckets on
     * top of the shared list, so a {@code histogram_quantile} panel and the SLO
     * boundary always line up exactly.</p>
     */
    @Bean
    public MeterFilter apiLatencyHistogramFilter(ApiMonitoringProperties properties,
                                                 ApiEndpointRegistry registry) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(properties.isPercentilesHistogram())
                        .serviceLevelObjectives(sloNanosFor(id, properties, registry))
                        .minimumExpectedValue((double) TimeUnit.MILLISECONDS.toNanos(5))
                        .maximumExpectedValue((double) TimeUnit.SECONDS.toNanos(30))
                        .build()
                        .merge(config);
            }
        };
    }

    /**
     * Publishes each endpoint's response time budget as a gauge.
     */
    @Bean
    public MeterBinder apiEndpointThresholdGauges(ApiEndpointRegistry registry) {
        return meterRegistry -> {
            for (MonitoredEndpoint endpoint : registry.endpoints()) {
                Gauge.builder(THRESHOLD_GAUGE, endpoint, e -> registry.thresholdMsFor(e))
                        .description("Configured response time threshold of the endpoint, in milliseconds")
                        .tag("api", endpoint.getName())
                        .tag("method", endpoint.getMethod() == null ? "UNKNOWN" : endpoint.getMethod())
                        .tag("title", endpoint.getTitle() == null ? endpoint.getName() : endpoint.getTitle())
                        .register(meterRegistry);
            }
        };
    }

    /**
     * @return the shared latency buckets plus, when the series belongs to a
     * catalogued endpoint, that endpoint's own threshold - all in nanoseconds,
     * which is the unit Micrometer expects for timer SLOs.
     */
    private static double[] sloNanosFor(Meter.Id id,
                                        ApiMonitoringProperties properties,
                                        ApiEndpointRegistry registry) {
        Set<Long> bucketsMs = new LinkedHashSet<>(properties.getLatencyBucketsMs());
        registry.findByName(id.getTag("api"))
                .map(registry::thresholdMsFor)
                .ifPresent(bucketsMs::add);
        return bucketsMs.stream()
                .sorted()
                .mapToDouble(ms -> (double) TimeUnit.MILLISECONDS.toNanos(ms))
                .toArray();
    }
}
