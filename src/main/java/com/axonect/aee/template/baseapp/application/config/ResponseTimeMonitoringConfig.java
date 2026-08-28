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
import com.axonect.aee.template.baseapp.application.monitoring.responsetime.ApiResponseTimeRecorder;
import com.axonect.aee.template.baseapp.application.monitoring.responsetime.ResponseTimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires per-request response time recording onto the endpoint catalog. Imported by
 * {@link ApiMonitoringConfig}, so it only comes up when the catalog it groups by
 * exists.
 *
 * <p>Response time is monitored from two directions, and only the second one is
 * configured here. The distribution - percentiles, the heatmap, the slowest
 * request in the window - is read straight off {@code http_server_requests} and its
 * {@code management.metrics.distribution.slo} buckets, which need no bean at all.
 * What needs one is the individual request: {@link ApiResponseTimeRecorder} names
 * the slow ones in the log and counts them per API.</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ResponseTimeProperties.class)
@ConditionalOnProperty(prefix = "monitoring.api.response-time", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ResponseTimeMonitoringConfig {

    @Bean
    public ApiResponseTimeRecorder apiResponseTimeRecorder(
            MeterRegistry meterRegistry,
            ApiEndpointRegistry apiEndpointRegistry,
            ResponseTimeProperties properties,
            @Value("${log.identifierKey:UUID}") String requestIdMdcKey) {

        log.info("Per-request response time recording enabled: slow at {}ms, very slow at {}ms",
                properties.getSlowRequestThresholdMs(), properties.getVerySlowRequestThresholdMs());
        return new ApiResponseTimeRecorder(meterRegistry, apiEndpointRegistry, properties, requestIdMdcKey);
    }

    /**
     * Puts the recorder near the outside of the filter chain, so the duration it
     * measures covers what the caller waited for - authentication, rate limiting and
     * XSS filtering included - rather than only the handler. It sits just behind
     * {@code RequestWrapperFilter}, which has to stay outermost to wrap the request
     * before anything reads it.
     *
     * <p>Registered explicitly because Spring Boot would otherwise pick the filter
     * bean up at the default order, in the middle of the chain, and silently measure
     * less than the name on the metric promises.</p>
     */
    @Bean
    public FilterRegistrationBean<ApiResponseTimeRecorder> apiResponseTimeRecorderRegistration(
            ApiResponseTimeRecorder recorder) {

        FilterRegistrationBean<ApiResponseTimeRecorder> registration = new FilterRegistrationBean<>(recorder);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setName("apiResponseTimeRecorder");
        return registration;
    }
}
