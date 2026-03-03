package com.axonect.aee.template.baseapp.application.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    private static final String DC_PROVISIONING = "dc-provisioning";

    @Value("${spring.kafka.bootstrap-servers.dc}")
    private String bootstrapServersDC;

    @Value("${spring.kafka.bootstrap-servers.dr:${spring.kafka.bootstrap-servers.dc}}")
    private String bootstrapServersDR;

    @Value("${kafka.reply.topic:db-write-events-reply}")
    private String replyTopic;

    @Value("${app.kafka.cluster.active:dc}")
    private String activeCluster;

    // -----------------------------------------------------------------------
    // DC Cluster Producer
    // -----------------------------------------------------------------------

    @Bean(name = "dcProducerFactory")
    public ProducerFactory<String, Object> dcProducerFactory() {
        return createProducerFactory(bootstrapServersDC);
    }

    @Bean(name = "dcKafkaTemplate")
    public KafkaTemplate<String, Object> dcKafkaTemplate() {
        return new KafkaTemplate<>(dcProducerFactory());
    }

    // -----------------------------------------------------------------------
    // DR Cluster Producer
    // -----------------------------------------------------------------------

    @Bean(name = "drProducerFactory")
    public ProducerFactory<String, Object> drProducerFactory() {
        return createProducerFactory(bootstrapServersDR);
    }

    @Bean(name = "drKafkaTemplate")
    public KafkaTemplate<String, Object> drKafkaTemplate() {
        return new KafkaTemplate<>(drProducerFactory());
    }

    // -----------------------------------------------------------------------
    // Shared producer factory builder — with fail-fast timeouts (from config 1)
    // -----------------------------------------------------------------------

    private ProducerFactory<String, Object> createProducerFactory(String bootstrapServers) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        // Fail fast when broker is unreachable (config 1 values preserved)
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);         // metadata fetch timeout
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);   // per-request timeout
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);  // total send timeout (must be > REQUEST_TIMEOUT_MS)

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    // -----------------------------------------------------------------------
    // Notification producer (String-typed) — config 1: no fail-fast timeouts
    // -----------------------------------------------------------------------

    @Bean(name = "notificationProducerFactory")
    public ProducerFactory<String, String> notificationProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "dr".equalsIgnoreCase(activeCluster) ? bootstrapServersDR : bootstrapServersDC);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean(name = "kafkaTemplate")
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(notificationProducerFactory());
    }

    // -----------------------------------------------------------------------
    // Request-Reply infrastructure — config 1: replyTimeout = 3s
    // -----------------------------------------------------------------------


    @Bean
    public ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate(
            @Qualifier("dcProducerFactory") ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, String> replyContainer) {
        ReplyingKafkaTemplate<String, Object, String> template =
                new ReplyingKafkaTemplate<>(pf, replyContainer);
        // Hard ceiling — must be >= publishTimeoutMs in KafkaEventPublisher (config 1)
        template.setDefaultReplyTimeout(Duration.ofSeconds(3));
        return template;
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, String> replyContainer(
            ConsumerFactory<String, String> cf) {

        // Force the consumer factory to use your actual cluster address instead of localhost
        if (cf instanceof DefaultKafkaConsumerFactory) {
            ((DefaultKafkaConsumerFactory<String, String>) cf)
                    .updateConfigs(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersDC));
        }

        ContainerProperties containerProperties = new ContainerProperties(replyTopic);

        // Each pod MUST have its own unique consumer group for the reply topic.
        // With a shared group, Kafka distributes reply-topic partitions across pods,
        // so a reply may land on a pod that did NOT send the request, causing timeouts.
        // Using HOSTNAME (unique per Kubernetes pod) ensures every pod independently
        // consumes ALL reply messages; the ReplyingKafkaTemplate filters by correlationId.
        String podId = System.getenv("HOSTNAME");
        if (podId == null || podId.isBlank()) {
            podId = java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        containerProperties.setGroupId("spring-reply-group-provisioning-" + podId);

        return new ConcurrentMessageListenerContainer<>(cf, containerProperties);
    }

    // -----------------------------------------------------------------------
    // Topic declarations (from config 2 — kept exactly as-is)
    // -----------------------------------------------------------------------

    @Bean
    public NewTopic userEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dbWriteDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic userEventsDRTopic() {
        return TopicBuilder.name("user-events-dr").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dbWriteDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic serviceEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic serviceEventsDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bucketEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bucketEventsDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bngEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bngEventsDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic actionLogEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic actionLogEventsDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic vendorConfigEventsDCTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic vendorConfigEventsDRTopic() {
        return TopicBuilder.name(DC_PROVISIONING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic replyTopic() {
        return TopicBuilder.name(replyTopic).partitions(3).replicas(1).build();
    }
}