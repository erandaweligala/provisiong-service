package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ChildTemplateRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.ChildTemplate;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserEntity;
import com.axonect.aee.template.baseapp.domain.events.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationGeneratorTest {

    @Mock
    private ChildTemplateRepository childTemplateRepository;

    @InjectMocks
    private NotificationGenerator notificationGenerator;

    private static final String MESSAGE_TYPE    = "USER_CREATION";
    private static final Long   SUPER_TEMPLATE_ID = 42L;

    private UserEntity userWithTemplate;
    private UserEntity userWithoutTemplate;

    @BeforeEach
    void setUp() {
        userWithTemplate = new UserEntity();
        userWithTemplate.setUserName("john.doe");
        userWithTemplate.setTemplateId(SUPER_TEMPLATE_ID);

        userWithoutTemplate = new UserEntity();
        userWithoutTemplate.setUserName("jane.doe");
        userWithoutTemplate.setTemplateId(null);
    }

    // -------------------------------------------------------------------------
    // Test 1: user has no templateId → returns empty, repo never called
    // -------------------------------------------------------------------------
    @Test
    void generate_shouldReturnEmpty_whenUserHasNoTemplateId() {
        Optional<NotificationEvent> result =
                notificationGenerator.generate(MESSAGE_TYPE, userWithoutTemplate);

        assertThat(result).isEmpty();
        verifyNoInteractions(childTemplateRepository);
    }

    // -------------------------------------------------------------------------
    // Test 2: repo returns no child templates → returns empty
    // -------------------------------------------------------------------------
    @Test
    void generate_shouldReturnEmpty_whenNoChildTemplatesExist() {
        when(childTemplateRepository.findBySuperTemplateId(SUPER_TEMPLATE_ID))
                .thenReturn(List.of());

        Optional<NotificationEvent> result =
                notificationGenerator.generate(MESSAGE_TYPE, userWithTemplate);

        assertThat(result).isEmpty();
        verify(childTemplateRepository).findBySuperTemplateId(SUPER_TEMPLATE_ID);
    }

    // -------------------------------------------------------------------------
    // Test 3: child templates exist but none matches the messageType → returns empty
    // -------------------------------------------------------------------------
    @Test
    void generate_shouldReturnEmpty_whenNoChildTemplateMatchesMessageType() {
        ChildTemplate unrelatedTemplate = new ChildTemplate();
        unrelatedTemplate.setMessageType("PASSWORD_RESET");
        unrelatedTemplate.setMessageContent("Reset password for {userName}");

        when(childTemplateRepository.findBySuperTemplateId(SUPER_TEMPLATE_ID))
                .thenReturn(List.of(unrelatedTemplate));

        Optional<NotificationEvent> result =
                notificationGenerator.generate(MESSAGE_TYPE, userWithTemplate);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 4: happy path – matching template found, placeholder replaced, event built
    // -------------------------------------------------------------------------
    @Test
    void generate_shouldReturnNotificationEvent_whenMatchingTemplateExists() {
        ChildTemplate matchingTemplate = new ChildTemplate();
        matchingTemplate.setMessageType("USER_CREATION");
        matchingTemplate.setMessageContent("Welcome, {userName}! Your account is ready.");

        when(childTemplateRepository.findBySuperTemplateId(SUPER_TEMPLATE_ID))
                .thenReturn(List.of(matchingTemplate));

        Optional<NotificationEvent> result =
                notificationGenerator.generate(MESSAGE_TYPE, userWithTemplate);

        assertThat(result).isPresent();
        NotificationEvent event = result.get();
        assertThat(event.getMessageType()).isEqualTo(MESSAGE_TYPE);
        assertThat(event.getUserName()).isEqualTo("john.doe");
        assertThat(event.getMessage()).isEqualTo("Welcome, john.doe! Your account is ready.");
        assertThat(event.getTimestamp()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Test 5: messageType matching is case-insensitive
    // -------------------------------------------------------------------------
    @Test
    void generate_shouldMatchMessageType_caseInsensitively() {
        ChildTemplate templateWithUpperCase = new ChildTemplate();
        templateWithUpperCase.setMessageType("USER_CREATION");   // stored upper-case
        templateWithUpperCase.setMessageContent("Hello {userName}");

        when(childTemplateRepository.findBySuperTemplateId(SUPER_TEMPLATE_ID))
                .thenReturn(List.of(templateWithUpperCase));

        // Pass lower-case → equalsIgnoreCase must still match
        Optional<NotificationEvent> result =
                notificationGenerator.generate("user_creation", userWithTemplate);

        assertThat(result).isPresent();
        assertThat(result.get().getMessage()).isEqualTo("Hello john.doe");
    }
}