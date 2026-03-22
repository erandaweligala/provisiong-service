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

    /**
     * Must match the partition count declared on the reply topic.
     * Each pod is pinned to (podOrdinal % REPLY_TOPIC_PARTITIONS) so replies
     * are always routed back to the pod that produced the request.
     */
    static final int REPLY_TOPIC_PARTITIONS = 3;

    /** Shared consumer-group for the reply listener across all pods. */
    private static final String REPLY_GROUP_ID = "spring-reply-group-provisioning";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.reply.topic:db-write-events-reply}")
    private String replyTopic;



    @Value("${app.kafka.topic.db-write}")
    private String dbWriteTopic;

    /**
     * Derive a stable partition index for this pod.
     *
     * <ul>
     *   <li>StatefulSet pods  – HOSTNAME = {@code <name>-<ordinal>}
     *       (e.g. {@code provisioning-service-0}) → use the numeric ordinal.</li>
     *   <li>Deployment pods   – HOSTNAME is random; fall back to a positive
     *       hash so the assignment is at least deterministic within a single
     *       pod's lifetime (good enough because in-flight futures are in-memory
     *       anyway).  Prefer StatefulSets for full restart-safety.</li>
     * </ul>
     */
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
        // Hard ceiling — must be >= publishTimeoutMs in KafkaEventPublisher
        template.setDefaultReplyTimeout(Duration.ofMillis(2500));
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
                            // Re-read unconsumed replies after a pod restart instead of
                            // skipping them.  Safe because each pod owns a dedicated
                            // partition and the ReplyingKafkaTemplate discards any
                            // message whose correlation-id it does not recognise.
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                    ));
        }

        // Pin this pod to its own partition so:
        //   (a) only this pod ever receives replies it produced, and
        //   (b) Kafka commits the offset against a stable group+partition pair,
        //       allowing crash-recovery without skipping messages.
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