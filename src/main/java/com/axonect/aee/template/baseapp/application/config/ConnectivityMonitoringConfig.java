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

import com.axonect.aee.template.baseapp.application.monitoring.connectivity.ConnectivityMonitoringProperties;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.ConnectivityMonitoringService;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.DatabaseConnectivityProbe;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.DependencyProbe;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.KafkaConnectivityProbe;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.RedisConnectivityProbe;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires dependency connectivity monitoring - the Oracle database, Redis and Kafka -
 * onto the Prometheus registry Grafana scrapes from {@code /actuator/prometheus}.
 *
 * <p>The probes are assembled here rather than declared as conditional beans of their
 * own: the {@link DataSource}, {@link RedisConnectionFactory} and {@link KafkaAdmin}
 * they need all come from auto-configuration, which is processed after this class, so
 * they are looked up lazily through {@link ObjectProvider} instead. A dependency whose
 * client is missing is simply not probed, and says so in the log at startup.</p>
 *
 * <p>Every probe the application defines as a {@link DependencyProbe} bean is picked up
 * as well, so a new dependency can be added without touching this class.</p>
 *
 * <p>{@link ConnectivityMonitoringService} is always registered, even with
 * {@code connectivity.enabled=false}: that switch turns off the active probes, while
 * failures seen by live traffic keep being classified and counted.</p>
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ConnectivityMonitoringProperties.class)
public class ConnectivityMonitoringConfig {

    @Bean
    public ConnectivityMonitoringService connectivityMonitoringService(
            MeterRegistry meterRegistry,
            ConnectivityMonitoringProperties properties,
            ObjectProvider<DataSource> dataSource,
            ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
            ObjectProvider<KafkaAdmin> kafkaAdmin,
            ObjectProvider<DependencyProbe> additionalProbes,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {

        List<DependencyProbe> probes = new ArrayList<>();

        if (properties.isProbeDatabase()) {
            DataSource source = dataSource.getIfUnique();
            if (source == null) {
                log.warn("Database connectivity probe disabled: no unique DataSource bean");
            } else {
                probes.add(new DatabaseConnectivityProbe(
                        source, properties.getDatabaseProbeQuery(), properties.getProbeTimeoutMs()));
            }
        }

        if (properties.isProbeRedis()) {
            RedisConnectionFactory factory = redisConnectionFactory.getIfUnique();
            if (factory == null) {
                log.warn("Redis connectivity probe disabled: no unique RedisConnectionFactory bean");
            } else {
                probes.add(new RedisConnectivityProbe(factory));
            }
        }

        if (properties.isProbeKafka()) {
            probes.add(new KafkaConnectivityProbe(
                    kafkaAdminConfig(kafkaAdmin.getIfAvailable(), bootstrapServers), properties.getProbeTimeoutMs()));
        }

        additionalProbes.orderedStream().forEach(probes::add);
        return new ConnectivityMonitoringService(meterRegistry, properties, probes);
    }

    /**
     * The AdminClient settings for the Kafka probe. Taken from {@link KafkaAdmin} when
     * it is available so the probe carries the same bootstrap servers and security
     * settings as the producers, and therefore fails exactly when they would.
     */
    private Map<String, Object> kafkaAdminConfig(KafkaAdmin kafkaAdmin, String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        if (kafkaAdmin == null) {
            log.info("No KafkaAdmin bean; Kafka connectivity probe falls back to spring.kafka.bootstrap-servers");
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        } else {
            config.putAll(kafkaAdmin.getConfigurationProperties());
            config.putIfAbsent(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        }
        return config;
    }
}
