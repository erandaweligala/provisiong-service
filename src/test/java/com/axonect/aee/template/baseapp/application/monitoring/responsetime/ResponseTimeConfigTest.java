package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import com.axonect.aee.template.baseapp.application.config.ResponseTimeConfig;
import io.prometheus.client.exemplars.ExemplarSampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring, which is where per-request response times are most easily lost.
 *
 * <p>Every assertion here stands for a way the feature can be switched on and
 * still produce nothing: an {@link ExemplarSampler} that never reaches the
 * Prometheus registry, or a filter ordered inside the observation filter that
 * stops the timer, so that the id is gone by the time a duration is recorded.
 * Neither raises an error - the histogram keeps working and only the individual
 * requests go missing, which on a dashboard is indistinguishable from an idle
 * service.</p>
 */
class ResponseTimeConfigTest {

    /**
     * Spring registers {@code ServerHttpObservationFilter} here, and it is what
     * starts and stops {@code http.server.requests}. Anything that needs to be
     * around the measurement has to be ordered ahead of this.
     */
    private static final int OBSERVATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(ResponseTimeConfig.class);

    @Test
    void publishesAnExemplarSamplerAndAFilterByDefault() {
        runner.run(context -> {
            assertTrue(context.getBean(ExemplarSampler.class) instanceof RequestExemplarSampler);
            assertEquals(1, context.getBeansOfType(ExemplarSampler.class).size(),
                    "Boot hands a single ExemplarSampler to the Prometheus registry - a second would be ambiguous");
        });
    }

    /**
     * The one ordering that matters. At {@code HIGHEST_PRECEDENCE} the filter wraps
     * the observation filter, so the request id is still on the thread when the
     * duration is recorded.
     */
    @Test
    void filterIsOrderedOutsideTheObservationFilter() {
        runner.run(context -> {
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
            assertTrue(registration.getOrder() < OBSERVATION_FILTER_ORDER);
            assertTrue(registration.determineDispatcherTypes().contains(DispatcherType.REQUEST));
            assertTrue(registration.determineDispatcherTypes().contains(DispatcherType.ASYNC),
                    "an async endpoint completes on a dispatch thread, which also needs the id");
        });
    }

    @Test
    void bindsItsProperties() {
        runner.withPropertyValues(
                "monitoring.api.response-time.min-retention-ms=7000",
                "monitoring.api.response-time.slow-request-threshold-ms=250",
                "monitoring.api.response-time.log-slow-requests=false"
        ).run(context -> {
            ResponseTimeProperties properties = context.getBean(ResponseTimeProperties.class);
            assertEquals(7_000L, properties.getMinRetentionMs());
            assertEquals(250L, properties.getSlowRequestThresholdMs());
            assertFalse(properties.isLogSlowRequests());
        });
    }

    @Test
    void defaultsFavourNamingEveryRequestItCan() {
        runner.run(context -> {
            ResponseTimeProperties properties = context.getBean(ResponseTimeProperties.class);
            assertEquals(0L, properties.getMinRetentionMs(),
                    "zero means each scrape carries the most recent real request in every bucket");
            assertEquals(1_000L, properties.getSlowRequestThresholdMs());
            assertTrue(properties.isEnabled());
            assertTrue(properties.isLogSlowRequests());
        });
    }

    /**
     * Switching the feature off has to leave no sampler behind: the histogram and
     * its percentiles keep working, only the per-request points go.
     */
    @Test
    void switchesOffCleanly() {
        runner.withPropertyValues("monitoring.api.response-time.enabled=false").run(context -> {
            assertTrue(context.getBeansOfType(ExemplarSampler.class).isEmpty());
            assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty());
        });
    }
}
