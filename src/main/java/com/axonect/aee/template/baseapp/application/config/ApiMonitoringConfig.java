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
import com.axonect.aee.template.baseapp.application.monitoring.ApiMonitoringProperties;
import com.axonect.aee.template.baseapp.application.monitoring.ApiNameObservationConvention;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * Wires REST endpoint availability monitoring onto the Prometheus registry that
 * Grafana scrapes from {@code /actuator/prometheus}.
 *
 * <p>Availability is read straight off Spring Boot's own request metrics: the
 * share of responses that were not {@code 5xx}. All this configuration adds is
 * two things:</p>
 * <ul>
 *   <li>an {@code api} tag on {@code http_server_requests_seconds_count} naming
 *       the catalog entry that served each request - the source of the
 *       availability figure;</li>
 *   <li>{@code api_endpoint_info}, a constant 1 per catalogued endpoint, so an
 *       endpoint that has taken no traffic still appears on the dashboard
 *       instead of vanishing.</li>
 * </ul>
 *
 * <p>Availability alone cannot say whether a quiet endpoint is well - a ratio of
 * no requests is not a health check - so {@link EndpointHealthConfig} is imported
 * here to add one. It is imported rather than scanned so that it comes up only
 * with this class, and after the catalog it reads.</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ApiMonitoringProperties.class)
@ConditionalOnProperty(prefix = "monitoring.api", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(EndpointHealthConfig.class)
public class ApiMonitoringConfig {

    private static final String INFO_GAUGE = "api.endpoint.info";

    @Bean
    public ApiEndpointRegistry apiEndpointRegistry(ApiMonitoringProperties properties) {
        log.info("REST endpoint availability monitoring enabled for microservice={}", properties.getMicroservice());
        return new ApiEndpointRegistry(properties.getEndpoints());
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
    public ServerRequestObservationConvention apiNameObservationConvention(ApiEndpointRegistry registry) {
        return new ApiNameObservationConvention(registry);
    }

    /**
     * Publishes a constant 1 for every catalogued endpoint.
     *
     * <p>Request metrics only come into existence once somebody calls the
     * endpoint, so without this an endpoint that has had no traffic since the
     * last restart is simply missing from Prometheus - and a dashboard panel
     * that divides by its request rate has nothing to divide. This series makes
     * the catalog itself visible, which is what lets every panel fall back to
     * "no traffic, nothing failed, 100% available" instead of drawing a gap.</p>
     */
    @Bean
    public MeterBinder apiEndpointInfoGauges(ApiEndpointRegistry registry) {
        return meterRegistry -> {
            for (MonitoredEndpoint endpoint : registry.endpoints()) {
                Gauge.builder(INFO_GAUGE, () -> 1)
                        .description("Constant 1 for every endpoint in the availability catalog")
                        .tag("api", endpoint.getName())
                        .tag("method", endpoint.getMethod() == null ? "UNKNOWN" : endpoint.getMethod())
                        .tag("title", endpoint.getTitle() == null ? endpoint.getName() : endpoint.getTitle())
                        .register(meterRegistry);
            }
        };
    }
}
