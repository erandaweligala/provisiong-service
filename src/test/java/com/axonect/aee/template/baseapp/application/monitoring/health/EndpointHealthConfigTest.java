package com.axonect.aee.template.baseapp.application.monitoring.health;

import com.axonect.aee.template.baseapp.application.config.ApiMonitoringConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring, not the logic: that the health beans come up with the catalog, bind
 * their properties, and switch off cleanly.
 *
 * <p>Worth a test of its own because every one of these is a silent failure. A
 * conditional that never matches, a property prefix that does not bind, an
 * {@code @Import} that is dropped - none of them break anything visibly, they
 * just leave the dashboard empty, which reads exactly like a healthy service that
 * is not being called.</p>
 */
class EndpointHealthConfigTest {

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RegistryConfig.class, ApiMonitoringConfig.class)
            .withPropertyValues(
                    "monitoring.api.enabled=true",
                    "monitoring.api.microservice=airtel-aaa-user-provisioning-service",
                    "monitoring.api.endpoints[0].name=get_user",
                    "monitoring.api.endpoints[0].title=Get user",
                    "monitoring.api.endpoints[0].method=GET",
                    "monitoring.api.endpoints[0].uris[0]=/api/user/{user_name}",
                    "monitoring.api.endpoints[0].dependencies[0]=database");

    @Test
    void theHealthMonitorComesUpWithTheEndpointCatalog() {
        runner.run(context -> {
            assertNotNull(context.getBean(EndpointHealthMonitor.class));
            assertNotNull(context.getBean(EndpointHealthProperties.class));
        });
    }

    /** Without this the whole feature is silently absent. */
    @Test
    void everyEndpointGaugeIsRegisteredAsSoonAsTheContextIsUp() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertNotNull(registry.find("api.endpoint.health").tag("api", "get_user").gauge());
            assertNotNull(registry.find("api.endpoint.mapped").tag("api", "get_user").gauge());
            assertNotNull(registry.find("api.endpoint.error.ratio").tag("api", "get_user").gauge());
            assertNotNull(registry.find("api.endpoint.dependency.required")
                    .tag("api", "get_user").tag("dependency", "database").gauge());
            assertEquals(EndpointHealthReason.values().length,
                    registry.find("api.endpoint.health.reason").tag("api", "get_user").gauges().size(),
                    "every reason must be a continuous series, not one that appears when it becomes active");
        });
    }

    @Test
    void thresholdsBindFromTheHealthPrefix() {
        runner.withPropertyValues(
                        "monitoring.api.health.window-ms=60000",
                        "monitoring.api.health.degraded-error-ratio=0.05",
                        "monitoring.api.health.unhealthy-error-ratio=0.25",
                        "monitoring.api.health.minimum-requests=100",
                        "monitoring.api.health.use-dependency-state=false",
                        "monitoring.api.health.verify-mappings=false")
                .run(context -> {
                    EndpointHealthProperties properties = context.getBean(EndpointHealthProperties.class);

                    assertEquals(60_000, properties.getWindowMs());
                    assertEquals(0.05, properties.getDegradedErrorRatio());
                    assertEquals(0.25, properties.getUnhealthyErrorRatio());
                    assertEquals(100, properties.getMinimumRequests());
                    assertEquals(false, properties.isUseDependencyState());
                    assertEquals(false, properties.isVerifyMappings());
                });
    }

    @Test
    void theDefaultsAreTheOnesTheDocumentationQuotes() {
        runner.run(context -> {
            EndpointHealthProperties properties = context.getBean(EndpointHealthProperties.class);

            assertTrue(properties.isEnabled());
            assertEquals(15_000, properties.getEvaluationIntervalMs());
            assertEquals(300_000, properties.getWindowMs());
            assertEquals(0.01, properties.getDegradedErrorRatio());
            assertEquals(0.10, properties.getUnhealthyErrorRatio());
            assertEquals(20, properties.getMinimumRequests());
            assertTrue(properties.isUseDependencyState());
            assertTrue(properties.isVerifyMappings());
        });
    }

    @Test
    void healthCanBeTurnedOffOnItsOwn() {
        runner.withPropertyValues("monitoring.api.health.enabled=false").run(context -> {
            assertTrue(context.getBeansOfType(EndpointHealthMonitor.class).isEmpty());
            // availability monitoring carries on
            assertNotNull(context.getBean(com.axonect.aee.template.baseapp.application.monitoring
                    .ApiEndpointRegistry.class));
        });
    }

    /** Health reads the same catalog, so it cannot outlive it. */
    @Test
    void turningOffApiMonitoringTakesHealthWithIt() {
        runner.withPropertyValues("monitoring.api.enabled=false").run(context -> {
            assertTrue(context.getBeansOfType(EndpointHealthMonitor.class).isEmpty());
            assertTrue(context.getBeansOfType(com.axonect.aee.template.baseapp.application.monitoring
                    .ApiEndpointRegistry.class).isEmpty());
        });
    }

    /**
     * No connectivity monitoring and no handler mappings in this context - both are
     * absent, and the monitor still has to reach a verdict rather than fail or
     * report everything down.
     */
    @Test
    void missingCollaboratorsDegradeToAssumeFine() {
        runner.run(context -> {
            EndpointHealthMonitor monitor = context.getBean(EndpointHealthMonitor.class);
            monitor.evaluate();
            monitor.evaluate();

            EndpointHealthStatus status = monitor.snapshot().get("get_user");
            assertEquals("healthy", status.health());
            assertEquals("idle", status.reason());
            assertTrue(status.mapped());
        });
    }
}
