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
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.ConnectivityMonitoringService;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.Dependency;
import com.axonect.aee.template.baseapp.application.monitoring.health.DependencyHealthSource;
import com.axonect.aee.template.baseapp.application.monitoring.health.EndpointHealthMonitor;
import com.axonect.aee.template.baseapp.application.monitoring.health.EndpointHealthProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Wires per-endpoint health onto the same Prometheus registry the endpoint
 * metrics go to. Imported by {@link ApiMonitoringConfig}, so it only comes up when
 * {@code monitoring.api.enabled} is true and the endpoint catalog exists.
 *
 * <p>Health needs two things the request metrics do not carry: the state of the dependencies
 * each endpoint declares, and the set of paths this instance actually serves. Both
 * are looked up through {@link ObjectProvider}, and both degrade to "assume fine"
 * when unavailable - a health check that cannot run must not manufacture an
 * outage.</p>
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(EndpointHealthProperties.class)
@ConditionalOnProperty(prefix = "monitoring.api.health", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EndpointHealthConfig {

    @Bean
    public EndpointHealthMonitor endpointHealthMonitor(
            MeterRegistry meterRegistry,
            ApiEndpointRegistry apiEndpointRegistry,
            EndpointHealthProperties properties,
            ObjectProvider<ConnectivityMonitoringService> connectivity,
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {

        return new EndpointHealthMonitor(meterRegistry, apiEndpointRegistry, properties,
                dependencyHealthSource(connectivity), liveMappings(handlerMapping));
    }

    /**
     * Reads dependency state from the connectivity probes that already run.
     *
     * <p>A dependency label the connectivity monitor does not track reports up. The
     * alternative - treating an unrecognised label as an outage - would turn a typo
     * in {@code monitoring.api.endpoints[].dependencies} into every endpoint going
     * red, which is a worse failure than the one it would be guarding against. The
     * typo is caught at startup instead, by the warning below.</p>
     */
    private DependencyHealthSource dependencyHealthSource(ObjectProvider<ConnectivityMonitoringService> provider) {
        ConnectivityMonitoringService service = provider.getIfAvailable();
        if (service == null) {
            log.warn("No connectivity monitoring; endpoint health will judge every endpoint from traffic alone");
            return dependency -> true;
        }
        return label -> {
            Dependency dependency = dependencyFor(label);
            return dependency == null || service.isUp(dependency);
        };
    }

    private Dependency dependencyFor(String label) {
        for (Dependency dependency : Dependency.values()) {
            if (dependency.label().equalsIgnoreCase(label)) {
                return dependency;
            }
        }
        log.warn("Endpoint health: unknown dependency '{}' - it is treated as reachable. Expected one of {}",
                label, Dependency.values());
        return null;
    }

    /**
     * Collects every method and path this application serves, in the same
     * {@code "METHOD /normalised/{}/path"} spelling the catalog is normalised to.
     *
     * <p>Supplied lazily: the handler mappings are not populated until the web
     * context has refreshed, which is after this bean is built.</p>
     */
    private Supplier<Set<String>> liveMappings(ObjectProvider<RequestMappingHandlerMapping> provider) {
        return () -> {
            RequestMappingHandlerMapping mapping = provider.getIfUnique();
            if (mapping == null) {
                return Set.of();
            }
            Set<String> mappings = new LinkedHashSet<>();
            mapping.getHandlerMethods().keySet().forEach(info -> collect(info, mappings));
            return mappings;
        };
    }

    private void collect(RequestMappingInfo info, Set<String> mappings) {
        Set<String> patterns = patternsOf(info);
        for (String verb : verbsOf(info.getMethodsCondition())) {
            for (String pattern : patterns) {
                mappings.add(verb + " " + ApiEndpointRegistry.normalizeUri(pattern));
            }
        }
    }

    /**
     * Spring Boot 3 parses paths with {@code PathPatternParser} by default, but a
     * application that has switched back to {@code AntPathMatcher} populates the other
     * condition instead. Both are read so the check does not silently pass by finding
     * nothing.
     */
    private Set<String> patternsOf(RequestMappingInfo info) {
        Set<String> patterns = new LinkedHashSet<>();
        if (info.getPathPatternsCondition() != null) {
            patterns.addAll(info.getPathPatternsCondition().getPatternValues());
        }
        if (info.getPatternsCondition() != null) {
            patterns.addAll(info.getPatternsCondition().getPatterns());
        }
        return patterns;
    }

    /** A mapping that names no method answers all of them, which is how it is recorded. */
    private Set<String> verbsOf(RequestMethodsRequestCondition condition) {
        Set<String> verbs = new LinkedHashSet<>();
        condition.getMethods().forEach(method -> verbs.add(method.name()));
        if (verbs.isEmpty()) {
            for (org.springframework.web.bind.annotation.RequestMethod method
                    : org.springframework.web.bind.annotation.RequestMethod.values()) {
                verbs.add(method.name().toUpperCase(Locale.ROOT));
            }
        }
        return verbs;
    }
}
