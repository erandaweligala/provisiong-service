package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ChildTemplateRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.ChildTemplate;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserEntity;
import com.axonect.aee.template.baseapp.domain.events.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Generates a {@link NotificationEvent} for a given messageType and user.
 *
 * Flow:
 *  1. Gets the user's templateId (= superTemplateId in ChildTemplate).
 *  2. Fetches all child templates linked to that superTemplateId.
 *  3. Finds the one whose messageType matches (e.g. "USER_CREATION").
 *  4. Replaces {userName} in messageContent with the actual username.
 *  5. Returns a built NotificationEvent, or empty if nothing matched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationGenerator {

    private static final String USER_NAME_PLACEHOLDER = "{userName}";
    private static final String DATE_PATTERN          = "yyyy-MM-dd'T'HH:mm:ss.SSS";

    private final ChildTemplateRepository childTemplateRepository;

    /**
     * @param messageType e.g. "USER_CREATION"
     * @param user        the newly created user entity
     * @return NotificationEvent if a matching child template exists, empty otherwise
     */
    public Optional<NotificationEvent> generate(String messageType, UserEntity user) {

        // Guard – user must have a templateId (= superTemplateId)
        if (user.getTemplateId() == null) {
            log.info("No templateId on user '{}', skipping '{}' notification",
                    user.getUserName(), messageType);
            return Optional.empty();
        }

        Long superTemplateId = user.getTemplateId();

        // Fetch all child templates for this super-template
        List<ChildTemplate> childTemplates =
                childTemplateRepository.findBySuperTemplateId(superTemplateId);

        if (childTemplates.isEmpty()) {
            log.info("No child templates found for superTemplateId={}, "
                            + "notification skipped for user '{}'",
                    superTemplateId, user.getUserName());
            return Optional.empty();
        }

        // Find the child template whose messageType matches
        Optional<ChildTemplate> match = childTemplates.stream()
                .filter(ct -> messageType.equalsIgnoreCase(ct.getMessageType()))
                .findFirst();

        if (match.isEmpty()) {
            log.info("No child template with messageType='{}' under superTemplateId={} "
                            + "for user '{}'",
                    messageType, superTemplateId, user.getUserName());
            return Optional.empty();
        }

        // Replace {userName} placeholder in messageContent
        String resolvedMessage = match.get().getMessageContent()
                .replace(USER_NAME_PLACEHOLDER, user.getUserName());

        NotificationEvent event = NotificationEvent.builder()
                .messageType(messageType)
                .userName(user.getUserName())
                .message(resolvedMessage)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN)))
                .build();

        log.info("NotificationEvent generated: type='{}', user='{}'",
                messageType, user.getUserName());
        return Optional.of(event);
    }
}