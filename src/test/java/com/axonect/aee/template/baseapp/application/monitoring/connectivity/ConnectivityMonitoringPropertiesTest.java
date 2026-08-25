package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code connectivity} block in the two configuration files against drift.
 *
 * <p>Connectivity monitoring is invisible when it is misconfigured - a service that
 * ships with the probes switched off, or with a different {@code service-name} in the
 * deployed profile, publishes metrics that look healthy because nothing is measuring.
 * These assertions fail the build instead.</p>
 */
class ConnectivityMonitoringPropertiesTest {

    private static final String SERVICE_NAME = "airtel-aaa-user-provisioning-service";

    private static ConnectivityMonitoringProperties bindFrom(String resource) throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), null,
                ApplicationConversionService.getSharedInstance())
                .bind("connectivity", ConnectivityMonitoringProperties.class)
                .orElseThrow(() -> new AssertionError("connectivity is missing from " + resource));
    }

    @Test
    void localConfigurationProbesEveryDependency() throws IOException {
        ConnectivityMonitoringProperties properties = bindFrom("application.yml");

        assertEquals(SERVICE_NAME, properties.getServiceName());
        assertTrue(properties.isEnabled());
        assertTrue(properties.isProbeDatabase());
        assertTrue(properties.isProbeRedis());
        assertTrue(properties.isProbeKafka());
        assertEquals(3, properties.getFailureThreshold());
        assertEquals(15000, properties.getProbeIntervalMs());
        assertEquals(2000, properties.getProbeTimeoutMs());
    }

    @Test
    void deploymentProfileCarriesTheSameSettings() throws IOException {
        ConnectivityMonitoringProperties local = bindFrom("application.yml");
        ConnectivityMonitoringProperties deployed = bindFrom("application-telco_aaa_dev.yml");

        assertTrue(deployed.isEnabled(), "connectivity monitoring must not ship switched off");
        assertEquals(local.getServiceName(), deployed.getServiceName(),
                "the service tag has to match, or the deployed pods land on their own series");
        assertEquals(local.getFailureThreshold(), deployed.getFailureThreshold());
        assertEquals(local.getProbeIntervalMs(), deployed.getProbeIntervalMs());
        assertEquals(local.getProbeTimeoutMs(), deployed.getProbeTimeoutMs());
        assertEquals(local.isProbeDatabase(), deployed.isProbeDatabase());
        assertEquals(local.isProbeRedis(), deployed.isProbeRedis());
        assertEquals(local.isProbeKafka(), deployed.isProbeKafka());
    }

    @Test
    void probeBudgetFitsInsideTheProbeInterval() throws IOException {
        ConnectivityMonitoringProperties properties = bindFrom("application.yml");

        // Probes are collected against their own deadline, so a budget at or beyond the
        // interval would mean rounds that never finish before the next one is due.
        assertTrue(properties.getProbeTimeoutMs() < properties.getProbeIntervalMs(),
                "probe-timeout-ms must be well under probe-interval-ms");
    }

    @Test
    void defaultsMatchTheShippedConfiguration() throws IOException {
        // The defaults are what a pod falls back to if the block is ever dropped from a
        // profile, so they must not say something different from the block itself.
        ConnectivityMonitoringProperties defaults = new ConnectivityMonitoringProperties();
        ConnectivityMonitoringProperties configured = bindFrom("application.yml");

        assertEquals(configured.getServiceName(), defaults.getServiceName());
        assertEquals(configured.getFailureThreshold(), defaults.getFailureThreshold());
        assertEquals(configured.getProbeIntervalMs(), defaults.getProbeIntervalMs());
        assertEquals(configured.getProbeTimeoutMs(), defaults.getProbeTimeoutMs());
    }
}
