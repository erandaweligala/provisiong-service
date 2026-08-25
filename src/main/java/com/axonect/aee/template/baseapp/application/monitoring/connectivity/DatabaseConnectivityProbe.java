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

import lombok.RequiredArgsConstructor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Oracle probe: borrows a connection from the Hikari pool and runs the configured
 * probe query, by default {@code SELECT 1 FROM DUAL}.
 *
 * <p>Going through the pool is deliberate. A pool that cannot hand out a connection
 * is an outage from this service's point of view even when the database itself is
 * healthy, and that is exactly what the {@code pool_exhausted} reason is for.</p>
 */
@RequiredArgsConstructor
public class DatabaseConnectivityProbe implements DependencyProbe {

    private static final int MILLIS_PER_SECOND = 1000;

    private final DataSource dataSource;
    private final String probeQuery;
    private final long probeTimeoutMs;

    @Override
    public Dependency dependency() {
        return Dependency.DATABASE;
    }

    @Override
    public void probe() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // Second line of defence behind the probe deadline: the JDBC driver gives up
            // on its own rather than leaving a worker thread parked on a dead socket.
            statement.setQueryTimeout(queryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(probeQuery)) {
                resultSet.next();
            }
        }
    }

    /** JDBC query timeouts are whole seconds, so a sub-second budget rounds up to 1. */
    private int queryTimeoutSeconds() {
        return (int) Math.max(1, (probeTimeoutMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
    }
}
