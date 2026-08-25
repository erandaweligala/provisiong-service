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
package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import java.util.List;

/**
 * Works out which dependency a failure came from, by the packages its exception chain
 * passes through.
 *
 * <p>This is what lets the global exception handler feed live traffic failures into
 * connectivity monitoring without every call site having to say which dependency it
 * was talking to. Call sites that do know say so explicitly - see
 * {@link ConnectivityMonitoringService#recordFailure(Dependency, Throwable)}.</p>
 *
 * <p>Matching is by package prefix rather than by type so that no vendor class has to
 * be imported, and Redis and Kafka are checked before the generic Spring data-access
 * packages: {@code RedisSystemException} is a {@code DataAccessException} too, and it
 * is not the database that is broken.</p>
 */
final class DependencyResolver {

    /**
     * Package prefix -> dependency, in match order. An ordered list rather than a map:
     * Redis and Kafka have to be tried before the generic Spring data-access packages.
     */
    private static final List<PackageRule> BY_PACKAGE_PREFIX = List.of(
            new PackageRule("org.springframework.data.redis", Dependency.REDIS),
            new PackageRule("io.lettuce", Dependency.REDIS),
            new PackageRule("redis.clients", Dependency.REDIS),
            new PackageRule("org.apache.kafka", Dependency.KAFKA),
            new PackageRule("org.springframework.kafka", Dependency.KAFKA),
            new PackageRule("org.springframework.dao", Dependency.DATABASE),
            new PackageRule("org.springframework.jdbc", Dependency.DATABASE),
            new PackageRule("org.springframework.orm", Dependency.DATABASE),
            new PackageRule("org.springframework.transaction", Dependency.DATABASE),
            new PackageRule("org.hibernate", Dependency.DATABASE),
            new PackageRule("jakarta.persistence", Dependency.DATABASE),
            new PackageRule("java.sql", Dependency.DATABASE),
            new PackageRule("javax.sql", Dependency.DATABASE),
            new PackageRule("oracle.", Dependency.DATABASE),
            new PackageRule("com.zaxxer.hikari", Dependency.DATABASE));

    /** Hard cap on cause-chain walking, so a self-referencing chain cannot spin. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private DependencyResolver() {
        // Utility class
    }

    /**
     * @param throwable the failure to attribute
     * @return the dependency the failure came from, or {@code null} when nothing in the
     * chain points at one - which is the normal case for a business rule failing
     */
    static Dependency resolve(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Dependency dependency = resolveOne(current);
            if (dependency != null) {
                return dependency;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }

    private static Dependency resolveOne(Throwable throwable) {
        String className = throwable.getClass().getName();
        for (PackageRule rule : BY_PACKAGE_PREFIX) {
            if (className.startsWith(rule.prefix())) {
                return rule.dependency();
            }
        }
        return null;
    }

    /** One package prefix and the dependency it belongs to. */
    private record PackageRule(String prefix, Dependency dependency) {}
}
