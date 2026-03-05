package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.VendorConfigRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PageDetails;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PaginatedResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.vendorconfig.VendorConfigResponse;
import com.axonect.aee.template.baseapp.domain.entities.dto.VendorConfig;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.specification.VendorConfigSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorConfigService {

    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String VENDOR_CONFIG_NOT_FOUND= "VENDOR_CONFIG_NOT_FOUND_CODE";
    private static final String VENDOR_ID_KEY = "vendorId";
    private static final String ATTRIBUTE_NAME_KEY = "attributeName";
    private static final String VENDOR_CONFIG_ID_NOT_FOUND = "Vendor config with ID %d not found";

    private final VendorConfigRepository repository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final EventMapper eventMapper;

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public VendorConfigResponse create(VendorConfigRequest request) {
        MDC.put(VENDOR_ID_KEY, request.getVendorId());
        MDC.put(ATTRIBUTE_NAME_KEY, request.getAttributeName());

        try {
            log.debug("Starting vendor config creation for vendorId: {}, attributeName: {}",
                    request.getVendorId(), request.getAttributeName());

            // -------------------------------
            //   DUPLICATE VALIDATION
            // -------------------------------
            validateDuplicateAttributeIdForVendor(request);
            validateDuplicateAttributeNameForVendor(request);

            // Build VendorConfig entity without saving to DB
            VendorConfig entity = mapRequestToEntity(request);

            // Generate ID from sequence
            Long generatedId = getNextSequenceValue();
            entity.setId(generatedId);

            // Set timestamps manually since @PrePersist won't fire
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedDate(now);
            entity.setCreatedBy(request.getCreatedBy());
            entity.setLastUpdatedDate(now);
            entity.setLastUpdatedBy(request.getCreatedBy());

            // -------------------------------
            //     PUBLISH KAFKA EVENTS
            // -------------------------------
            publishVendorConfigCreatedEvents(entity, request.getCreatedBy());

            VendorConfigResponse response = mapEntityToResponse(entity);
            log.debug("Vendor config created successfully: ID={}, vendorId={}",
                    entity.getId(), entity.getVendorId());

            return response;

        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create vendor config for vendorId: {} - Unexpected error: {}",
                    request.getVendorId(), e.getMessage(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_CREATION_ERROR_CODE",
                    "Failed to create vendor config",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Publish VendorConfig created events to Kafka
     */
    private void publishVendorConfigCreatedEvents(VendorConfig vendorConfig, String userName) {
        try {
            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toVendorConfigDBWriteEvent(
                    CREATE, vendorConfig, userName);
            PublishResult dbResult = kafkaEventPublisher.publishVendorConfigDBWriteEvent(dbEvent);

            // Check if events published successfully
            if (dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing vendor config creation events for vendorId '{}'",
                        vendorConfig.getVendorId());
                throw new AAAException(
                        "VENDOR_CONFIG_CREATION_ERROR_CODE",
                        "Failed to publish vendor config created events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            // Log warnings if one cluster failed
            if (!dbResult.isDcSuccess()) {
                log.warn("Failed to publish some vendor config creation events to DC cluster for vendorId '{}'",
                        vendorConfig.getVendorId());
            }

            log.info("Vendor config created events published successfully: vendorId={}",
                    vendorConfig.getVendorId());

        } catch (Exception e) {
            log.error("Failed to publish vendor config created events for vendorId '{}'",
                    vendorConfig.getVendorId(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_CREATION_ERROR_CODE",
                    "Failed to publish vendor config created events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // -------------------------------
    // SEQUENCE GENERATION
    // -------------------------------

    /**
     * Get next sequence value for VendorConfig ID
     */
    private Long getNextSequenceValue() {
        try {
            Number nextVal = (Number) entityManager.createNativeQuery(
                    "SELECT VENDOR_CONFIG_SEQ.NEXTVAL FROM DUAL"
            ).getSingleResult();
            return nextVal.longValue();
        } catch (Exception e) {
            log.error("Failed to get next sequence value for VendorConfig", e);
            throw new AAAException(
                    "VENDOR_CONFIG_CREATION_ERROR_CODE",
                    "Failed to generate VendorConfig ID",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // -------------------------------
    // VALIDATION METHODS
    // -------------------------------

    /**
     * Validates that the attributeId is unique for the given vendorId
     */
    private void validateDuplicateAttributeIdForVendor(VendorConfigRequest request) {
        if (repository.existsByVendorIdAndAttributeId(
                request.getVendorId(), request.getAttributeId())) {

            log.warn("Duplicate attributeId {} found for vendorId: {}",
                    request.getAttributeId(), request.getVendorId());

            throw new AAAException(
                    "VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_ID_CODE",
                    String.format("Attribute ID '%s' already exists for vendor '%s'",
                            request.getAttributeId(), request.getVendorId()),
                    HttpStatus.CONFLICT
            );
        }
    }


    /**
     * Validates that the attributeName is unique for the given vendorId
     */
    private void validateDuplicateAttributeNameForVendor(VendorConfigRequest request) {
        if (repository.existsByVendorIdAndAttributeName(request.getVendorId(), request.getAttributeName())) {
            log.warn("Duplicate attributeName '{}' found for vendorId: {}",
                    request.getAttributeName(), request.getVendorId());
            throw new AAAException(
                    "VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_NAME_CODE",
                    String.format("Attribute name '%s' already exists for vendor '%s'",
                            request.getAttributeName(), request.getVendorId()),
                    HttpStatus.CONFLICT
            );
        }
    }

    // -------------------------------
    // MAPPING METHODS
    // -------------------------------

    private VendorConfig mapRequestToEntity(VendorConfigRequest request) {
        VendorConfig entity = new VendorConfig();
        entity.setVendorId(request.getVendorId());
        entity.setVendorName(request.getVendorName());
        entity.setAttributeName(request.getAttributeName());
        entity.setAttributeId(request.getAttributeId());
        entity.setValuePath(request.getValuePath());
        entity.setEntity(request.getEntity());
        entity.setDataType(request.getDataType());
        entity.setParameter(request.getParameter());
        entity.setIsActive(request.getIsActive());
        entity.setAttributePrefix(request.getAttributePrefix());
        return entity;
    }

    private VendorConfigResponse mapEntityToResponse(VendorConfig entity) {
        VendorConfigResponse response = new VendorConfigResponse();
        response.setId(entity.getId());
        response.setVendorId(entity.getVendorId());
        response.setVendorName(entity.getVendorName());
        response.setAttributeName(entity.getAttributeName());
        response.setAttributeId(entity.getAttributeId());
        response.setValuePath(entity.getValuePath());
        response.setEntity(entity.getEntity());
        response.setDataType(entity.getDataType());
        response.setParameter(entity.getParameter());
        response.setIsActive(entity.getIsActive());
        response.setAttributePrefix(entity.getAttributePrefix());
        response.setCreatedDate(entity.getCreatedDate());
        response.setCreatedBy(entity.getCreatedBy());
        response.setLastUpdatedDate(entity.getLastUpdatedDate());
        response.setLastUpdatedBy(entity.getLastUpdatedBy());
        return response;
    }

    @Transactional
    public VendorConfigResponse update(VendorConfigUpdateRequest request) {
        MDC.put(VENDOR_ID_KEY, request.getVendorId());
        MDC.put(ATTRIBUTE_NAME_KEY, request.getAttributeName());

        try {
            log.debug("Starting vendor config update for ID: {} with vendorId: {}",
                    request.getId(), request.getVendorId());

            // Fetch existing config for validation
            VendorConfig existing = getExistingConfig(request.getId());

            // Validate vendorId cannot be changed
            validateVendorIdUnchanged(existing, request);

            // Validate duplicate attributeId for the vendor (excluding current record)
            validateDuplicateAttributeIdOnUpdate(existing, request);

            // Validate duplicate attributeName for the vendor (excluding current record)
            validateDuplicateAttributeNameOnUpdate(existing, request);

            // Apply updates
            applyUpdates(existing, request);

            // Set updated audit fields manually
            existing.setLastUpdatedDate(LocalDateTime.now());
            existing.setLastUpdatedBy(request.getUpdatedBy());

            // -------------------------------
            //     PUBLISH KAFKA EVENTS
            // -------------------------------
            publishVendorConfigUpdatedEvents(existing, request.getUpdatedBy());

            log.debug("Vendor config updated successfully: ID={}, vendorId={}",
                    existing.getId(), existing.getVendorId());

            return mapEntityToResponse(existing);

        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update vendor config ID: {}", request.getId(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_UPDATE_ERROR_CODE",
                    String.format("Failed to update vendor config with ID %d", request.getId()),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Publish VendorConfig updated events to Kafka
     */
    private void publishVendorConfigUpdatedEvents(VendorConfig vendorConfig, String userName) {
        try {
            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toVendorConfigDBWriteEvent(
                    UPDATE, vendorConfig, userName);
            PublishResult dbResult = kafkaEventPublisher.publishVendorConfigDBWriteEvent(dbEvent);

            // Check if events published successfully
            if (dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing vendor config update events for vendorId '{}'",
                        vendorConfig.getVendorId());
                throw new AAAException(
                        "VENDOR_CONFIG_UPDATE_ERROR_CODE",
                        "Failed to publish vendor config update events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            log.info("Vendor config updated events published successfully: vendorId={}",
                    vendorConfig.getVendorId());

        } catch (Exception e) {
            log.error("Failed to publish vendor config updated events for vendorId '{}'",
                    vendorConfig.getVendorId(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_UPDATE_ERROR_CODE",
                    "Failed to publish vendor config updated events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

// -------------------------------
// VALIDATION METHODS FOR UPDATE
// -------------------------------

    private VendorConfig getExistingConfig(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AAAException(
                        VENDOR_CONFIG_NOT_FOUND,
                        String.format(VENDOR_CONFIG_ID_NOT_FOUND, id),
                        HttpStatus.NOT_FOUND
                ));
    }

    /**
     * Validates that vendorId cannot be changed during update
     */
    private void validateVendorIdUnchanged(VendorConfig existing, VendorConfigUpdateRequest request) {
        if (!existing.getVendorId().equals(request.getVendorId())) {
            log.warn("Attempt to change vendorId from '{}' to '{}' for ID: {}",
                    existing.getVendorId(), request.getVendorId(), existing.getId());
            throw new AAAException(
                    "VENDOR_CONFIG_UPDATE_MISMATCH_CODE",
                    String.format("Vendor ID cannot be changed. Cannot update vendor ID for config ID %d",
                            request.getId()),
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * Validates that the new attributeId is unique for the vendor (excluding current record)
     */
    private void validateDuplicateAttributeIdOnUpdate(VendorConfig existing, VendorConfigUpdateRequest request) {
        // If attributeId hasn't changed, no need to check
        if (existing.getAttributeId().equals(request.getAttributeId())) {
            return;
        }

        // Check if another record with same vendorId and attributeId exists
        repository.findByVendorIdAndAttributeId(
                        request.getVendorId(), request.getAttributeId())
                .filter(config -> !config.getId().equals(existing.getId()))
                .ifPresent(config -> {
                    log.warn("Duplicate attributeId {} found for vendorId: {} (existing record ID: {})",
                            request.getAttributeId(), request.getVendorId(), config.getId());

                    throw new AAAException(
                            "VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_ID_CODE",
                            String.format("Attribute ID '%s' already exists for vendor '%s'",
                                    request.getAttributeId(), request.getVendorId()),
                            HttpStatus.CONFLICT
                    );
                });
    }

    /**
     * Validates that the new attributeName is unique for the vendor (excluding current record)
     */
    private void validateDuplicateAttributeNameOnUpdate(VendorConfig existing, VendorConfigUpdateRequest request) {
        // If attributeName hasn't changed, no need to check
        if (existing.getAttributeName().equals(request.getAttributeName())) {
            return;
        }

        // Check if another record with same vendorId and attributeName exists
        repository.findByVendorIdAndAttributeName(request.getVendorId(), request.getAttributeName())
                .filter(config -> !config.getId().equals(existing.getId()))
                .ifPresent(config -> {
                    log.warn("Duplicate attributeName '{}' found for vendorId: {} (existing record ID: {})",
                            request.getAttributeName(), request.getVendorId(), config.getId());
                    throw new AAAException(
                            "VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_NAME_CODE",
                            String.format("Attribute name '%s' already exists for vendor '%s'",
                                    request.getAttributeName(), request.getVendorId()),
                            HttpStatus.CONFLICT
                    );
                });
    }

    /**
     * Apply updates to the existing entity
     */
    private void applyUpdates(VendorConfig existing, VendorConfigUpdateRequest request) {
        // Update all editable fields
        existing.setVendorName(request.getVendorName());
        existing.setAttributeName(request.getAttributeName());
        existing.setAttributeId(request.getAttributeId());
        existing.setValuePath(request.getValuePath());
        existing.setEntity(request.getEntity());
        existing.setDataType(request.getDataType());
        existing.setParameter(request.getParameter());
        existing.setIsActive(request.getIsActive());
        existing.setAttributePrefix(request.getAttributePrefix());
    }

    @Transactional
    public String delete(Long id) {
        try {
            log.debug("Starting vendor config deletion for ID: {}", id);

            // Fetch VendorConfig for validation
            VendorConfig vendorConfig = repository.findById(id)
                    .orElseThrow(() -> {
                        log.warn("Vendor config with ID {} not found", id);
                        return new AAAException(
                                VENDOR_CONFIG_NOT_FOUND,
                                String.format(VENDOR_CONFIG_ID_NOT_FOUND, id),
                                HttpStatus.NOT_FOUND
                        );
                    });

            MDC.put(VENDOR_ID_KEY, vendorConfig.getVendorId());
            MDC.put(ATTRIBUTE_NAME_KEY, vendorConfig.getAttributeName());


            // -------------------------------
            //     PUBLISH KAFKA EVENTS (instead of deleting from DB)
            // -------------------------------
            publishVendorConfigDeletedEvents(vendorConfig, vendorConfig.getLastUpdatedBy() != null ?
                    vendorConfig.getLastUpdatedBy() : vendorConfig.getCreatedBy());

            log.debug("Vendor config deleted successfully: ID={}, vendorId={}",
                    id, vendorConfig.getVendorId());

            return String.format("Vendor config %d deleted successfully", id);

        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to delete vendor config with ID: {}", id, e);
            throw new AAAException(
                    "VENDOR_CONFIG_DELETION_ERROR_CODE",
                    String.format("Failed to delete vendor config with ID %d", id),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Publish VendorConfig deleted events to Kafka
     */
    private void publishVendorConfigDeletedEvents(VendorConfig vendorConfig, String userName) {
        try {
            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toVendorConfigDBWriteEvent(
                    DELETE, vendorConfig, userName);
            PublishResult dbResult = kafkaEventPublisher.publishVendorConfigDBWriteEvent(dbEvent);

            // Check if events published successfully
            if (dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing vendor config deletion events for vendorId '{}'",
                        vendorConfig.getVendorId());
                throw new AAAException(
                        "VENDOR_CONFIG_DELETION_ERROR_CODE",
                        "Failed to publish vendor config deletion events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            log.info("Vendor config deleted events published successfully: vendorId={}",
                    vendorConfig.getVendorId());

        } catch (Exception e) {
            log.error("Failed to publish vendor config deleted events for vendorId '{}'",
                    vendorConfig.getVendorId(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_DELETION_ERROR_CODE",
                    "Failed to publish vendor config deleted events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Search/filter vendor configs with pagination
     *
     * @param filter Filter parameters
     * @return PaginatedResponse containing list of VendorConfigResponse objects
     */
    /**
     * Search/filter vendor configs with pagination
     *
     * @param filter Filter parameters
     * @return PaginatedResponse containing list of VendorConfigResponse objects
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<VendorConfigResponse> searchVendorConfig(VendorConfigFilterRequest filter) {
        log.info("Searching vendor configs with filters: {}", filter);

        try {
            // Build specification
            Specification<VendorConfig> spec = VendorConfigSpecification.filterVendorConfig(filter);

            // Build pageable with sorting (convert page number from 1-based to 0-based)
            int pageIndex = filter.getPage() > 0 ? filter.getPage() - 1 : 0;
            Sort sort = buildSort(filter.getSortBy(), filter.getSortDirection());
            Pageable pageable = PageRequest.of(pageIndex, filter.getSize(), sort);

            // Execute query
            Page<VendorConfig> page = repository.findAll(spec, pageable);

            // Map to response
            List<VendorConfigResponse> content = page.getContent().stream()
                    .map(this::mapEntityToResponse)
                    .toList();

            PageDetails pageDetails = new PageDetails(
                    page.getTotalElements(),
                    filter.getPage(),  // Return the original 1-based page number
                    page.getNumberOfElements()
            );

            log.info("Found {} vendor config records", page.getTotalElements());

            return PaginatedResponse.<VendorConfigResponse>builder()
                    .vendorConfigData(content)
                    .pageDetails(pageDetails)
                    .build();

        } catch (Exception ex) {
            log.error("Error searching vendor configs: {}", ex.getMessage(), ex);
            throw new AAAException(
                    "VENDOR_CONFIG_SEARCH_ERROR",
                    "Failed to search vendor config records",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Build sort object based on field and direction
     */
    private Sort buildSort(String sortBy, String sortDirection) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ?
                Sort.Direction.ASC : Sort.Direction.DESC;

        // Default to createdDate if sortBy is null or blank
        String sortField = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdDate";

        return Sort.by(direction, sortField);
    }

    /**
     * Get a vendor config by ID
     */
    @Transactional(readOnly = true)
    public VendorConfigResponse getById(Long id) {
        log.debug("Fetching vendor config with ID: {}", id);

        try {
            VendorConfig entity = repository.findById(id)
                    .orElseThrow(() -> {
                        log.warn("Vendor config with ID {} not found", id);
                        return new AAAException(
                                VENDOR_CONFIG_NOT_FOUND,
                                String.format(VENDOR_CONFIG_ID_NOT_FOUND, id),
                                HttpStatus.NOT_FOUND
                        );
                    });

            VendorConfigResponse response = mapEntityToResponse(entity);
            log.debug("Successfully fetched vendor config: ID={}, vendorId={}, attributeName={}",
                    entity.getId(), entity.getVendorId(), entity.getAttributeName());

            return response;

        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch vendor config with ID: {}", id, e);
            throw new AAAException(
                    "VENDOR_CONFIG_FETCH_ERROR",
                    String.format("Failed to fetch vendor config with ID %d", id),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Get all vendor configs by vendor ID
     */
    @Transactional(readOnly = true)
    public List<VendorConfigResponse> getByVendorId(String vendorId) {
        log.debug("Fetching vendor configs for vendorId: {}", vendorId);

        try {
            // Fetch all configs for this vendor, sorted by attributeName
            List<VendorConfig> configs = repository.findByVendorId(
                    vendorId,
                    Sort.by(ATTRIBUTE_NAME_KEY).ascending()
            );

            if (configs.isEmpty()) {
                log.warn("No vendor configs found for vendorId: {}", vendorId);
                throw new AAAException(
                        VENDOR_CONFIG_NOT_FOUND,
                        String.format("No vendor configs found for vendor ID '%s'", vendorId),
                        HttpStatus.NOT_FOUND
                );
            }

            List<VendorConfigResponse> responses = configs.stream()
                    .map(this::mapEntityToResponse)
                    .toList();

            log.info("Successfully fetched {} vendor configs for vendorId: {}",
                    responses.size(), vendorId);

            return responses;

        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch vendor configs for vendorId: {}", vendorId, e);
            throw new AAAException(
                    "VENDOR_CONFIG_FETCH_ERROR",
                    String.format("Failed to fetch vendor configs for vendor ID '%s'", vendorId),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public record VendorSummary(String vendorId, String vendorName) {}

    @Transactional(readOnly = true)
    public List<VendorSummary> getVendorList() {
        log.debug("Fetching distinct vendor ID and name list");
        try {
            return repository.findDistinctVendorIdAndName()
                    .stream()
                    .map(row -> new VendorSummary((String) row[0], (String) row[1]))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch vendor list: {}", e.getMessage(), e);
            throw new AAAException(
                    "VENDOR_CONFIG_FETCH_ERROR",
                    "Failed to fetch vendor list",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
}