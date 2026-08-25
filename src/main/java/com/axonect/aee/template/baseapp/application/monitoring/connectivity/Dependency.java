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

/**
 * The infrastructure dependencies whose reachability is tracked.
 *
 * <p>The label is what appears as the {@code dependency} Prometheus tag, and it is
 * deliberately the same set of labels the DB write service publishes, so one Grafana
 * dashboard can chart either service by switching the {@code service} tag.</p>
 */
public enum Dependency {

    /** The Oracle database behind the JPA repositories and the Hikari pool. */
    DATABASE("database"),

    /** Redis, used for rate limiting, service TTLs and caching. */
    REDIS("redis"),

    /** The Kafka cluster this service publishes provisioning events to. */
    KAFKA("kafka");

    private final String label;

    Dependency(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
