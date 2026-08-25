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
 * One round trip against a dependency, used to detect outages while no traffic is
 * flowing and recoveries without waiting for a real request to succeed.
 *
 * <p>Implementations do the cheapest call that proves the connection works and throw
 * on anything else. They do not need to enforce the probe timeout or touch metrics:
 * {@link ConnectivityMonitoringService} runs every probe on its own thread with a
 * deadline and records the outcome.</p>
 */
public interface DependencyProbe {

    /** The dependency this probe checks. */
    Dependency dependency();

    /**
     * Performs the round trip.
     *
     * @throws Exception if the dependency could not be reached or answered with an error
     */
    void probe() throws Exception;
}
