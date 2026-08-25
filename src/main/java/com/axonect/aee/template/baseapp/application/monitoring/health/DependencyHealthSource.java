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
package com.axonect.aee.template.baseapp.application.monitoring.health;

/**
 * Where endpoint health reads dependency state from.
 *
 * <p>In the running application this is
 * {@code ConnectivityMonitoringService::isUp} - the probes that already run every
 * 15 seconds against Oracle, Redis and Kafka. Endpoint health does not probe
 * anything itself: it reuses that answer, which is why an idle endpoint can be
 * given a verdict at no extra cost to the dependencies.</p>
 *
 * <p>Declared as an interface rather than taking the service directly so the
 * evaluation schedule can be tested without a database, a Redis and a Kafka.</p>
 */
@FunctionalInterface
public interface DependencyHealthSource {

    /**
     * @param dependency the {@code dependency} label, e.g. {@code database}
     * @return true when it is reachable, or when nothing is tracking it - an unknown
     * dependency must not be reported as an outage
     */
    boolean isUp(String dependency);
}
