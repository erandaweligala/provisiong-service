package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.ChildTemplateRepository;
import com.axonect.aee.template.baseapp.application.repository.SuperTemplateRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateMessageRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateMessageUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PageDetails;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PaginatedResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.CreateTemplateResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateFilterResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateListResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.UpdateTemplateResponse;
import com.axonect.aee.template.baseapp.domain.entities.dto.ChildTemplate;
import com.axonect.aee.template.baseapp.domain.entities.dto.SuperTemplate;
import com.axonect.aee.template.baseapp.domain.enums.MessageType;
import com.axonect.aee.template.baseapp.domain.enums.TemplateStatus;
import com.axonect.aee.template.baseapp.domain.events.ChildTemplateEvent;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.events.SuperTemplateEvent;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.specification.TemplateSpecification;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final SuperTemplateRepository superTemplateRepository;
    private final ChildTemplateRepository childTemplateRepository;
    private final UserRepository userRepository;
    private final KafkaEventPublisher kafkaEventPublisher;  //    ADD THIS
    private final EventMapper eventMapper;  //    ADD THIS
    private final EntityManager entityManager;  // ADD THIS


    private static final Set<String> VALID_SORT_FIELDS = Set.of(
            "templateName", "status", "isDefault", "createdAt", "updatedAt", "createdBy"
    );
    private static final String TEMPLATE_NOT_FOUND = "Template not found with ID: {}";
    private static final String INVALID_STATUS = "Invalid status '";

    // ========== ID GENERATION METHODS ==========

    /**
     * Generates a non-cryptographic unique template ID.
     * Used internally; cryptographic randomness is not required.
     */
    /*@SuppressWarnings("java:S2245")
    private Long generateTemplateId() {
        long timestampPart = System.currentTimeMillis() % 1_000_000;
        int random = ThreadLocalRandom.current().nextInt(10_000);
        return timestampPart * 10_000L + random;
    }
    private Long generateSuperTemplateId() {
        return generateTemplateId();
    }

    private Long generateChildTemplateId() {
        return generateTemplateId();
    }*/

    /**
     * Get next sequence value for SuperTemplate ID
     */
    private Long getNextSuperTemplateSequenceValue() {
        try {
            Number nextVal = (Number) entityManager.createNativeQuery(
                    "SELECT SUPER_TEMPLATE_SEQ.NEXTVAL FROM DUAL"
            ).getSingleResult();
            return nextVal.longValue();
        } catch (Exception e) {
            log.error("Failed to get next sequence value for SuperTemplate", e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to generate SuperTemplate ID",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Get next sequence value for ChildTemplate ID
     */
    private Long getNextChildTemplateSequenceValue() {
        try {
            Number nextVal = (Number) entityManager.createNativeQuery(
                    "SELECT CHILD_TEMPLATE_SEQ.NEXTVAL FROM DUAL"
            ).getSingleResult();
            return nextVal.longValue();
        } catch (Exception e) {
            log.error("Failed to get next sequence value for ChildTemplate", e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to generate ChildTemplate ID",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }




    // ========== CREATE TEMPLATE (WITH KAFKA) ==========

    /**
     * Create a new template with child templates
     *
     * @param request CreateTemplateRequest
     * @return CreateTemplateResponse
     */
    @Transactional(readOnly = true)  //    CHANGED to readOnly
    public CreateTemplateResponse createTemplate(CreateTemplateRequest request) {
        log.info("Creating template: {}", request.getTemplateName());

        try {
            //    KEEP: All validations (READ-ONLY checks)
            TemplateStatus status = validateAndParseStatusForCreation(request.getStatus());
            validateTemplateNameUniqueness(request.getTemplateName());
            validateDefaultFlag(request.getIsDefault());
            validateChildTemplates(request.getTemplates());

            //    BUILD SuperTemplate entity (in-memory, no DB save)
            SuperTemplate superTemplate = mapToSuperTemplateEntity(request, status);

            //    GENERATE ID MANUALLY (since @PrePersist won't run)
            superTemplate.setId(getNextSuperTemplateSequenceValue());
            superTemplate.setCreatedAt(LocalDateTime.now());
            superTemplate.setUpdatedAt(LocalDateTime.now());

            log.info("SuperTemplate built with ID: {} (not saved to DB)", superTemplate.getId());

            //    BUILD ChildTemplate entities (in-memory, no DB save)
            List<ChildTemplate> childTemplates = mapToChildTemplateEntities(
                    request.getTemplates(),
                    superTemplate.getId()
            );

            //    GENERATE IDs for child templates
            for (ChildTemplate child : childTemplates) {
                child.setId(getNextChildTemplateSequenceValue());
                child.setCreatedAt(LocalDateTime.now());
                child.setUpdatedAt(LocalDateTime.now());
            }

            log.info("Built {} child templates (not saved to DB)", childTemplates.size());


            //    PUBLISH TO KAFKA INSTEAD
            publishTemplateCreatedEvents(superTemplate, childTemplates);

            //    Map to response
            CreateTemplateResponse response = mapToCreateTemplateResponse(superTemplate, childTemplates);

            log.info("Template '{}' created successfully with ID: {} (events published to Kafka)",
                    superTemplate.getTemplateName(), superTemplate.getId());

            return response;

        } catch (AAAException ex) {
            log.error("AAAException while creating template: code={}, message={}", ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while creating template '{}': {}", request.getTemplateName(), ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while creating template",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // ========== UPDATE TEMPLATE (WITH KAFKA) ==========

    /**
     * Update an existing template
     *
     * @param templateId Template internal ID
     * @param request UpdateTemplateRequest
     * @return UpdateTemplateResponse
     */
    @Transactional(readOnly = true)
    public UpdateTemplateResponse updateTemplate(Long templateId, UpdateTemplateRequest request) {
        log.info("Updating template with ID: {}", templateId);

        try {
            TemplateStatus status = validateAndParseStatusForUpdate(request.getStatus());

            SuperTemplate superTemplate = superTemplateRepository.findById(templateId)
                    .orElseThrow(() -> {
                        log.error(TEMPLATE_NOT_FOUND, templateId);
                        return new AAAException(
                                LogMessages.ERROR_NOT_FOUND,
                                LogMessages.MSG_TEMPLATE_NOT_FOUND,
                                HttpStatus.NOT_FOUND
                        );
                    });

            validateChildTemplatesForUpdate(request.getTemplates());

            superTemplate.setStatus(status);
            superTemplate.setUpdatedBy(request.getUpdatedBy());
            superTemplate.setUpdatedAt(LocalDateTime.now());

            log.info("SuperTemplate status updated to '{}' for ID: {} (in-memory)",
                    status, superTemplate.getId());

            List<ChildTemplate> updatedChildren = new ArrayList<>();
            List<ChildTemplate> newChildren = new ArrayList<>();

            processChildTemplatesInMemory(request.getTemplates(), templateId, updatedChildren, newChildren);

            publishTemplateUpdatedEvents(superTemplate, updatedChildren, newChildren);

            // ========== FIX: Combine all child templates for response ==========
            // Fetch existing child templates from DB (those not updated)
            List<ChildTemplate> existingChildTemplates = childTemplateRepository.findBySuperTemplateId(templateId);

            // Create a combined list
            List<ChildTemplate> allChildTemplates = new ArrayList<>();

            // Add all existing templates that were NOT updated
            Set<Long> updatedChildIds = updatedChildren.stream()
                    .map(ChildTemplate::getId)
                    .collect(Collectors.toSet());

            existingChildTemplates.stream()
                    .filter(child -> !updatedChildIds.contains(child.getId()))
                    .forEach(allChildTemplates::add);

            // Add updated children (with in-memory changes)
            allChildTemplates.addAll(updatedChildren);

            // Add newly created children (in-memory only)
            allChildTemplates.addAll(newChildren);

            log.info("Returning {} total child templates in response (existing: {}, updated: {}, new: {})",
                    allChildTemplates.size(),
                    existingChildTemplates.size() - updatedChildren.size(),
                    updatedChildren.size(),
                    newChildren.size());

            UpdateTemplateResponse response = mapToUpdateTemplateResponse(superTemplate, allChildTemplates);

            log.info("Template '{}' updated successfully (events published to Kafka)",
                    superTemplate.getTemplateName());

            return response;

        } catch (AAAException ex) {
            log.error("AAAException while updating template ID {}: code={}, message={}",
                    templateId, ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while updating template with ID {}: {}", templateId, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while updating template",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Process child templates in-memory - distinguish between updates and creates
     */
    private void processChildTemplatesInMemory(
            List<TemplateMessageUpdateRequest> templateMessages,
            Long superTemplateId,
            List<ChildTemplate> updatedChildren,
            List<ChildTemplate> newChildren) {

        try {
            for (TemplateMessageUpdateRequest templateMessage : templateMessages) {

                if (templateMessage.getChildTemplateId() != null) {
                    //    UPDATE existing child template (READ-ONLY fetch, in-memory update)
                    ChildTemplate existingTemplate = childTemplateRepository
                            .findById(templateMessage.getChildTemplateId())
                            .orElseThrow(() -> {
                                log.error("Child template not found with ID: {}", templateMessage.getChildTemplateId());
                                return new AAAException(
                                        LogMessages.ERROR_NOT_FOUND,
                                        "Child template with ID " + templateMessage.getChildTemplateId() + " not found",
                                        HttpStatus.NOT_FOUND
                                );
                            });

                    // Verify ownership
                    if (!existingTemplate.getSuperTemplateId().equals(superTemplateId)) {
                        log.error("Child template {} does not belong to super template {}",
                                templateMessage.getChildTemplateId(), superTemplateId);
                        throw new AAAException(
                                LogMessages.ERROR_VALIDATION_FAILED,
                                "Child template does not belong to this super template",
                                HttpStatus.BAD_REQUEST
                        );
                    }

                    // Update fields in-memory
                    existingTemplate.setDaysToExpire(templateMessage.getDaysToExpire());
                    existingTemplate.setQuotaPercentage(templateMessage.getQuotaPercentage());
                    existingTemplate.setMessageContent(templateMessage.getMessageContent());
                    existingTemplate.setUpdatedAt(LocalDateTime.now());

                    updatedChildren.add(existingTemplate);
                    log.info("Updated child template with ID: {} (in-memory)", existingTemplate.getId());

                } else {
                    //    CREATE new child template
                    ChildTemplate newTemplate = new ChildTemplate();
                    newTemplate.setId(getNextChildTemplateSequenceValue());  // Generate ID manually
                    newTemplate.setMessageType(templateMessage.getMessageType());
                    newTemplate.setDaysToExpire(templateMessage.getDaysToExpire());
                    newTemplate.setQuotaPercentage(templateMessage.getQuotaPercentage());
                    newTemplate.setMessageContent(templateMessage.getMessageContent());
                    newTemplate.setSuperTemplateId(superTemplateId);
                    newTemplate.setCreatedAt(LocalDateTime.now());
                    newTemplate.setUpdatedAt(LocalDateTime.now());

                    newChildren.add(newTemplate);
                    log.info("Created new child template with ID: {} (in-memory)", newTemplate.getId());
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while processing child templates for super template ID {}: {}",
                    superTemplateId, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while processing child templates",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // ========== DELETE TEMPLATE (WITH KAFKA) ==========

    /**
     * Delete a template by ID (via Kafka events)
     * Validates that template is not in use by any user before deletion
     *
     * @param templateId Template ID to delete
     */
    @Transactional(readOnly = true)  //    CHANGED to readOnly
    public void deleteTemplate(Long templateId) {
        log.info("Attempting to delete template with ID: {}", templateId);

        try {
            //    KEEP: Check if template exists (READ-ONLY)
            SuperTemplate superTemplate = superTemplateRepository.findById(templateId)
                    .orElseThrow(() -> {
                        log.error(TEMPLATE_NOT_FOUND, templateId);
                        return new AAAException(
                                LogMessages.ERROR_NOT_FOUND,
                                LogMessages.MSG_TEMPLATE_NOT_FOUND,
                                HttpStatus.NOT_FOUND
                        );
                    });

            //    KEEP: Check if template is in use (READ-ONLY)
            boolean isInUse = userRepository.existsByTemplateId(templateId);
            if (isInUse) {
                log.error("Cannot delete template '{}' (ID: {}) - it is currently in use by one or more users",
                        superTemplate.getTemplateName(), templateId);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Cannot delete template '" + superTemplate.getTemplateName() + "'. This template is currently assigned to one or more users. Please reassign users to a different template before deletion.",
                        HttpStatus.CONFLICT
                );
            }

            //    KEEP: Check if it's the default template
            if (Boolean.TRUE.equals(superTemplate.getIsDefault())) {
                log.error("Cannot delete default template '{}' (ID: {})", superTemplate.getTemplateName(), templateId);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Cannot delete the default template '" + superTemplate.getTemplateName() + "'. Please set another template as default before deleting this one.",
                        HttpStatus.CONFLICT
                );
            }

            //    KEEP: Fetch child templates (READ-ONLY)
            List<ChildTemplate> childTemplates = childTemplateRepository.findBySuperTemplateId(templateId);


            //    PUBLISH TO KAFKA INSTEAD
            publishTemplateDeletedEvents(superTemplate, childTemplates);

            log.info("Template '{}' (ID: {}) deletion events published successfully",
                    superTemplate.getTemplateName(), templateId);

        } catch (AAAException ex) {
            log.error("AAAException while deleting template: code={}, message={}", ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while deleting template with ID: {}", templateId, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while deleting template",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // ========== KAFKA EVENT PUBLISHING METHODS ==========

    /**
     * Publish SuperTemplate and ChildTemplate creation events to Kafka
     */
    private void publishTemplateCreatedEvents(SuperTemplate superTemplate, List<ChildTemplate> childTemplates) {
        try {


            // 2. Publish SuperTemplate DB write event
            DBWriteRequestGeneric superDbEvent = eventMapper.toSuperTemplateDBWriteEvent("CREATE", superTemplate);
            PublishResult superDbResult = kafkaEventPublisher.publishDBWriteEvent(superDbEvent);

            // Check for complete failures
            if (superDbResult.isCompleteFailure() || superDbResult.isCompleteFailure()) {
                log.error("Complete failure publishing super template creation events for template ID '{}'",
                        superTemplate.getId());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Failed to publish template created events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            if (!superDbResult.isBothSuccess()) {
                log.warn("Partial failure publishing super template DB event. DC: {}, DR: {}",
                        superDbResult.isDcSuccess(), superDbResult.isDrSuccess());
            }

            // 3. Publish ChildTemplate events
            publishChildTemplatesCreatedEvents(childTemplates, superTemplate.getCreatedBy());

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish template created events for template ID '{}'",
                    superTemplate.getId(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish template created events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish ChildTemplate creation events
     */
    private void publishChildTemplatesCreatedEvents(List<ChildTemplate> childTemplates, String userName) {
        try {
            List<PublishResult> failedResults = new ArrayList<>();

            for (ChildTemplate child : childTemplates) {


                // Publish DB write event
                DBWriteRequestGeneric dbEvent = eventMapper.toChildTemplateDBWriteEvent("CREATE", child, userName);
                PublishResult dbResult = kafkaEventPublisher.publishDBWriteEvent(dbEvent);

                if (dbResult.isCompleteFailure()) {
                    failedResults.add(dbResult);
                    log.error("Failed to publish child template created events for child ID '{}'",
                            child.getId());
                }
                if (!dbResult.isBothSuccess()) {
                    log.warn("Partial failure publishing DB event for child ID '{}'. DC: {}, DR: {}",
                            child.getId(), dbResult.isDcSuccess(), dbResult.isDrSuccess());
                }
            }

            if (!failedResults.isEmpty()) {
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        String.format("Failed to publish %d child template creation events", failedResults.size()),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish child template created events", e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish child template created events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish SuperTemplate and ChildTemplate update events to Kafka
     */
    private void publishTemplateUpdatedEvents(
            SuperTemplate superTemplate,
            List<ChildTemplate> updatedChildren,
            List<ChildTemplate> newChildren) {

        try {


            // 2. Publish SuperTemplate DB write event
            DBWriteRequestGeneric superDbEvent = eventMapper.toSuperTemplateDBWriteEvent("UPDATE", superTemplate);
            PublishResult superDbResult = kafkaEventPublisher.publishDBWriteEvent(superDbEvent);

            // Check for complete failures
            if (superDbResult.isCompleteFailure()) {
                log.error("Complete failure publishing super template update events for template ID '{}'",
                        superTemplate.getId());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Failed to publish template updated events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            if (!superDbResult.isBothSuccess()) {
                log.warn("Partial failure publishing super template DB update event. DC: {}, DR: {}",
                        superDbResult.isDcSuccess(), superDbResult.isDrSuccess());
            }

            // 3. Publish updated ChildTemplate events
            if (!updatedChildren.isEmpty()) {
                publishChildTemplatesUpdatedEvents(updatedChildren, superTemplate.getUpdatedBy());
            }

            // 4. Publish new ChildTemplate events
            if (!newChildren.isEmpty()) {
                publishChildTemplatesCreatedEvents(newChildren, superTemplate.getUpdatedBy());
            }

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish template updated events for template ID '{}'",
                    superTemplate.getId(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish template updated events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish ChildTemplate update events
     */
    private void publishChildTemplatesUpdatedEvents(List<ChildTemplate> childTemplates, String userName) {
        try {
            for (ChildTemplate child : childTemplates) {

                // Publish DB write event
                DBWriteRequestGeneric dbEvent = eventMapper.toChildTemplateDBWriteEvent("UPDATE", child, userName);
                PublishResult dbResult = kafkaEventPublisher.publishDBWriteEvent(dbEvent);

                if (dbResult.isCompleteFailure() || dbResult.isCompleteFailure()) {
                    log.error("Failed to publish child template update events for child ID '{}'", child.getId());
                    throw new AAAException(
                            LogMessages.ERROR_INTERNAL_ERROR,
                            "Failed to publish child template update events",
                            HttpStatus.INTERNAL_SERVER_ERROR
                    );
                }

                if (!dbResult.isBothSuccess()) {
                    log.warn("Partial failure publishing DB update event. DC: {}, DR: {}",
                            dbResult.isDcSuccess(), dbResult.isDrSuccess());
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish child template updated events", e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish child template updated events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish SuperTemplate and ChildTemplate deletion events to Kafka
     */
    private void publishTemplateDeletedEvents(SuperTemplate superTemplate, List<ChildTemplate> childTemplates) {
        try {
            // 1. Delete child templates first (FK constraint)
            if (!childTemplates.isEmpty()) {
                publishChildTemplatesDeletedEvents(childTemplates, superTemplate.getCreatedBy());
            }

            DBWriteRequestGeneric superDbEvent = eventMapper.toSuperTemplateDBWriteEvent("DELETE", superTemplate);
            PublishResult superDbResult = kafkaEventPublisher.publishDBWriteEvent(superDbEvent);

            // For deletes, log but don't throw
            if (superDbResult.isCompleteFailure()) {
                log.error("Failed to publish super template delete event for template ID '{}'", superTemplate.getId());
            }
            if (superDbResult.isCompleteFailure()) {
                log.error("Failed to publish super template DB delete event for template ID '{}'", superTemplate.getId());
            }

        } catch (Exception e) {
            log.error("Failed to publish template deleted events for template ID '{}'", superTemplate.getId(), e);
            // Don't throw for deletes - log only
        }
    }

    /**
     * Publish ChildTemplate deletion events
     */
    private void publishChildTemplatesDeletedEvents(List<ChildTemplate> childTemplates, String userName) {
        try {
            for (ChildTemplate child : childTemplates) {
                DBWriteRequestGeneric dbEvent = eventMapper.toChildTemplateDBWriteEvent("DELETE", child, userName);
                kafkaEventPublisher.publishDBWriteEvent(dbEvent);
            }
        } catch (Exception e) {
            log.error("Failed to publish child template deleted events", e);
            // Don't throw for deletes - log only
        }
    }

    // ========== READ-ONLY METHODS (NO CHANGES) ==========

    /**
     * Search/filter templates with pagination
     *
     * @param filter TemplateFilterRequest
     * @return PaginatedResponse with template list
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<TemplateFilterResponse> searchTemplates(TemplateFilterRequest filter) {
        log.info("Searching templates with filters: {}", filter);

        try {
            Specification<SuperTemplate> spec = TemplateSpecification.filterTemplate(filter);

            int pageIndex = filter.getPage() > 0 ? filter.getPage() - 1 : 0;
            Sort sort = buildSort(filter.getSortBy(), filter.getSortDirection());
            Pageable pageable = PageRequest.of(pageIndex, filter.getSize(), sort);

            Page<SuperTemplate> page = superTemplateRepository.findAll(spec, pageable);

            List<TemplateFilterResponse> content = page.getContent().stream()
                    .map(this::mapToTemplateFilterResponse)
                    .toList();

            PageDetails pageDetails = new PageDetails(
                    page.getTotalElements(),
                    filter.getPage(),
                    page.getNumberOfElements()
            );

            log.info("Found {} template records", page.getTotalElements());

            return PaginatedResponse.<TemplateFilterResponse>builder()
                    .templateData(content)
                    .pageDetails(pageDetails)
                    .build();

        } catch (Exception ex) {
            log.error("Error occurred while searching templates: {}", ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.SEARCH_ERROR,
                    "Error occurred while searching template records",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Get template by ID
     */
    @Transactional(readOnly = true)
    public CreateTemplateResponse getTemplateById(Long templateId) {
        log.info("Retrieving template with ID: {}", templateId);

        try {
            SuperTemplate superTemplate = superTemplateRepository.findById(templateId)
                    .orElseThrow(() -> {
                        log.error(TEMPLATE_NOT_FOUND, templateId);
                        return new AAAException(
                                LogMessages.ERROR_NOT_FOUND,
                                LogMessages.MSG_TEMPLATE_NOT_FOUND,
                                HttpStatus.NOT_FOUND
                        );
                    });

            List<ChildTemplate> childTemplates = childTemplateRepository.findBySuperTemplateId(templateId);

            log.info("Template '{}' retrieved successfully", superTemplate.getTemplateName());

            return mapToCreateTemplateResponse(superTemplate, childTemplates);

        } catch (AAAException ex) {
            log.error("AAAException while retrieving template ID {}: code={}, message={}",
                    templateId, ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while retrieving template with ID {}: {}", templateId, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while retrieving template",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Get all templates as a simple list (ID and name only, no pagination)
     *
     * @return List of template IDs and names
     */
    @Transactional(readOnly = true)
    public List<TemplateListResponse> getAllTemplatesList() {
        log.info("Fetching all templates list (ID and name only)");

        try {
            List<SuperTemplate> templates = superTemplateRepository.findAll();

            log.info("Found {} templates", templates.size());

            return templates.stream()
                    .map(template -> new TemplateListResponse(
                            template.getId(),
                            template.getTemplateName()
                    ))
                    .toList();

        } catch (Exception ex) {
            log.error("Error occurred while fetching templates list: {}", ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while fetching templates list",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Get remaining quota percentages for a specific super template
     *
     * @param superTemplateId Super template ID
     * @return List of available quota percentages (0-100)
     */
    @Transactional(readOnly = true)
    public List<Integer> getRemainingQuotaPercentages(Long superTemplateId) {
        log.info("Fetching remaining quota percentages for super template ID: {}", superTemplateId);

        try {
            if (!superTemplateRepository.existsById(superTemplateId)) {
                log.error("Super template not found with ID: {}", superTemplateId);
                throw new AAAException(
                        LogMessages.ERROR_NOT_FOUND,
                        LogMessages.MSG_TEMPLATE_NOT_FOUND,
                        HttpStatus.NOT_FOUND
                );
            }

            List<Integer> usedQuotaPercentages = childTemplateRepository
                    .findUsedQuotaPercentagesBySuperTemplateId(superTemplateId);

            log.info("Found {} used quota percentages for super template ID: {}",
                    usedQuotaPercentages.size(), superTemplateId);

            List<Integer> remainingPercentages = IntStream.rangeClosed(0, 100)
                    .boxed()
                    .filter(p -> !usedQuotaPercentages.contains(p))
                    .toList();

            log.info("Returning {} remaining quota percentages", remainingPercentages.size());

            return remainingPercentages;

        } catch (AAAException ex) {
            log.error("AAAException while fetching remaining quota percentages: code={}, message={}",
                    ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while fetching remaining quota percentages for super template ID: {}",
                    superTemplateId, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while fetching remaining quota percentages",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // ========== VALIDATION METHODS ==========

    /**
     * Validate and parse status for template creation (only ACTIVE and DRAFT allowed)
     */
    private TemplateStatus validateAndParseStatusForCreation(String status) {
        if (status == null || status.trim().isEmpty()) {
            log.error("Status is required for template creation");
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Status is required",
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            TemplateStatus templateStatus = TemplateStatus.valueOf(status.trim().toUpperCase());

            if (templateStatus != TemplateStatus.ACTIVE && templateStatus != TemplateStatus.DRAFT) {
                log.error("Invalid status for template creation: {}. Only ACTIVE and DRAFT are allowed.", status);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        INVALID_STATUS + status + "'. Only ACTIVE and DRAFT statuses are allowed for template creation.",
                        HttpStatus.BAD_REQUEST
                );
            }

            return templateStatus;
        } catch (IllegalArgumentException ex) {
            log.error("Invalid status value: {}. Valid values are: ACTIVE, DRAFT", status);
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    INVALID_STATUS + status + "'. Valid values for creation are: ACTIVE, DRAFT",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * Validate and parse status for template update (all statuses allowed)
     */
    private TemplateStatus validateAndParseStatusForUpdate(String status) {
        if (status == null || status.trim().isEmpty()) {
            log.error("Status is required for template update");
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Status is required",
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            return TemplateStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.error("Invalid status value: {}. Valid values are: ACTIVE, INACTIVE, DRAFT", status);
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    INVALID_STATUS + status + "'. Valid values are: ACTIVE, INACTIVE, DRAFT",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * Validate template name uniqueness
     */
    private void validateTemplateNameUniqueness(String templateName) {
        try {
            if (superTemplateRepository.existsByTemplateName(templateName)) {
                log.error("Template name already exists: {}", templateName);
                throw new AAAException(
                        LogMessages.ERROR_DUPLICATE_TEMPLATE,
                        "Template name '" + templateName + "' already exists. Template name must be unique.",
                        HttpStatus.CONFLICT
                );
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during template name uniqueness validation for '{}': {}",
                    templateName, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while validating template name uniqueness",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Validate default flag - only one template can be default
     */
    private void validateDefaultFlag(Boolean isDefault) {
        try {
            if (Boolean.TRUE.equals(isDefault)) {
                boolean defaultExists = superTemplateRepository.existsByIsDefault(true);
                if (defaultExists) {
                    log.error("A default template already exists");
                    throw new AAAException(
                            LogMessages.ERROR_DUPLICATE_TEMPLATE,
                            "A default template already exists. Only one template can be set as default.",
                            HttpStatus.CONFLICT
                    );
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during default flag validation: {}", ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while validating default flag",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Validate child templates
     */
    private void validateChildTemplates(List<TemplateMessageRequest> templates) {
        try {
            for (TemplateMessageRequest template : templates) {

                if (MessageType.EXPIRE.name().equalsIgnoreCase(template.getMessageType())
                        && template.getDaysToExpire() == null) {

                    log.error("daysToExpire is mandatory for messageType EXPIRE");
                    throw new AAAException(
                            LogMessages.ERROR_VALIDATION_FAILED,
                            LogMessages.MSG_EXPIRE_DATE_MANDATORY,
                            HttpStatus.BAD_REQUEST
                    );
                }

                if (MessageType.USAGE.name().equalsIgnoreCase(template.getMessageType())) {
                    if (template.getQuotaPercentage() == null) {
                        log.error("quotaPercentage is mandatory for messageType USAGE");
                        throw new AAAException(
                                LogMessages.ERROR_VALIDATION_FAILED,
                                LogMessages.MSG_QUOTA_MANDATORY,
                                HttpStatus.BAD_REQUEST
                        );
                    }

                    if (template.getQuotaPercentage() < 0 || template.getQuotaPercentage() > 100) {
                        log.error("quotaPercentage must be between 0-100, received: {}", template.getQuotaPercentage());
                        throw new AAAException(
                                LogMessages.ERROR_VALIDATION_FAILED,
                                LogMessages.MSG_INVALID_QUOTA,
                                HttpStatus.BAD_REQUEST
                        );
                    }
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during child template validation: {}", ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while validating child templates",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Validate child templates for update
     */
    private void validateChildTemplatesForUpdate(List<TemplateMessageUpdateRequest> templates) {
        try {
            for (TemplateMessageUpdateRequest template : templates) {

                if (MessageType.EXPIRE.name().equalsIgnoreCase(template.getMessageType())
                        && template.getDaysToExpire() == null) {

                    log.error("daysToExpire is mandatory for messageType EXPIRE");
                    throw new AAAException(
                            LogMessages.ERROR_VALIDATION_FAILED,
                            LogMessages.MSG_EXPIRE_DATE_MANDATORY,
                            HttpStatus.BAD_REQUEST
                    );
                }

                if (MessageType.USAGE.name().equalsIgnoreCase(template.getMessageType())) {
                    if (template.getQuotaPercentage() == null) {
                        log.error("quotaPercentage is mandatory for messageType USAGE");
                        throw new AAAException(
                                LogMessages.ERROR_VALIDATION_FAILED,
                                LogMessages.MSG_QUOTA_MANDATORY,
                                HttpStatus.BAD_REQUEST
                        );
                    }

                    if (template.getQuotaPercentage() < 0 || template.getQuotaPercentage() > 100) {
                        log.error("quotaPercentage must be between 0-100, received: {}", template.getQuotaPercentage());
                        throw new AAAException(
                                LogMessages.ERROR_VALIDATION_FAILED,
                                LogMessages.MSG_INVALID_QUOTA,
                                HttpStatus.BAD_REQUEST
                        );
                    }
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during child template validation for update: {}", ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while validating child templates",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Build sort object with validation
     */
    private Sort buildSort(String sortBy, String sortDirection) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        if (!VALID_SORT_FIELDS.contains(sortBy)) {
            sortBy = "createdAt";
        }

        return Sort.by(direction, sortBy);
    }

    // ========== MAPPING METHODS ==========

    /**
     * Map CreateTemplateRequest to SuperTemplate entity
     */
    private SuperTemplate mapToSuperTemplateEntity(CreateTemplateRequest request, TemplateStatus status) {
        SuperTemplate superTemplate = new SuperTemplate();
        superTemplate.setTemplateName(request.getTemplateName());
        superTemplate.setStatus(status);
        superTemplate.setIsDefault(request.getIsDefault());
        superTemplate.setCreatedBy(request.getCreatedBy());
        return superTemplate;
    }

    /**
     * Map TemplateMessageRequest list to ChildTemplate entities
     */
    private List<ChildTemplate> mapToChildTemplateEntities(
            List<TemplateMessageRequest> templateMessages,
            Long superTemplateId) {

        try {
            List<ChildTemplate> childTemplates = new ArrayList<>();

            for (TemplateMessageRequest templateMessage : templateMessages) {
                ChildTemplate childTemplate = new ChildTemplate();
                childTemplate.setMessageType(templateMessage.getMessageType());
                childTemplate.setDaysToExpire(templateMessage.getDaysToExpire());
                childTemplate.setQuotaPercentage(templateMessage.getQuotaPercentage());
                childTemplate.setMessageContent(templateMessage.getMessageContent());
                childTemplate.setSuperTemplateId(superTemplateId);

                childTemplates.add(childTemplate);
            }

            return childTemplates;

        } catch (Exception ex) {
            log.error("Unexpected error while mapping child template entities for super template ID {}: {}",
                    superTemplateId, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while mapping child template entities",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Map SuperTemplate and ChildTemplates to CreateTemplateResponse
     */
    private CreateTemplateResponse mapToCreateTemplateResponse(
            SuperTemplate superTemplate,
            List<ChildTemplate> childTemplates) {

        try {
            CreateTemplateResponse response = new CreateTemplateResponse();
            response.setSuperTemplateId(superTemplate.getId());
            response.setTemplateName(superTemplate.getTemplateName());
            response.setStatus(superTemplate.getStatus().name());
            response.setIsDefault(superTemplate.getIsDefault());
            response.setCreatedBy(superTemplate.getCreatedBy());
            response.setCreatedAt(superTemplate.getCreatedAt());
            response.setUpdatedBy(superTemplate.getUpdatedBy());
            response.setUpdatedAt(superTemplate.getUpdatedAt());

            List<CreateTemplateResponse.TemplateMessageResponse> templateMessageResponses =
                    childTemplates.stream()
                            .map(this::mapToTemplateMessageResponse)
                            .toList();

            response.setTemplates(templateMessageResponses);

            return response;

        } catch (Exception ex) {
            log.error("Unexpected error while mapping to CreateTemplateResponse for super template ID {}: {}",
                    superTemplate.getId(), ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while mapping template response",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Map SuperTemplate and ChildTemplates to UpdateTemplateResponse
     */
    private UpdateTemplateResponse mapToUpdateTemplateResponse(
            SuperTemplate superTemplate,
            List<ChildTemplate> childTemplates) {

        try {
            UpdateTemplateResponse response = new UpdateTemplateResponse();
            response.setSuperTemplateId(superTemplate.getId());
            response.setTemplateName(superTemplate.getTemplateName());
            response.setStatus(superTemplate.getStatus().name());
            response.setIsDefault(superTemplate.getIsDefault());
            response.setCreatedBy(superTemplate.getCreatedBy());
            response.setCreatedAt(superTemplate.getCreatedAt());
            response.setUpdatedBy(superTemplate.getUpdatedBy());
            response.setUpdatedAt(superTemplate.getUpdatedAt());

            List<UpdateTemplateResponse.TemplateMessageResponse> templateMessageResponses =
                    childTemplates.stream()
                            .map(this::mapToUpdateTemplateMessageResponse)
                            .toList();

            response.setTemplates(templateMessageResponses);

            return response;

        } catch (Exception ex) {
            log.error("Unexpected error while mapping to UpdateTemplateResponse for super template ID {}: {}",
                    superTemplate.getId(), ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while mapping template update response",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Map ChildTemplate to TemplateMessageResponse (for CreateTemplateResponse)
     */
    private CreateTemplateResponse.TemplateMessageResponse mapToTemplateMessageResponse(ChildTemplate childTemplate) {
        try {
            CreateTemplateResponse.TemplateMessageResponse messageResponse =
                    new CreateTemplateResponse.TemplateMessageResponse();

            messageResponse.setChildTemplateId(childTemplate.getId());
            messageResponse.setMessageType(childTemplate.getMessageType());
            messageResponse.setDaysToExpire(childTemplate.getDaysToExpire());
            messageResponse.setQuotaPercentage(childTemplate.getQuotaPercentage());
            messageResponse.setMessageContent(childTemplate.getMessageContent());
            messageResponse.setCreatedAt(childTemplate.getCreatedAt());
            messageResponse.setUpdatedAt(childTemplate.getUpdatedAt());

            return messageResponse;

        } catch (Exception ex) {
            log.error("Unexpected error while mapping child template ID {} to response: {}",
                    childTemplate.getId(), ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while mapping template message response",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Map ChildTemplate to TemplateMessageResponse (for UpdateTemplateResponse)
     */
    private UpdateTemplateResponse.TemplateMessageResponse mapToUpdateTemplateMessageResponse(ChildTemplate childTemplate) {
        try {
            UpdateTemplateResponse.TemplateMessageResponse messageResponse =
                    new UpdateTemplateResponse.TemplateMessageResponse();

            messageResponse.setChildTemplateId(childTemplate.getId());
            messageResponse.setMessageType(childTemplate.getMessageType());
            messageResponse.setDaysToExpire(childTemplate.getDaysToExpire());
            messageResponse.setQuotaPercentage(childTemplate.getQuotaPercentage());
            messageResponse.setMessageContent(childTemplate.getMessageContent());
            messageResponse.setCreatedAt(childTemplate.getCreatedAt());
            messageResponse.setUpdatedAt(childTemplate.getUpdatedAt());

            return messageResponse;

        } catch (Exception ex) {
            log.error("Unexpected error while mapping child template ID {} to update response: {}",
                    childTemplate.getId(), ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while mapping template message update response",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Map SuperTemplate to TemplateFilterResponse
     */
    private TemplateFilterResponse mapToTemplateFilterResponse(SuperTemplate template) {
        return TemplateFilterResponse.builder()
                .superTemplateId(template.getId())
                .templateName(template.getTemplateName())
                .status(template.getStatus().name())
                .isDefault(template.getIsDefault())
                .createdBy(template.getCreatedBy())
                .createdAt(template.getCreatedAt())
                .updatedBy(template.getUpdatedBy())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

}