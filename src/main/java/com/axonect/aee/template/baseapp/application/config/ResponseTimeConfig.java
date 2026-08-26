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

import com.axonect.aee.template.baseapp.application.monitoring.responsetime.RequestExemplarSampler;
import com.axonect.aee.template.baseapp.application.monitoring.responsetime.ResponseTimeFilter;
import com.axonect.aee.template.baseapp.application.monitoring.responsetime.ResponseTimeProperties;
import io.prometheus.client.exemplars.ExemplarSampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;

/**
 * Turns the response time histogram into a record of individual requests.
 *
 * <p>The histogram itself needs no wiring - {@code http.server.requests} is
 * Spring Boot's own timer, and the {@code management.metrics.distribution.slo}
 * block in {@code application.yml} is what gives it the buckets that
 * {@code http_server_requests_seconds_bucket} is made of. Percentiles read off
 * those buckets, and so does the heatmap; but a percentile is a property of a
 * population, and the question here is what one request actually took.</p>
 *
 * <p>An exemplar is the only part of the Prometheus exposition that carries a
 * single observed value rather than an aggregate, so that is what this class
 * arranges. Registering an {@link ExemplarSampler} bean is enough: Spring Boot's
 * {@code PrometheusMetricsExportAutoConfiguration} takes whatever sampler it
 * finds and hands it to the {@code PrometheusMeterRegistry}, which attaches an
 * exemplar to each histogram bucket as requests land in it.</p>
 *
 * <p>Two conditions outside this service have to hold for the exemplars to
 * arrive - Prometheus must be started with {@code --enable-feature=exemplar-storage}
 * and must scrape in OpenMetrics format, which it does by default once that
 * feature is on. {@code monitoring/RESPONSE-TIME.md} covers both.</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ResponseTimeProperties.class)
@ConditionalOnProperty(prefix = "monitoring.api.response-time", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResponseTimeConfig {

    /**
     * The header carrying a caller-supplied correlation id - the same one
     * {@code RequestUuidInterceptor} reads into the logging MDC, so the exemplar
     * and the logs agree on what a request is called.
     */
    @Value("${log.identifierKey:UUID}")
    private String requestIdHeader;

    /**
     * Labels each histogram bucket with a real request that landed in it.
     *
     * <p>Declared as the single {@link ExemplarSampler} bean. Boot would otherwise
     * build a {@code DefaultExemplarSampler} of its own, but only if a
     * {@code SpanContextSupplier} bean exists - which needs Micrometer Tracing and
     * a tracing backend this service does not run. Supplying the sampler directly
     * skips that dependency, and lets the retention interval be a choice rather
     * than the 7s constant baked into the default.</p>
     */
    @Bean
    public ExemplarSampler requestExemplarSampler(ResponseTimeProperties properties) {
        log.info("Per-request response time exemplars enabled: minRetentionMs={}", properties.getMinRetentionMs());
        return new RequestExemplarSampler(properties.getMinRetentionMs());
    }

    /**
     * Registers {@link ResponseTimeFilter} outside Spring's observation filter.
     *
     * <p>{@code ServerHttpObservationFilter} - the filter that starts and stops the
     * request timer - is registered at {@code HIGHEST_PRECEDENCE + 1}, so
     * {@code HIGHEST_PRECEDENCE} is what puts this one around it. Anything later
     * would run inside the timer and be gone before the duration is recorded,
     * leaving every exemplar unlabelled.</p>
     *
     * <p>{@code ASYNC} is included for the same reason it is on the observation
     * filter: an async endpoint completes on a dispatch thread, and the id has to
     * be on that thread too.</p>
     */
    @Bean
    public FilterRegistrationBean<ResponseTimeFilter> responseTimeFilter(ResponseTimeProperties properties) {
        ResponseTimeFilter filter = new ResponseTimeFilter(requestIdHeader,
                properties.isLogSlowRequests(), properties.getSlowRequestThresholdMs());
        FilterRegistrationBean<ResponseTimeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
