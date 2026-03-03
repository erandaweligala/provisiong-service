package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.domain.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaEventPublisher {

    @Qualifier("dcKafkaTemplate")
    private final KafkaTemplate<String, Object> dcKafkaTemplate;

    /** Injected by Spring — @Autowired because it is not final (no constructor param conflict). */
    @Autowired
    private ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate;

    /** Generates a stable CRC32-based partition key for every outbound record. */
    private final PartitionKeyGenerator partitionKeyGenerator;

    @Value("${app.kafka.cluster.active:dc}")
    private String activeCluster;

    @Value("${app.kafka.publish-to-both:true}")
    private boolean publishToBoth;

    // ── Timeout / retry knobs ────────────────────────────────────────────────
    @Value("${app.kafka.publish.timeout-ms:2000}")
    private long publishTimeoutMs;

    @Value("${app.kafka.publish.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${app.kafka.publish.retry.max-attempts:2}")
    private int maxRetryAttempts;
    // ────────────────────────────────────────────────────────────────────────

    // Topic names — all currently mapped to dc-provisioning
    private static final String USER_EVENTS_DC          = "dc-provisioning";
    private static final String DB_WRITE_DC              = "dc-provisioning";
    private static final String SERVICE_EVENTS_DC        = "dc-provisioning";
    private static final String BUCKET_EVENTS_DC         = "dc-provisioning";
    private static final String SUPER_TEMPLATE_EVENTS_DC = "dc-provisioning";
    private static final String CHILD_TEMPLATE_EVENTS_DC = "dc-provisioning";
    private static final String BNG_EVENTS_DC            = "dc-provisioning";
    private static final String VENDOR_CONFIG_EVENTS_DC  = "dc-provisioning";
    private static final String ACTION_LOG_EVENTS_DC     = "dc-provisioning";

    // -----------------------------------------------------------------------
    // Core business ACK method
    // -----------------------------------------------------------------------

    /**
     * Sends {@code payload} to {@code topic} and waits for a consumer-level
     * SUCCESS / FAILURE reply (request-reply pattern).
     *
     * <p>A stable {@code X-Partition-Key} header is stamped on every record so
     * that downstream consumers and monitoring tools can correlate messages
     * without depending solely on the Kafka message key.
     *
     * @param topic      destination Kafka topic
     * @param cluster    logical cluster label used in log messages only
     * @param key        Kafka message key (used for partitioning by the broker)
     * @param payload    event payload object (serialised by the configured serialiser)
     * @param eventType  human-readable event name, e.g. "USER_CREATED"
     * @return {@code true} if the consumer acknowledged SUCCESS; {@code false} otherwise
     */
    public boolean publishWithBusinessAck(String topic,
                                          String cluster,
                                          String key,
                                          Object payload,
                                          String eventType) {
        // ── Build a stable partition key for this event+entity combination ──
        String partitionKey = partitionKeyGenerator.generate(eventType, key);
        byte[] partitionKeyBytes = partitionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int attempt = 0;

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                // Stamp the partition key as a Kafka record header
                ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
                record.headers().add(
                        new RecordHeader(PartitionKeyGenerator.PARTITION_KEY_HEADER, partitionKeyBytes));

                log.debug("[{}] Publishing {} event – key: '{}', partitionKey: {}",
                        cluster, eventType, key, partitionKey);

                RequestReplyFuture<String, Object, String> future =
                        replyingKafkaTemplate.sendAndReceive(record, Duration.ofMillis(publishTimeoutMs));

                // Broker ACK
                SendResult<String, Object> sendResult =
                        future.getSendFuture().get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                log.debug("[{}] Broker ACK for {} (key: '{}', partitionKey: {}) – partition: {}, offset: {}",
                        cluster, eventType, key, partitionKey,
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset());

                // Consumer business-level reply
                ConsumerRecord<String, String> reply = future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                String result = reply.value();
                if ("SUCCESS".equalsIgnoreCase(result)) {
                    log.info("[{}] Consumer confirmed SUCCESS for {} (key: '{}', partitionKey: {})",
                            cluster, eventType, key, partitionKey);
                    return true;
                } else {
                    log.warn("[{}] Consumer reported FAILURE: '{}' for {} (key: '{}', partitionKey: {}). Retrying...",
                            cluster, result, eventType, key, partitionKey);
                }

            } catch (Exception e) {
                log.error("[{}] Attempt {}/{} failed for {} (key: '{}', partitionKey: {}): {}",
                        cluster, attempt, maxRetryAttempts, eventType, key, partitionKey, e.getMessage());
            }

            if (!retryEnabled || attempt >= maxRetryAttempts) {
                break;
            }

            try {
                long backoffMs = (long) Math.pow(2, attempt) * 100; // 200 ms, 400 ms
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.error("[{}] FINAL NACK for {} (key: '{}', partitionKey: {}) after {} attempt(s)",
                cluster, eventType, key, partitionKey, attempt);
        return false;
    }

    // -----------------------------------------------------------------------
    // Public publish methods
    // -----------------------------------------------------------------------

    public PublishResult publishUserEvent(String eventType, UserEvent userEvent) {
        boolean success = publishWithBusinessAck(
                USER_EVENTS_DC, "DC", userEvent.getUserName(), userEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                DB_WRITE_DC, "DC", dbWriteEvent.getUserName(), dbWriteEvent, dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishServiceEvent(String eventType, ServiceEvent serviceEvent) {
        boolean success = publishWithBusinessAck(
                SERVICE_EVENTS_DC, "DC", serviceEvent.getUsername(), serviceEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBucketEvent(String eventType, BucketEvent bucketEvent, String username) {
        boolean success = publishWithBusinessAck(
                BUCKET_EVENTS_DC, "DC", username, bucketEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishSuperTemplateEvent(String eventType, SuperTemplateEvent event) {
        String key = String.valueOf(event.getSuperTemplateId());
        boolean success = publishWithBusinessAck(
                SUPER_TEMPLATE_EVENTS_DC, "DC", key, event, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishChildTemplateEvent(String eventType, ChildTemplateEvent event, String userName) {
        String key = String.valueOf(event.getChildTemplateId());
        boolean success = publishWithBusinessAck(
                CHILD_TEMPLATE_EVENTS_DC, "DC", key, event, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBngEvent(String eventType, BngEvent bngEvent) {
        boolean success = publishWithBusinessAck(
                BNG_EVENTS_DC, "DC", bngEvent.getBngId(), bngEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBngDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                DB_WRITE_DC, "DC", dbWriteEvent.getUserName(), dbWriteEvent, dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishActionLogEvent(String eventType, ActionLogEvent actionLogEvent) {
        String key = actionLogEvent.getRequestId() != null
                ? actionLogEvent.getRequestId()
                : String.valueOf(actionLogEvent.getId());
        boolean success = publishWithBusinessAck(
                ACTION_LOG_EVENTS_DC, "DC", key, actionLogEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishActionLogDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                DB_WRITE_DC, "DC", dbWriteEvent.getUserName(), dbWriteEvent, dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishVendorConfigEvent(String eventType, VendorConfigEvent vendorConfigEvent) {
        String key = vendorConfigEvent.getVendorId() + "-" + vendorConfigEvent.getAttributeId();
        boolean success = publishWithBusinessAck(
                VENDOR_CONFIG_EVENTS_DC, "DC", key, vendorConfigEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishVendorConfigDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                DB_WRITE_DC, "DC", dbWriteEvent.getUserName(), dbWriteEvent, dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    public String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
    }
}