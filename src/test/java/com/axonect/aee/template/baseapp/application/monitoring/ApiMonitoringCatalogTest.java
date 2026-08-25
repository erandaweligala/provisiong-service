package com.axonect.aee.template.baseapp.application.monitoring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the endpoint catalog in {@code application.yml} against drift.
 *
 * <p>A catalog entry whose {@code uris} no longer match any controller stops
 * matching traffic silently: the endpoint just quietly reports zero requests
 * forever, which on an availability dashboard looks like a quiet endpoint rather
 * than a broken one. This test fails the build instead.</p>
 */
class ApiMonitoringCatalogTest {

    private static final String CONTROLLER_PACKAGE = "com.axonect.aee.template.baseapp.application.controller";

    private static ApiMonitoringProperties properties;
    private static Set<String> controllerMappings;

    @BeforeAll
    static void loadConfiguration() throws IOException {
        properties = bindFrom("application.yml");
        controllerMappings = scanControllerMappings();
    }

    private static ApiMonitoringProperties bindFrom(String resource) throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), null,
                ApplicationConversionService.getSharedInstance())
                .bind("monitoring.api", ApiMonitoringProperties.class)
                .orElseThrow(() -> new AssertionError("monitoring.api is missing from " + resource));
    }

    /**
     * @return every request mapping the controllers declare, as
     * {@code "METHOD /normalised/{}/path"}.
     */
    private static Set<String> scanControllerMappings() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> mappings = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException ex) {
                throw new AssertionError(ex);
            }
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            Set<String> typePaths = pathsOf(typeMapping);

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                RequestMethod[] verbs = methodMapping.method();
                if (verbs.length == 0) {
                    verbs = RequestMethod.values();
                }
                for (String typePath : typePaths) {
                    for (String methodPath : pathsOf(methodMapping)) {
                        String full = ApiEndpointRegistry.normalizeUri(combine(typePath, methodPath));
                        for (RequestMethod verb : verbs) {
                            mappings.add(verb.name() + " " + full);
                        }
                    }
                }
            }
        }
        return mappings;
    }

    private static Set<String> pathsOf(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return new LinkedHashSet<>(List.of(""));
        }
        return new LinkedHashSet<>(List.of(mapping.path()));
    }

    private static String combine(String typePath, String methodPath) {
        String left = typePath.endsWith("/") ? typePath.substring(0, typePath.length() - 1) : typePath;
        if (methodPath.isEmpty()) {
            return left.isEmpty() ? "/" : left;
        }
        String right = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return left + right;
    }

    @Test
    void catalogCoversTheEndpointsOnTheApiSheet() {
        assertEquals(List.of(
                        "create_user", "update_user", "delete_user", "activate_user", "update_service",
                        "delete_service", "get_user", "get_users", "query_user_information"),
                properties.getEndpoints().stream().map(MonitoredEndpoint::getName).toList());
    }

    @Test
    void everyCataloguedUriIsServedByAController() {
        List<String> missing = properties.getEndpoints().stream()
                .flatMap(endpoint -> endpoint.getUris().stream()
                        .map(uri -> endpoint.getMethod().toUpperCase() + " " + ApiEndpointRegistry.normalizeUri(uri))
                        .filter(key -> !controllerMappings.contains(key))
                        .map(key -> endpoint.getName() + " -> " + key))
                .toList();

        assertTrue(missing.isEmpty(),
                "monitoring.api.endpoints refers to request mappings that no controller declares: " + missing
                        + "\nControllers actually declare:\n  "
                        + String.join("\n  ", controllerMappings));
    }

    @Test
    void everyEndpointIsUsable() {
        for (MonitoredEndpoint endpoint : properties.getEndpoints()) {
            assertNotNull(endpoint.getTitle(), endpoint.getName() + " has no title for the dashboard");
            assertNotNull(endpoint.getMethod(), endpoint.getName() + " has no HTTP method");
            assertFalse(endpoint.getUris().isEmpty(), endpoint.getName() + " has no uris");
            assertTrue(endpoint.getName().matches("[a-z0-9_]+"),
                    endpoint.getName() + " is not a usable Prometheus label value");
        }
    }

    @Test
    void endpointNamesAreUnique() {
        List<String> names = properties.getEndpoints().stream().map(MonitoredEndpoint::getName).toList();

        assertEquals(names.size(), Set.copyOf(names).size(), "duplicate endpoint names: " + names);
    }

    @Test
    void noStateChangingEndpointIsProbed() {
        List<String> unsafe = properties.getEndpoints().stream()
                .filter(endpoint -> endpoint.getProbePath() != null && !endpoint.getProbePath().isBlank())
                .filter(endpoint -> !"GET".equalsIgnoreCase(endpoint.getMethod()))
                .map(MonitoredEndpoint::getName)
                .toList();

        assertTrue(unsafe.isEmpty(), "synthetic probes must never call state changing endpoints: " + unsafe);
    }

    @Test
    void thresholdsMatchTheApiSheet() {
        assertEquals(2000L, properties.getDefaultThresholdMs());

        ApiEndpointRegistry registry =
                new ApiEndpointRegistry(properties.getEndpoints(), properties.getDefaultThresholdMs());
        Set<Long> thresholds = properties.getEndpoints().stream()
                .map(registry::thresholdMsFor)
                .collect(Collectors.toSet());

        assertEquals(Set.of(2000L), thresholds);
    }

    @Test
    void deploymentProfileCarriesTheSameCatalog() throws IOException {
        ApiMonitoringProperties deployed = bindFrom("application-telco_aaa_dev.yml");

        assertEquals(properties.getEndpoints().stream().map(MonitoredEndpoint::getName).toList(),
                deployed.getEndpoints().stream().map(MonitoredEndpoint::getName).toList());
        assertEquals(properties.getMicroservice(), deployed.getMicroservice());
    }

    @Test
    void probeIsOffByDefault() {
        assertFalse(properties.getProbe().isEnabled(),
                "the synthetic probe issues real HTTP calls and must not ship enabled");
    }
}
