package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.domain.events.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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

    @Autowired
    private ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate;

    @Value("${app.kafka.cluster.active:dc}")
    private String activeCluster;

    @Value("${app.kafka.publish-to-both:true}")
    private boolean publishToBoth;

    // ── Timeout / retry knobs (all driven from application.yml) ─────────────
    @Value("${app.kafka.publish.timeout-ms:2000}")
    private long publishTimeoutMs;

    @Value("${app.kafka.publish.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${app.kafka.publish.retry.max-attempts:2}")
    private int maxRetryAttempts;
    // ────────────────────────────────────────────────────────────────────────

    // Topic names
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

    public boolean publishWithBusinessAck(String topic,
                                          String cluster,
                                          String key,
                                          Object payload,
                                          String eventType) {
        int attempt = 0;

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);

                RequestReplyFuture<String, Object, String> future =
                        replyingKafkaTemplate.sendAndReceive(record, Duration.ofMillis(publishTimeoutMs));

                // Broker ACK
                SendResult<String, Object> sendResult =
                        future.getSendFuture().get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                log.debug("[{}] Broker ACK received for {} event (key: '{}') – Partition: {}, Offset: {}",
                        cluster, eventType, key,
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset());

                // Consumer business-level reply
                ConsumerRecord<String, String> reply = future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                String result = reply.value();

                if ("SUCCESS".equalsIgnoreCase(result)) {
                    log.info("[{}] Consumer confirmed success for {} event (key: '{}')",
                            cluster, eventType, key);
                    return true;

                } else {
                    // Strip the "FAIL: " prefix if present for a cleaner message
                    String errorMessage = (result != null && result.toUpperCase().startsWith("FAIL:"))
                            ? result.substring(5).trim()
                            : result;

                    log.warn("[{}] Consumer reported failure: '{}' for {} event (key: '{}')",
                            cluster, result, eventType, key);

                    // Throw immediately — business NACK should not be retried
                    throw new ConsumerReplyException(
                            String.format("[%s] Consumer NACK for %s event (key: '%s'): %s",
                                    cluster, eventType, key, errorMessage)
                    );
                }

            } catch (ConsumerReplyException e) {
                // Business-level rejection from consumer — propagate immediately, no retry
                throw e;

            } catch (Exception e) {
                log.error("[{}] Attempt {}/{} failed for {} event (key: '{}'): {}",
                        cluster, attempt, maxRetryAttempts, eventType, key, e.getMessage());

                if (!retryEnabled || attempt >= maxRetryAttempts) {
                    log.error("[{}] Max retry attempts reached for {} event (key: '{}') – giving up",
                            cluster, eventType, key);

                    throw new RuntimeException(
                            String.format("Something went wrong",
                                    cluster, maxRetryAttempts, eventType, key), e
                    );
                }

                try {
                    long backoffMs = (long) Math.pow(2, attempt) * 100; // 200ms, 400ms
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.error("[{}] FINAL NACK for {} event (key: '{}') after {} attempts",
                cluster, eventType, key, attempt);
        return false;
    }

    // -----------------------------------------------------------------------
    // Public publish methods
    // -----------------------------------------------------------------------

    public PublishResult publishUserEvent(String eventType, UserEvent userEvent) {
        String key = userEvent.getUserName();
        boolean success = publishWithBusinessAck(USER_EVENTS_DC, "DC", key, userEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        String key = dbWriteEvent.getUserName();
        boolean success = publishWithBusinessAck(DB_WRITE_DC, "DC", key, dbWriteEvent,
                dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishServiceEvent(String eventType, ServiceEvent serviceEvent) {
        String key = serviceEvent.getUsername();
        boolean success = publishWithBusinessAck(SERVICE_EVENTS_DC, "DC", key, serviceEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBucketEvent(String eventType, BucketEvent bucketEvent, String username) {
        boolean success = publishWithBusinessAck(BUCKET_EVENTS_DC, "DC", username, bucketEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishSuperTemplateEvent(String eventType, SuperTemplateEvent event) {
        String key = String.valueOf(event.getSuperTemplateId());
        boolean success = publishWithBusinessAck(SUPER_TEMPLATE_EVENTS_DC, "DC", key, event, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishChildTemplateEvent(String eventType, ChildTemplateEvent event, String userName) {
        String key = String.valueOf(event.getChildTemplateId());
        boolean success = publishWithBusinessAck(CHILD_TEMPLATE_EVENTS_DC, "DC", key, event, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBngEvent(String eventType, BngEvent bngEvent) {
        String key = bngEvent.getBngId();
        boolean success = publishWithBusinessAck(BNG_EVENTS_DC, "DC", key, bngEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishBngDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        String key = dbWriteEvent.getUserName();
        boolean success = publishWithBusinessAck(DB_WRITE_DC, "DC", key, dbWriteEvent,
                dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishActionLogEvent(String eventType, ActionLogEvent actionLogEvent) {
        String key = actionLogEvent.getRequestId() != null
                ? actionLogEvent.getRequestId()
                : String.valueOf(actionLogEvent.getId());
        boolean success = publishWithBusinessAck(ACTION_LOG_EVENTS_DC, "DC", key, actionLogEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishActionLogDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        String key = dbWriteEvent.getUserName();
        boolean success = publishWithBusinessAck(DB_WRITE_DC, "DC", key, dbWriteEvent,
                dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishVendorConfigEvent(String eventType, VendorConfigEvent vendorConfigEvent) {
        String key = vendorConfigEvent.getVendorId() + "-" + vendorConfigEvent.getAttributeId();
        boolean success = publishWithBusinessAck(VENDOR_CONFIG_EVENTS_DC, "DC", key, vendorConfigEvent, eventType);
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    public PublishResult publishVendorConfigDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        String key = dbWriteEvent.getUserName();
        boolean success = publishWithBusinessAck(DB_WRITE_DC, "DC", key, dbWriteEvent,
                dbWriteEvent.getEventType());
        return PublishResult.builder().dcSuccess(success).drSuccess(false).build();
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    public String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
    }
}