package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.constants.LoggingAdviceConstants;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.ConnectivityMonitoringService;
import com.axonect.aee.template.baseapp.application.monitoring.connectivity.Dependency;
import com.axonect.aee.template.baseapp.domain.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
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

    private static final String SUCCESS_REPLY     = "SUCCESS";
    private static final String FAIL_PREFIX       = "FAIL:";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";

    @Qualifier("kafkaObjectTemplate")
    private final KafkaTemplate<String, Object> kafkaObjectTemplate;

    private final ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate;

    /**
     * Publishing is the one place this service talks to Kafka on the request path, so
     * the outcome of every publish is reported to connectivity monitoring: a completed
     * round trip proves the cluster is reachable, and a failed one is classified and
     * counted towards marking Kafka down.
     */
    private final ConnectivityMonitoringService connectivityMonitoringService;

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

    // -----------------------------------------------------------------------
    // Core business ACK method
    // -----------------------------------------------------------------------

    public boolean publishWithBusinessAck(String topic, String key, Object payload, String eventType) {
        int attempt = 0;
        long publishStart = System.currentTimeMillis();

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                return sendAndAwaitReply(topic, key, payload, eventType);
            } catch (ConsumerReplyException e) {
                throw e;
            } catch (Exception e) {
                handleRetryOrThrow(e, attempt, eventType, key, System.currentTimeMillis() - publishStart);
            }
        }

        log.error(LoggingAdviceConstants.UP_KAFKA, System.currentTimeMillis() - publishStart, "PUBLISH_FINAL_NACK", eventType + "|KEY:" + key);
        return false;
    }

    private boolean sendAndAwaitReply(String topic, String key, Object payload, String eventType) throws Exception {
        long kafkaStart = System.currentTimeMillis();
        ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topic, key, payload);

        RequestReplyFuture<String, Object, String> future =
                replyingKafkaTemplate.sendAndReceive(producerRecord, Duration.ofMillis(publishTimeoutMs));

        logBrokerAck(future, eventType, key);

        ConsumerRecord<String, String> reply = future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        // The reply came back over Kafka, so the cluster is reachable - whatever the
        // consumer thought of the payload.
        connectivityMonitoringService.recordSuccess(Dependency.KAFKA);
        log.info(LoggingAdviceConstants.UP_KAFKA, System.currentTimeMillis() - kafkaStart, "PUBLISH_ACK", eventType + "|KEY:" + key);
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

    private void handleRetryOrThrow(Exception e, int attempt, String eventType, String key, long elapsedMs) {
        connectivityMonitoringService.recordFailure(Dependency.KAFKA, e);
        log.error(LoggingAdviceConstants.UP_KAFKA, elapsedMs, "PUBLISH_FAIL_ATTEMPT_" + attempt, eventType + "|KEY:" + key + "|ERROR:" + e.getMessage());

        if (!retryEnabled || attempt >= maxRetryAttempts) {
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
}