package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Kafka event payload published to the Notification Topic.
 *
 * Example JSON sent to the topic:
 * {
 *   "messageType" : "USER_CREATION",
 *   "userName"    : "john.doe",
 *   "message"     : "Dear john.doe, your account has been successfully created.",
 *   "timestamp"   : "2025-02-17T10:30:00.123"
 * }
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    /** Matches messageType in ChildTemplate (e.g. "USER_CREATION"). */
    private String messageType;

    /** The username this notification targets. */
    private String userName;

    /** Fully resolved message with all placeholders replaced. */
    private String message;

    /** ISO-8601 timestamp of event generation. */
    private String timestamp;
}