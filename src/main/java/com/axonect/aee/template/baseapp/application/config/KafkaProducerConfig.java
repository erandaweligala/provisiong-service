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
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {


    static final int REPLY_TOPIC_PARTITIONS = 3;

    private static final String REPLY_GROUP_ID = "spring-reply-group-provisioning";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.reply.topic:db-write-events-reply}")
    private String replyTopic;



    @Value("${app.kafka.publish.timeout-ms:10000}")
    private long publishTimeoutMs;

    @Value("${app.kafka.topic.db-write}")
    private String dbWriteTopic;

    private int resolvePodPartition() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            int dashIdx = hostname.lastIndexOf('-');
            if (dashIdx >= 0) {
                try {
                    int ordinal = Integer.parseInt(hostname.substring(dashIdx + 1));
                    return ordinal % REPLY_TOPIC_PARTITIONS;
                } catch (NumberFormatException ignored) {
                    // Deployment random suffix – fall through to hash
                }
            }
            return Math.abs(hostname.hashCode()) % REPLY_TOPIC_PARTITIONS;
        }
        return 0;
    }

    /**
     * Exposed as a Spring bean so {@link KafkaEventPublisher} can inject it
     * and stamp every outgoing {@code REPLY_PARTITION} header.
     */
    @Bean
    public Integer podReplyPartition() {
        return resolvePodPartition();
    }



    // -----------------------------------------------------------------------
    // Producer Factory & Templates
    // -----------------------------------------------------------------------

    @Bean(name = "producerFactory")
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean(name = "kafkaObjectTemplate")
    public KafkaTemplate<String, Object> kafkaObjectTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean(name = "notificationProducerFactory")
    public ProducerFactory<String, String> notificationProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
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
    // Request-Reply infrastructure
    // -----------------------------------------------------------------------

    @Bean
    public ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate(
            @Qualifier("producerFactory") ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, String> replyContainer) {
        ReplyingKafkaTemplate<String, Object, String> template =
                new ReplyingKafkaTemplate<>(pf, replyContainer);
        // Must be >= publishTimeoutMs in KafkaEventPublisher
        template.setDefaultReplyTimeout(Duration.ofMillis(publishTimeoutMs));
        return template;
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, String> replyContainer(
            ConsumerFactory<String, String> cf,
            Integer podReplyPartition) {

        if (cf instanceof DefaultKafkaConsumerFactory) {
            ((DefaultKafkaConsumerFactory<String, String>) cf)
                    .updateConfigs(Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                    ));
        }

        TopicPartitionOffset tpo = new TopicPartitionOffset(replyTopic, podReplyPartition);
        ContainerProperties containerProperties = new ContainerProperties(tpo);
        containerProperties.setGroupId(REPLY_GROUP_ID);

        return new ConcurrentMessageListenerContainer<>(cf, containerProperties);
    }

    // -----------------------------------------------------------------------
    // Topic declarations — all names sourced from application.yml
    // Deduplicates so that topics sharing the same name are only declared once
    // -----------------------------------------------------------------------



    @Bean
    public NewTopic replyTopic() {
        return TopicBuilder.name(replyTopic).partitions(3).replicas(1).build();
    }
}