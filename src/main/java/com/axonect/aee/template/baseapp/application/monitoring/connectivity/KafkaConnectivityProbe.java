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

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kafka probe: {@code describeCluster} through an AdminClient.
 *
 * <p>The client is built from whatever the application itself is configured with
 * (bootstrap servers, and any security settings that come with them) so the probe
 * fails exactly when the producers would. It is created on the first probe rather
 * than at startup, so a broker outage during boot cannot hold the service back, and
 * it is kept for the life of the pod - one connection, not one per probe.</p>
 */
@Slf4j
public class KafkaConnectivityProbe implements DependencyProbe, AutoCloseable {

    private static final String PROBE_CLIENT_ID = "user-provisioning-connectivity-probe";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, Object> adminConfig;
    private final int probeTimeoutMs;

    private volatile AdminClient adminClient;

    public KafkaConnectivityProbe(Map<String, Object> adminConfig, long probeTimeoutMs) {
        this.probeTimeoutMs = (int) probeTimeoutMs;
        Map<String, Object> config = new HashMap<>(adminConfig);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, PROBE_CLIENT_ID);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, this.probeTimeoutMs);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, this.probeTimeoutMs * 2);
        config.put(AdminClientConfig.RETRIES_CONFIG, 0);
        this.adminConfig = Map.copyOf(config);
    }

    @Override
    public Dependency dependency() {
        return Dependency.KAFKA;
    }

    @Override
    public void probe() throws Exception {
        adminClient()
                .describeCluster(new DescribeClusterOptions().timeoutMs(probeTimeoutMs))
                .nodes()
                .get(probeTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private AdminClient adminClient() {
        AdminClient client = adminClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (adminClient == null) {
                adminClient = AdminClient.create(new HashMap<>(adminConfig));
                log.info("Kafka connectivity probe client created for bootstrap servers: {}",
                        adminConfig.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
            }
            return adminClient;
        }
    }

    @Override
    public void close() {
        AdminClient client = adminClient;
        if (client == null) {
            return;
        }
        try {
            client.close(CLOSE_TIMEOUT);
        } catch (Exception e) {
            log.warn("Failed to close Kafka connectivity probe client: {}", e.getMessage());
        }
    }
}
