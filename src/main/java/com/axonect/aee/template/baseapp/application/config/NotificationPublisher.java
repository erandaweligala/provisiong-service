package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.domain.events.NotificationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes {@link NotificationEvent} messages to the Kafka Notification Topic.
 *
 * - userName is used as the Kafka message key (ordering guarantee per user).
 * - Fire-and-forget with async callback logging.
 * - Notification failures are logged as warnings; they never block callers.
 *
 * Required application property:
 *   app.kafka.notification-topic=<your-topic-name>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.notification-topic}")
    private String notificationTopic;

    /**
     * Serialises the event to JSON and sends it to the Notification Topic.
     *
     * @param event the fully built notification event
     */
    public void publish(NotificationEvent event) {
        String userName = event.getUserName();

        try {
            String payload = objectMapper.writeValueAsString(event);

            log.info("Publishing notification: type='{}', user='{}', topic='{}'",
                    event.getMessageType(), userName, notificationTopic);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(notificationTopic, userName, payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish notification for user '{}': {}",
                            userName, ex.getMessage(), ex);
                } else {
                    log.info("Notification published for user '{}' → partition={}, offset={}",
                            userName,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

        } catch (JsonProcessingException e) {
            log.error("Failed to serialise NotificationEvent for user '{}': {}",
                    userName, e.getMessage(), e);
        }
    }
}