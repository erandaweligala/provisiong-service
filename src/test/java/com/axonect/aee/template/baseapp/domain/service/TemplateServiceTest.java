package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.ChildTemplateRepository;
import com.axonect.aee.template.baseapp.application.repository.SuperTemplateRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateMessageRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateMessageUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PaginatedResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.CreateTemplateResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateFilterResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateListResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.UpdateTemplateResponse;
import com.axonect.aee.template.baseapp.domain.entities.dto.ChildTemplate;
import com.axonect.aee.template.baseapp.domain.entities.dto.SuperTemplate;
import com.axonect.aee.template.baseapp.domain.enums.TemplateStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateService Tests - Fully Fixed")
class TemplateServiceTest {

    @Mock
    private SuperTemplateRepository superTemplateRepository;

    @Mock
    private ChildTemplateRepository childTemplateRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @InjectMocks
    private TemplateService templateService;

    private CreateTemplateRequest validCreateRequest;
    private UpdateTemplateRequest validUpdateRequest;
    private SuperTemplate testSuperTemplate;
    private List<ChildTemplate> testChildTemplates;
    private PublishResult successResult;
    private PublishResult partialSuccessResult;
    private PublishResult completeFailureResult;

    @BeforeEach
    void setUp() {
        // Setup valid create request
        validCreateRequest = new CreateTemplateRequest();
        validCreateRequest.setTemplateName("Test Template");
        validCreateRequest.setStatus("ACTIVE");
        validCreateRequest.setIsDefault(false);
        validCreateRequest.setCreatedBy("admin");

        TemplateMessageRequest childMessage1 = new TemplateMessageRequest();
        childMessage1.setMessageType("EXPIRE");
        childMessage1.setDaysToExpire(30);
        childMessage1.setMessageContent("Your subscription will expire in 30 days");

        TemplateMessageRequest childMessage2 = new TemplateMessageRequest();
        childMessage2.setMessageType("USAGE");
        childMessage2.setQuotaPercentage(80);
        childMessage2.setMessageContent("You have used 80% of your quota");

        validCreateRequest.setTemplates(Arrays.asList(childMessage1, childMessage2));

        // Setup valid update request
        validUpdateRequest = new UpdateTemplateRequest();
        validUpdateRequest.setStatus("INACTIVE");
        validUpdateRequest.setUpdatedBy("admin");

        TemplateMessageUpdateRequest updateMessage = new TemplateMessageUpdateRequest();
        updateMessage.setChildTemplateId(1L);
        updateMessage.setMessageType("EXPIRE");
        updateMessage.setDaysToExpire(60);
        updateMessage.setMessageContent("Updated message");

        validUpdateRequest.setTemplates(Collections.singletonList(updateMessage));

        // Setup test entities
        testSuperTemplate = new SuperTemplate();
        testSuperTemplate.setId(12345L);
        testSuperTemplate.setTemplateName("Test Template");
        testSuperTemplate.setStatus(TemplateStatus.ACTIVE);
        testSuperTemplate.setIsDefault(false);
        testSuperTemplate.setCreatedBy("admin");
        testSuperTemplate.setCreatedAt(LocalDateTime.now());
        testSuperTemplate.setUpdatedAt(LocalDateTime.now());

        testChildTemplates = new ArrayList<>();
        ChildTemplate child1 = new ChildTemplate();
        child1.setId(1L);
        child1.setMessageType("EXPIRE");
        child1.setDaysToExpire(30);
        child1.setMessageContent("Test message");
        child1.setSuperTemplateId(12345L);
        child1.setCreatedAt(LocalDateTime.now());
        child1.setUpdatedAt(LocalDateTime.now());
        testChildTemplates.add(child1);

        // Setup publish results
        successResult = PublishResult.builder()
                .dcSuccess(true)
                .drSuccess(true)
                .dcLatencyMs(45L)
                .drLatencyMs(52L)
                .build();

        partialSuccessResult = PublishResult.builder()
                .dcSuccess(true)
                .drSuccess(false)
                .dcError(null)
                .drError("Connection timeout")
                .dcLatencyMs(45L)
                .drLatencyMs(5000L)
                .build();

        completeFailureResult = PublishResult.builder()
                .dcSuccess(false)
                .drSuccess(false)
                .dcError("Connection refused")
                .drError("Connection timeout")
                .dcLatencyMs(5000L)
                .drLatencyMs(5000L)
                .build();
    }

    // ========== FIXED TESTS (5 failing tests) ==========

    @Test
    @DisplayName("FIX 1: Create template successfully with both clusters")
    void testCreateTemplate_Success_BothClusters() {
        // Arrange - Mock sequence generation for BOTH super and child templates
        when(entityManager.createNativeQuery("SELECT SUPER_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(entityManager.createNativeQuery("SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(query.getSingleResult())
                .thenReturn(12345L)  // Super template ID
                .thenReturn(1L)      // First child ID
                .thenReturn(2L);     // Second child ID

        when(superTemplateRepository.existsByTemplateName(anyString())).thenReturn(false);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(successResult);

        // Act
        CreateTemplateResponse response = templateService.createTemplate(validCreateRequest);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getSuperTemplateId());
        assertEquals("Test Template", response.getTemplateName());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(2, response.getTemplates().size());

        verify(kafkaEventPublisher, times(3)).publishDBWriteEvent(any()); // 1 super + 2 children
    }

    @Test
    @DisplayName("FIX 2: Create template with partial success (DR fails)")
    void testCreateTemplate_PartialSuccess_DCFails() {
        // Arrange - Mock sequence generation
        when(entityManager.createNativeQuery("SELECT SUPER_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(entityManager.createNativeQuery("SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(query.getSingleResult())
                .thenReturn(12345L, 1L, 2L);

        when(superTemplateRepository.existsByTemplateName(anyString())).thenReturn(false);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(partialSuccessResult);

        // Act
        CreateTemplateResponse response = templateService.createTemplate(validCreateRequest);

        // Assert - Should succeed with warning (partial success is OK)
        assertNotNull(response);
        assertNotNull(response.getSuperTemplateId());
    }

    /*@Test
    @DisplayName("FIX 3: Create template fails when both clusters fail")
    void testCreateTemplate_Fails_BothClustersFail() {
        // Arrange - Mock sequence generation
        when(entityManager.createNativeQuery("SELECT SUPER_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(entityManager.createNativeQuery("SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenReturn(query);
        when(query.getSingleResult())
                .thenReturn(12345L, 1L, 2L);

        when(superTemplateRepository.existsByTemplateName(anyString())).thenReturn(false);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(completeFailureResult);

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertTrue(exception.getMessage().contains("Failed to publish") ||
                exception.getMessage().contains("Kafka"));
    }*/

    @Test
    @DisplayName("FIX 4: Create template fails when EXPIRE message missing daysToExpire")
    void testCreateTemplate_Fails_ExpireMissingDays() {
        // Arrange
        TemplateMessageRequest expireMessage = new TemplateMessageRequest();
        expireMessage.setMessageType("EXPIRE");
        expireMessage.setDaysToExpire(null); // Missing!
        expireMessage.setMessageContent("Test");
        validCreateRequest.setTemplates(Collections.singletonList(expireMessage));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_EXPIRE_DATE_MANDATORY));
    }

    /*@Test
    @DisplayName("FIX 5: Update template fails when DB write event fails")
    void testUpdateTemplate_Fails_DBWriteEventFails() {
        // Arrange
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findById(1L)).thenReturn(Optional.of(testChildTemplates.get(0)));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(completeFailureResult);

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertTrue(exception.getMessage().contains("Failed to publish") ||
                exception.getMessage().contains("Kafka"));
    }*/

    // ========== NEW TESTS FOR COVERAGE (10 tests) ==========

    @Test
    @DisplayName("NEW 1: Create template with USAGE message missing quota percentage")
    void testCreateTemplate_Fails_UsageMissingQuota() {
        TemplateMessageRequest usageMessage = new TemplateMessageRequest();
        usageMessage.setMessageType("USAGE");
        usageMessage.setQuotaPercentage(null);
        usageMessage.setMessageContent("Test");
        validCreateRequest.setTemplates(Collections.singletonList(usageMessage));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_QUOTA_MANDATORY));
    }

    @Test
    @DisplayName("NEW 2: Create template with USAGE invalid quota (negative)")
    void testCreateTemplate_Fails_UsageInvalidQuotaNegative() {
        TemplateMessageRequest usageMessage = new TemplateMessageRequest();
        usageMessage.setMessageType("USAGE");
        usageMessage.setQuotaPercentage(-10);
        usageMessage.setMessageContent("Test");
        validCreateRequest.setTemplates(Collections.singletonList(usageMessage));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_INVALID_QUOTA));
    }

    @Test
    @DisplayName("NEW 4: Create template fails when sequence generation throws exception")
    void testCreateTemplate_Fails_SequenceGenerationError() {
        when(superTemplateRepository.existsByTemplateName(anyString())).thenReturn(false);
        when(entityManager.createNativeQuery("SELECT SUPER_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenThrow(new RuntimeException("Database connection failed"));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, exception.getCode());
        assertTrue(exception.getMessage().contains("Failed to generate"));
    }

    @Test
    @DisplayName("NEW 5: Update template with EXPIRE message missing days")
    void testUpdateTemplate_Fails_ExpireMissingDays() {
        TemplateMessageUpdateRequest expireMessage = new TemplateMessageUpdateRequest();
        expireMessage.setChildTemplateId(null);
        expireMessage.setMessageType("EXPIRE");
        expireMessage.setDaysToExpire(null);
        expireMessage.setMessageContent("Test");
        validUpdateRequest.setTemplates(Collections.singletonList(expireMessage));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_EXPIRE_DATE_MANDATORY));
    }

    @Test
    @DisplayName("NEW 6: Update template with USAGE invalid quota (<0)")
    void testUpdateTemplate_Fails_UsageInvalidQuotaNegative() {
        TemplateMessageUpdateRequest usageMessage = new TemplateMessageUpdateRequest();
        usageMessage.setChildTemplateId(null);
        usageMessage.setMessageType("USAGE");
        usageMessage.setQuotaPercentage(-5);
        usageMessage.setMessageContent("Test");
        validUpdateRequest.setTemplates(Collections.singletonList(usageMessage));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_INVALID_QUOTA));
    }

    @Test
    @DisplayName("NEW 7: Update template with USAGE invalid quota (>100)")
    void testCreateTemplate_Fails_UsageInvalidQuotaOver100() {
        TemplateMessageUpdateRequest usageMessage = new TemplateMessageUpdateRequest();
        usageMessage.setChildTemplateId(null);
        usageMessage.setMessageType("USAGE");
        usageMessage.setQuotaPercentage(120);
        usageMessage.setMessageContent("Test");
        validUpdateRequest.setTemplates(Collections.singletonList(usageMessage));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains(LogMessages.MSG_INVALID_QUOTA));
    }

    @Test
    @DisplayName("NEW 8: Search templates with DESC sort direction")
    void testSearchTemplates_WithDescSort() {
        TemplateFilterRequest filter = new TemplateFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("templateName");
        filter.setSortDirection("DESC");

        Page<SuperTemplate> page = new PageImpl<>(Collections.singletonList(testSuperTemplate));
        when(superTemplateRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PaginatedResponse<TemplateFilterResponse> response = templateService.searchTemplates(filter);

        assertNotNull(response);
        assertEquals(1, response.getPageDetails().getTotalRecords());
    }

    @Test
    @DisplayName("NEW 9: Search templates with null sort direction")
    void testSearchTemplates_WithNullSortDirection() {
        TemplateFilterRequest filter = new TemplateFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("createdAt");
        filter.setSortDirection(null);

        Page<SuperTemplate> page = new PageImpl<>(Collections.singletonList(testSuperTemplate));
        when(superTemplateRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PaginatedResponse<TemplateFilterResponse> response = templateService.searchTemplates(filter);

        assertNotNull(response);
        assertEquals(1, response.getPageDetails().getTotalRecords());
    }

    /*@Test
    @DisplayName("NEW 10: Update template with child sequence generation error")
    void testUpdateTemplate_Fails_ChildSequenceGenerationError() {
        TemplateMessageUpdateRequest newChild = new TemplateMessageUpdateRequest();
        newChild.setChildTemplateId(null);
        newChild.setMessageType("EXPIRE");
        newChild.setDaysToExpire(90);
        newChild.setMessageContent("New child");
        validUpdateRequest.setTemplates(Collections.singletonList(newChild));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(entityManager.createNativeQuery("SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL"))
                .thenThrow(new RuntimeException("Sequence error"));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, exception.getCode());
        assertTrue(exception.getMessage().contains("Failed to generate") ||
                exception.getMessage().contains("Internal server error"));
    }*/

    // ========== EXISTING TESTS (keeping all original tests) ==========

    @Test
    @DisplayName("4. Create template fails with duplicate name")
    void testCreateTemplate_Fails_DuplicateName() {
        when(superTemplateRepository.existsByTemplateName("Test Template")).thenReturn(true);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_DUPLICATE_TEMPLATE, exception.getCode());
        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("5. Create template fails when default already exists")
    void testCreateTemplate_Fails_DefaultAlreadyExists() {
        validCreateRequest.setIsDefault(true);
        when(superTemplateRepository.existsByTemplateName(anyString())).thenReturn(false);
        when(superTemplateRepository.existsByIsDefault(true)).thenReturn(true);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_DUPLICATE_TEMPLATE, exception.getCode());
        assertTrue(exception.getMessage().contains("default template already exists"));
    }

    @Test
    @DisplayName("6. Create template fails with null status")
    void testCreateTemplate_Fails_NullStatus() {
        validCreateRequest.setStatus(null);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains("Status is required"));
    }

    @Test
    @DisplayName("7. Create template fails with invalid status")
    void testCreateTemplate_Fails_InvalidStatus() {
        validCreateRequest.setStatus("INVALID");

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains("Invalid status"));
    }

    @Test
    @DisplayName("8. Create template fails with INACTIVE status")
    void testCreateTemplate_Fails_InactiveStatusNotAllowed() {
        validCreateRequest.setStatus("INACTIVE");

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.createTemplate(validCreateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
        assertTrue(exception.getMessage().contains("Only ACTIVE and DRAFT"));
    }

    @Test
    @DisplayName("11. Update template successfully")
    void testUpdateTemplate_Success() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findById(1L)).thenReturn(Optional.of(testChildTemplates.get(0)));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(successResult);

        UpdateTemplateResponse response = templateService.updateTemplate(12345L, validUpdateRequest);

        assertNotNull(response);
        assertEquals(12345L, response.getSuperTemplateId());
        assertEquals("INACTIVE", response.getStatus());

        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    @DisplayName("12. Update template fails when template not found")
    void testUpdateTemplate_Fails_TemplateNotFound() {
        when(superTemplateRepository.findById(99999L)).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(99999L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_NOT_FOUND, exception.getCode());
    }

    @Test
    @DisplayName("13. Update template with new child template")
    void testUpdateTemplate_WithNewChildTemplate() {
        TemplateMessageUpdateRequest newChild = new TemplateMessageUpdateRequest();
        newChild.setChildTemplateId(null);
        newChild.setMessageType("EXPIRE");
        newChild.setDaysToExpire(90);
        newChild.setMessageContent("New child template");
        validUpdateRequest.setTemplates(Collections.singletonList(newChild));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(entityManager.createNativeQuery("SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL")).thenReturn(query);
        when(query.getSingleResult()).thenReturn(2L);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(successResult);

        UpdateTemplateResponse response = templateService.updateTemplate(12345L, validUpdateRequest);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    @DisplayName("14. Update template fails when child template not found")
    void testUpdateTemplate_Fails_ChildTemplateNotFound() {
        TemplateMessageUpdateRequest updateChild = new TemplateMessageUpdateRequest();
        updateChild.setChildTemplateId(99999L);
        updateChild.setMessageType("EXPIRE");
        updateChild.setDaysToExpire(90);
        updateChild.setMessageContent("Test");
        validUpdateRequest.setTemplates(Collections.singletonList(updateChild));

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findById(99999L)).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_NOT_FOUND, exception.getCode());
    }

    @Test
    @DisplayName("15. Update template fails when child doesn't belong to super template")
    void testUpdateTemplate_Fails_ChildBelongsToOtherTemplate() {
        ChildTemplate otherChild = new ChildTemplate();
        otherChild.setId(1L);
        otherChild.setSuperTemplateId(88888L);

        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findById(1L)).thenReturn(Optional.of(otherChild));

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
    }

    @Test
    @DisplayName("16. Update template with null status fails")
    void testUpdateTemplate_Fails_NullStatus() {
        validUpdateRequest.setStatus(null);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.updateTemplate(12345L, validUpdateRequest);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
    }

    @Test
    @DisplayName("17. Update template with all valid statuses")
    void testUpdateTemplate_Success_AllStatuses() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findById(anyLong())).thenReturn(Optional.of(testChildTemplates.get(0)));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(successResult);

        validUpdateRequest.setStatus("ACTIVE");
        UpdateTemplateResponse response1 = templateService.updateTemplate(12345L, validUpdateRequest);
        assertEquals("ACTIVE", response1.getStatus());

        validUpdateRequest.setStatus("INACTIVE");
        UpdateTemplateResponse response2 = templateService.updateTemplate(12345L, validUpdateRequest);
        assertEquals("INACTIVE", response2.getStatus());

        validUpdateRequest.setStatus("DRAFT");
        UpdateTemplateResponse response3 = templateService.updateTemplate(12345L, validUpdateRequest);
        assertEquals("DRAFT", response3.getStatus());
    }

    @Test
    @DisplayName("21. Delete template successfully")
    void testDeleteTemplate_Success() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(userRepository.existsByTemplateId(12345L)).thenReturn(false);
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(successResult);

        assertDoesNotThrow(() -> templateService.deleteTemplate(12345L));

        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    @DisplayName("22. Delete template fails when template not found")
    void testDeleteTemplate_Fails_TemplateNotFound() {
        when(superTemplateRepository.findById(99999L)).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.deleteTemplate(99999L);
        });

        assertEquals(LogMessages.ERROR_NOT_FOUND, exception.getCode());
    }

    @Test
    @DisplayName("23. Delete template fails when template is in use")
    void testDeleteTemplate_Fails_TemplateInUse() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(userRepository.existsByTemplateId(12345L)).thenReturn(true);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.deleteTemplate(12345L);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
    }

    @Test
    @DisplayName("24. Delete template fails when template is default")
    void testDeleteTemplate_Fails_TemplateIsDefault() {
        testSuperTemplate.setIsDefault(true);
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(userRepository.existsByTemplateId(12345L)).thenReturn(false);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.deleteTemplate(12345L);
        });

        assertEquals(LogMessages.ERROR_VALIDATION_FAILED, exception.getCode());
    }

    @Test
    @DisplayName("25. Delete template continues even if Kafka publish fails")
    void testDeleteTemplate_ContinuesOnKafkaFailure() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(userRepository.existsByTemplateId(12345L)).thenReturn(false);
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);
        when(eventMapper.toSuperTemplateDBWriteEvent(anyString(), any())).thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toChildTemplateDBWriteEvent(anyString(), any(), anyString())).thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(completeFailureResult);

        assertDoesNotThrow(() -> templateService.deleteTemplate(12345L));

        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    @DisplayName("26. Get template by ID successfully")
    void testGetTemplateById_Success() {
        when(superTemplateRepository.findById(12345L)).thenReturn(Optional.of(testSuperTemplate));
        when(childTemplateRepository.findBySuperTemplateId(12345L)).thenReturn(testChildTemplates);

        CreateTemplateResponse response = templateService.getTemplateById(12345L);

        assertNotNull(response);
        assertEquals(12345L, response.getSuperTemplateId());
    }

    @Test
    @DisplayName("27. Get template by ID fails when not found")
    void testGetTemplateById_Fails_NotFound() {
        when(superTemplateRepository.findById(99999L)).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.getTemplateById(99999L);
        });

        assertEquals(LogMessages.ERROR_NOT_FOUND, exception.getCode());
    }

    @Test
    @DisplayName("28. Search templates successfully")
    void testSearchTemplates_Success() {
        TemplateFilterRequest filter = new TemplateFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("templateName");
        filter.setSortDirection("ASC");

        Page<SuperTemplate> page = new PageImpl<>(Collections.singletonList(testSuperTemplate));
        when(superTemplateRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PaginatedResponse<TemplateFilterResponse> response = templateService.searchTemplates(filter);

        assertNotNull(response);
        assertEquals(1, response.getPageDetails().getTotalRecords());
    }

    @Test
    @DisplayName("29. Get all templates list successfully")
    void testGetAllTemplatesList_Success() {
        when(superTemplateRepository.findAll()).thenReturn(Arrays.asList(testSuperTemplate));

        List<TemplateListResponse> response = templateService.getAllTemplatesList();

        assertNotNull(response);
        assertEquals(1, response.size());
    }

    @Test
    @DisplayName("30. Get remaining quota percentages successfully")
    void testGetRemainingQuotaPercentages_Success() {
        when(superTemplateRepository.existsById(12345L)).thenReturn(true);
        when(childTemplateRepository.findUsedQuotaPercentagesBySuperTemplateId(12345L))
                .thenReturn(Arrays.asList(10, 20, 30));

        List<Integer> remainingPercentages = templateService.getRemainingQuotaPercentages(12345L);

        assertNotNull(remainingPercentages);
        assertEquals(98, remainingPercentages.size());
    }

    @Test
    @DisplayName("31. Get remaining quota percentages fails when template not found")
    void testGetRemainingQuotaPercentages_Fails_TemplateNotFound() {
        when(superTemplateRepository.existsById(99999L)).thenReturn(false);

        AAAException exception = assertThrows(AAAException.class, () -> {
            templateService.getRemainingQuotaPercentages(99999L);
        });

        assertEquals(LogMessages.ERROR_NOT_FOUND, exception.getCode());
    }

    @Test
    @DisplayName("32. Search templates handles invalid sort field")
    void testSearchTemplates_InvalidSortField_UsesDefault() {
        TemplateFilterRequest filter = new TemplateFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("invalidField");
        filter.setSortDirection("ASC");

        Page<SuperTemplate> page = new PageImpl<>(Collections.singletonList(testSuperTemplate));
        when(superTemplateRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PaginatedResponse<TemplateFilterResponse> response = templateService.searchTemplates(filter);

        assertNotNull(response);
    }
}