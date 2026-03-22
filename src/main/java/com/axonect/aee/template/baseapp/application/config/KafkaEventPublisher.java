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
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private static final String SUCCESS_REPLY     = "SUCCESS";
    private static final String FAIL_PREFIX       = "FAIL:";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";

    @Qualifier("kafkaObjectTemplate")
    private final KafkaTemplate<String, Object> kafkaObjectTemplate;

    private final ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate;

    // ── Timeout / retry knobs ────────────────────────────────────────────────
    @Value("${app.kafka.publish.timeout-ms:2000}")
    private long publishTimeoutMs;

    @Value("${app.kafka.publish.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${app.kafka.publish.retry.max-attempts:2}")
    private int maxRetryAttempts;

    // ── Topic names ──────────────────────────────────────────────────────────
    @Value("${app.kafka.topic.db-write}")
    private String dbWriteTopic;

    /**
     * The reply-topic partition this pod exclusively listens on.
     * Injected from the {@code podReplyPartition} bean so that the
     * {@code REPLY_PARTITION} header stamped on every outgoing request
     * always matches the partition the reply container is consuming.
     */
    @Autowired
    @Qualifier("podReplyPartition")
    private int podReplyPartition;

    // -----------------------------------------------------------------------
    // Core business ACK method
    // -----------------------------------------------------------------------

    public boolean publishWithBusinessAck(String topic, String key, Object payload, String eventType) {
        int attempt = 0;

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                return sendAndAwaitReply(topic, key, payload, eventType);
            } catch (ConsumerReplyException e) {
                throw e;
            } catch (Exception e) {
                handleRetryOrThrow(e, attempt, eventType, key);
            }
        }

        log.error("FINAL NACK for {} event (key: '{}') after {} attempts", eventType, key, attempt);
        return false;
    }

    private boolean sendAndAwaitReply(String topic, String key, Object payload, String eventType) throws Exception {
        ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topic, key, payload);

        // Tell the downstream service which reply-topic partition to use.
        // This guarantees the reply is always delivered to the same pod that
        // sent the request, regardless of how many replicas are running.
        producerRecord.headers().add(new RecordHeader(
                KafkaHeaders.REPLY_PARTITION,
                ByteBuffer.allocate(4).putInt(podReplyPartition).array()));

        RequestReplyFuture<String, Object, String> future =
                replyingKafkaTemplate.sendAndReceive(producerRecord, Duration.ofMillis(publishTimeoutMs));

        logBrokerAck(future, eventType, key);

        ConsumerRecord<String, String> reply = future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        return evaluateConsumerReply(reply.value(), eventType, key);
    }

    private void logBrokerAck(RequestReplyFuture<String, Object, String> future,
                              String eventType, String key) throws Exception {
        SendResult<String, Object> sendResult = future.getSendFuture().get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        log.debug("Broker ACK received for {} event (key: '{}') – Partition: {}, Offset: {}",
                eventType, key,
                sendResult.getRecordMetadata().partition(),
                sendResult.getRecordMetadata().offset());
    }

    private boolean evaluateConsumerReply(String result, String eventType, String key) {
        if (SUCCESS_REPLY.equalsIgnoreCase(result)) {
            log.info("Consumer confirmed success for {} event (key: '{}')", eventType, key);
            return true;
        }

        String errorMessage = extractErrorMessage(result);
        log.warn("Consumer reported failure: '{}' for {} event (key: '{}')", result, eventType, key);
        throw new ConsumerReplyException(
                String.format("Consumer NACK for %s event (key: '%s'): %s", eventType, key, errorMessage));
    }

    private String extractErrorMessage(String result) {
        if (result != null && result.toUpperCase().startsWith(FAIL_PREFIX)) {
            return result.substring(FAIL_PREFIX.length()).trim();
        }
        return result;
    }

    private void handleRetryOrThrow(Exception e, int attempt, String eventType, String key) {
        log.error("Attempt {}/{} failed for {} event (key: '{}'): {}",
                attempt, maxRetryAttempts, eventType, key, e.getMessage());

        if (!retryEnabled || attempt >= maxRetryAttempts) {
            log.error("Max retry attempts reached for {} event (key: '{}') – giving up", eventType, key);
            throw new KafkaException(
                    String.format("Publish failed for %s event (key: '%s') after %d attempts", eventType, key, attempt), e);
        }

        applyBackoff(attempt);
    }

    private void applyBackoff(int attempt) {
        try {
            long backoffMs = (long) Math.pow(2, attempt) * 100L;
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Public publish methods — all delegate to shared private method
    // -----------------------------------------------------------------------

    public PublishResult publishDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        return publishGenericDBWriteEvent(dbWriteEvent);
    }

    public PublishResult publishBngDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        return publishGenericDBWriteEvent(dbWriteEvent);
    }

    public PublishResult publishActionLogDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        return publishGenericDBWriteEvent(dbWriteEvent);
    }

    public PublishResult publishVendorConfigDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        return publishGenericDBWriteEvent(dbWriteEvent);
    }

    private PublishResult publishGenericDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                dbWriteTopic,
                dbWriteEvent.getUserName(),
                dbWriteEvent,
                dbWriteEvent.getEventType()
        );
        return PublishResult.builder().Success(success).build();
    }
    public PublishResult publishCredentialDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        boolean success = publishWithBusinessAck(
                dbWriteTopic,
                dbWriteEvent.getUserName(),
                dbWriteEvent,
                dbWriteEvent.getEventType()
        );
        return PublishResult.builder().Success(success).build();
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    public String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN));
    }
}