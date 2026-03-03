package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.BngRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.BngCreateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.BngFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.BngUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import com.axonect.aee.template.baseapp.domain.events.BngEvent;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.specification.BngSpecification;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
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
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BngService {

    private static final String ACTIVE = "Active";
    private static final String INACTIVE = "Inactive";
    private static final String ALREADY_EXIST = " already exists";
    private static final String NOT_FOUND = "' not found";
    private static final String BNG_ID_KEY = "bngId";
    private static final String BNG_NAME_KEY = "bngName";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final List<String> VALID_SORT_FIELDS = Arrays.asList(
            BNG_ID_KEY, BNG_NAME_KEY, "bngIp", "status",
            "createdDate", "updatedDate"
    );

    private final BngRepository bngRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final EventMapper eventMapper;

    @Transactional
    public CreateBngResponse createBng(BngCreateRequest request) {
        MDC.put(BNG_NAME_KEY, request.getBngName());
        MDC.put(BNG_ID_KEY, request.getBngId());

        try {
            log.info(LogMessages.BNG_CREATING, request.getBngName(), request.getBngId());

            validateDuplicateBngId(request);
            validateDuplicateBngName(request);
            validateStatus(request.getStatus());

            // Build BNG entity without saving to DB
            BngEntity bngEntity = mapToEntity(request);
            bngEntity.setCreatedDate(LocalDateTime.now());

            // Publish to Kafka instead of saving to DB
            publishBngCreatedEvents(bngEntity);

            log.info(LogMessages.BNG_CREATED, bngEntity.getBngName(), bngEntity.getBngId());

            return mapToCreateBngResponse(bngEntity);

        } catch (AAAException ex) {
            log.error(LogMessages.BNG_VALIDATION_ERROR, ex.getMessage());
            throw ex;
        } finally {
            MDC.clear();
        }
    }

    @Transactional
    public UpdateBngResponse updateBng(String bngId, BngUpdateRequest request) {
        MDC.put(BNG_ID_KEY, bngId);

        try {
            log.info(LogMessages.BNG_UPDATING, bngId);

            // Fetch BNG for validation (still need to verify it exists)
            BngEntity bng = bngRepository.findById(bngId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.BNG_NOT_FOUND,
                            "BNG '" + bngId + NOT_FOUND,
                            HttpStatus.NOT_FOUND
                    ));

            MDC.put(BNG_NAME_KEY, bng.getBngName());

            // Apply updates
            validateAndApplyUpdates(bng, request);
            bng.setUpdatedDate(LocalDateTime.now());
            bng.setUpdatedBy(request.getUpdatedBy());

            // Publish to Kafka instead of saving to DB
            publishBngUpdatedEvents(bng);

            log.info(LogMessages.BNG_UPDATED, bngId);

            return mapToUpdateBngResponse(bng);

        } catch (AAAException ex) {
            log.error(LogMessages.BNG_VALIDATION_ERROR, ex.getMessage());
            throw ex;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Publish BNG created events to Kafka
     */
    private void publishBngCreatedEvents(BngEntity bng) {
        try {
            /*// Publish BngEvent
            BngEvent bngEvent = eventMapper.toBngEvent(bng);
            PublishResult eventResult = kafkaEventPublisher.publishBngEvent("BNG_CREATED", bngEvent);*/

            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toBngDBWriteEvent(CREATE, bng);
            PublishResult dbResult = kafkaEventPublisher.publishBngDBWriteEvent(dbEvent);

            // Check if both events published successfully
            if (dbResult.isCompleteFailure() || dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing BNG creation events for BNG '{}'", bng.getBngId());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Failed to publish BNG created events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            // Log warnings if one cluster failed
            if (!dbResult.isDcSuccess() || !dbResult.isDcSuccess()) {
                log.warn("Failed to publish some BNG creation events to DC cluster for BNG '{}'", bng.getBngId());
            }

        } catch (Exception e) {
            log.error("Failed to publish BNG created events for '{}'", bng.getBngId(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish BNG created events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish BNG updated events to Kafka
     */
    private void publishBngUpdatedEvents(BngEntity bng) {
        try {
            /*// Publish BngEvent
            BngEvent bngEvent = eventMapper.toBngEvent(bng);
            PublishResult eventResult = kafkaEventPublisher.publishBngEvent("BNG_UPDATED", bngEvent);*/

            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toBngDBWriteEvent(UPDATE, bng);
            PublishResult dbResult = kafkaEventPublisher.publishBngDBWriteEvent(dbEvent);

            // Check if both events published successfully
            if (dbResult.isCompleteFailure() || dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing BNG update events for BNG '{}'", bng.getBngId());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Failed to publish BNG update events to Kafka",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

        } catch (Exception e) {
            log.error("Failed to publish BNG updated events for '{}'", bng.getBngId(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish BNG updated events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Publish BNG deleted events to Kafka
     *//*
    private void publishBngDeletedEvents(BngEntity bng) {
        try {
            // Publish BngEvent
            BngEvent bngEvent = eventMapper.toBngEvent(bng);
            kafkaEventPublisher.publishBngEvent("BNG_DELETED", bngEvent);

            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toBngDBWriteEvent(DELETE, bng);
            kafkaEventPublisher.publishBngDBWriteEvent(dbEvent);

        } catch (Exception e) {
            log.error("Failed to publish BNG deleted events for '{}'", bng.getBngId(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish BNG deleted events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
*/
    /**
     * Get a simple list of all BNG names and IPs without pagination.
     *
     * @return List of BngListResponse containing bngName and bngIp
     */
    @Transactional(readOnly = true)
    public List<BngListResponse> getBngList() {
        log.info("Retrieving all BNG names and IPs");

        try {
            List<BngEntity> allBngs = bngRepository.findAll(Sort.by(Sort.Direction.ASC, BNG_NAME_KEY));

            List<BngListResponse> response = allBngs.stream()
                    .map(bng -> BngListResponse.builder()
                            .bngName(bng.getBngName())
                            .bngIp(bng.getBngIp())
                            .build())
                    .toList();


            log.info("Retrieved {} BNG records", response.size());

            return response;

        } catch (Exception ex) {
            log.error("Error retrieving BNG list: {}", ex.getMessage(), ex);
            throw new AAAException(
                    "BNG_LIST_ERROR",
                    "Error retrieving BNG list",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void validateDuplicateBngId(BngCreateRequest request) {
        if (bngRepository.existsByBngId(request.getBngId())) {
            log.warn(LogMessages.BNG_DUPLICATE_ID, request.getBngId());
            throw new AAAException(
                    LogMessages.BNG_DUPLICATE,
                    "BNG ID '" + request.getBngId() + "'" + ALREADY_EXIST,
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateDuplicateBngName(BngCreateRequest request) {
        if (bngRepository.existsByBngName(request.getBngName())) {
            log.warn(LogMessages.BNG_DUPLICATE_NAME, request.getBngName());
            throw new AAAException(
                    LogMessages.BNG_DUPLICATE,
                    "BNG name '" + request.getBngName() + "'" + ALREADY_EXIST,
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new AAAException(
                    LogMessages.VALIDATION_FAILED,
                    LogMessages.MSG_STATUS_MANDATORY,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (!ACTIVE.equalsIgnoreCase(status) && !INACTIVE.equalsIgnoreCase(status)) {
            throw new AAAException(
                    LogMessages.VALIDATION_FAILED,
                    LogMessages.MSG_STATUS_INVALID,
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void validateAndApplyUpdates(BngEntity bng, BngUpdateRequest request) {
        updateBngIp(bng, request);
        updateBngTypeVendor(bng, request);
        updateModelVersion(bng, request);
        updateNasIpAddress(bng, request);
        updateNasIdentifier(bng, request);
        updateCoaIp(bng, request);
        updateCoaPort(bng, request);
        updateSharedSecret(bng, request);
        updateLocation(bng, request);
        updateStatus(bng, request);
    }

    private void updateBngIp(BngEntity bng, BngUpdateRequest request) {
        if (request.getBngIp() != null && !request.getBngIp().isBlank()) {
            bng.setBngIp(request.getBngIp());
        }
    }

    private void updateBngTypeVendor(BngEntity bng, BngUpdateRequest request) {
        if (request.getBngTypeVendor() != null && !request.getBngTypeVendor().isBlank()) {
            bng.setBngTypeVendor(request.getBngTypeVendor());
        }
    }

    private void updateModelVersion(BngEntity bng, BngUpdateRequest request) {
        if (request.getModelVersion() != null && !request.getModelVersion().isBlank()) {
            bng.setModelVersion(request.getModelVersion());
        }
    }

    private void updateNasIpAddress(BngEntity bng, BngUpdateRequest request) {
        if (request.getNasIpAddress() != null && !request.getNasIpAddress().isBlank()) {
            bng.setNasIpAddress(request.getNasIpAddress());
        }
    }

    private void updateNasIdentifier(BngEntity bng, BngUpdateRequest request) {
        if (request.getNasIdentifier() != null && !request.getNasIdentifier().isBlank()) {
            bng.setNasIdentifier(request.getNasIdentifier());
        }
    }

    private void updateCoaIp(BngEntity bng, BngUpdateRequest request) {
        if (request.getCoaIp() != null && !request.getCoaIp().isBlank()) {
            bng.setCoaIp(request.getCoaIp());
        }
    }

    private void updateCoaPort(BngEntity bng, BngUpdateRequest request) {
        if (request.getCoaPort() != null) {
            bng.setCoaPort(request.getCoaPort());
        }
    }

    private void updateSharedSecret(BngEntity bng, BngUpdateRequest request) {
        if (request.getSharedSecret() != null && !request.getSharedSecret().isBlank()) {
            bng.setSharedSecret(request.getSharedSecret());
        }
    }

    private void updateLocation(BngEntity bng, BngUpdateRequest request) {
        if (request.getLocation() != null && !request.getLocation().isBlank()) {
            bng.setLocation(request.getLocation());
        }
    }

    private void updateStatus(BngEntity bng, BngUpdateRequest request) {
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            validateStatus(request.getStatus());
            bng.setStatus(request.getStatus());
        }
    }

    private BngEntity mapToEntity(BngCreateRequest request) {
        return BngEntity.builder()
                .bngId(request.getBngId())
                .bngName(request.getBngName())
                .bngIp(request.getBngIp())
                .bngTypeVendor(request.getBngTypeVendor())
                .modelVersion(request.getModelVersion())
                .nasIpAddress(request.getNasIpAddress())
                .nasIdentifier(request.getNasIdentifier())
                .coaIp(request.getCoaIp())
                .coaPort(request.getCoaPort())
                .sharedSecret(request.getSharedSecret())
                .location(request.getLocation())
                .status(request.getStatus())
                .createdBy(request.getCreatedBy())
                .build();
    }

    private CreateBngResponse mapToCreateBngResponse(BngEntity bng) {
        return CreateBngResponse.builder()
                .bngId(bng.getBngId())
                .bngName(bng.getBngName())
                .bngIp(bng.getBngIp())
                .bngTypeVendor(bng.getBngTypeVendor())
                .modelVersion(bng.getModelVersion())
                .nasIpAddress(bng.getNasIpAddress())
                .nasIdentifier(bng.getNasIdentifier())
                .coaIp(bng.getCoaIp())
                .coaPort(bng.getCoaPort())
                .sharedSecret(bng.getSharedSecret())
                .location(bng.getLocation())
                .status(bng.getStatus())
                .createdBy(bng.getCreatedBy())
                .createdDate(bng.getCreatedDate() != null ?
                        LocalDateTime.parse(bng.getCreatedDate().toString()) : null)
                .build();
    }

    private UpdateBngResponse mapToUpdateBngResponse(BngEntity bng) {
        return UpdateBngResponse.builder()
                .bngId(bng.getBngId())
                .bngName(bng.getBngName())
                .bngIp(bng.getBngIp())
                .bngTypeVendor(bng.getBngTypeVendor())
                .modelVersion(bng.getModelVersion())
                .nasIpAddress(bng.getNasIpAddress())
                .nasIdentifier(bng.getNasIdentifier())
                .coaIp(bng.getCoaIp())
                .coaPort(bng.getCoaPort())
                .sharedSecret(bng.getSharedSecret())
                .location(bng.getLocation())
                .status(bng.getStatus())
                .createdBy(bng.getCreatedBy())
                .updatedBy(bng.getUpdatedBy())
                .createdDate(bng.getCreatedDate() != null ?
                        LocalDateTime.parse(bng.getCreatedDate().toString()) : null)
                .updatedDate(bng.getUpdatedDate() != null ?
                        LocalDateTime.parse(bng.getUpdatedDate().toString()) : null)
                .build();
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<BngFilterResponse> searchBng(BngFilterRequest filter) {
        log.info(LogMessages.BNG_SEARCHING, filter);

        try {
            // Build specification
            Specification<BngEntity> spec = BngSpecification.filterBng(filter);

            // Build pageable with sorting (convert page number from 1-based to 0-based)
            int pageIndex = filter.getPage() > 0 ? filter.getPage() - 1 : 0;
            Sort sort = buildSort(filter.getSortBy(), filter.getSortDirection());
            Pageable pageable = PageRequest.of(pageIndex, filter.getSize(), sort);

            // Execute query
            Page<BngEntity> page = bngRepository.findAll(spec, pageable);

            // Map to response
            List<BngFilterResponse> content = page.getContent().stream()
                    .map(this::mapToBngFilterResponse)
                    .toList();

            PageDetails pageDetails = new PageDetails(
                    page.getTotalElements(),
                    filter.getPage(),  // Return the original 1-based page number
                    page.getNumberOfElements()
            );

            log.info(LogMessages.BNG_FOUND_RECORDS, page.getTotalElements());

            return PaginatedResponse.<BngFilterResponse>builder()
                    .bngData(content)
                    .pageDetails(pageDetails)
                    .build();

        } catch (Exception ex) {
            log.error(LogMessages.BNG_ERROR_SEARCHING, ex.getMessage(), ex);
            throw new AAAException(
                    LogMessages.SEARCH_ERROR,
                    LogMessages.MSG_BNG_SEARCH_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private Sort buildSort(String sortBy, String sortDirection) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Validate sortBy field to prevent injection
        if (!VALID_SORT_FIELDS.contains(sortBy)) {
            sortBy = "createdDate";
        }

        return Sort.by(direction, sortBy);
    }

    private BngFilterResponse mapToBngFilterResponse(BngEntity bng) {
        return BngFilterResponse.builder()
                .bngId(bng.getBngId())
                .bngName(bng.getBngName())
                .bngIp(bng.getBngIp())
                .bngTypeVendor(bng.getBngTypeVendor())
                .modelVersion(bng.getModelVersion())
                .nasIpAddress(bng.getNasIpAddress())
                .nasIdentifier(bng.getNasIdentifier())
                .coaIp(bng.getCoaIp())
                .coaPort(bng.getCoaPort())
                .sharedSecret(bng.getSharedSecret())
                .location(bng.getLocation())
                .status(bng.getStatus())
                .createdBy(bng.getCreatedBy())
                .updatedBy(bng.getUpdatedBy())
                .createdDate(bng.getCreatedDate())
                .updatedDate(bng.getUpdatedDate())
                .build();
    }

    @Transactional(readOnly = true)
    public GetBngResponse getBngById(String bngId) {
        MDC.put(BNG_ID_KEY, bngId);

        try {
            log.info(LogMessages.BNG_RETRIEVING_BY_ID, bngId);

            BngEntity bng = bngRepository.findById(bngId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.BNG_NOT_FOUND,
                            "BNG with ID '" + bngId + NOT_FOUND,
                            HttpStatus.NOT_FOUND
                    ));

            log.info(LogMessages.BNG_RETRIEVED, bngId);

            return mapToGetBngResponse(bng);

        } catch (AAAException ex) {
            log.error(LogMessages.BNG_ERROR_RETRIEVING, ex.getMessage());
            throw ex;
        } finally {
            MDC.clear();
        }
    }

    private GetBngResponse mapToGetBngResponse(BngEntity bng) {
        return GetBngResponse.builder()
                .bngId(bng.getBngId())
                .bngName(bng.getBngName())
                .bngIp(bng.getBngIp())
                .bngTypeVendor(bng.getBngTypeVendor())
                .modelVersion(bng.getModelVersion())
                .nasIpAddress(bng.getNasIpAddress())
                .nasIdentifier(bng.getNasIdentifier())
                .coaIp(bng.getCoaIp())
                .coaPort(bng.getCoaPort())
                .sharedSecret(bng.getSharedSecret())
                .location(bng.getLocation())
                .status(bng.getStatus())
                .createdBy(bng.getCreatedBy())
                .updatedBy(bng.getUpdatedBy())
                .createdDate(bng.getCreatedDate())
                .updatedDate(bng.getUpdatedDate())
                .build();
    }

    @Transactional(readOnly = true)
    public GetBngResponse getBngByName(String bngName) {
        MDC.put(BNG_NAME_KEY, bngName);

        try {
            log.info(LogMessages.BNG_RETRIEVING_BY_NAME, bngName);

            BngEntity bng = bngRepository.findByBngName(bngName)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.BNG_NOT_FOUND,
                            "BNG with name '" + bngName + NOT_FOUND,
                            HttpStatus.NOT_FOUND
                    ));

            MDC.put(BNG_ID_KEY, bng.getBngId());
            log.info(LogMessages.BNG_RETRIEVED_WITH_ID, bngName, bng.getBngId());

            return mapToGetBngResponse(bng);

        } catch (AAAException ex) {
            log.error(LogMessages.BNG_ERROR_RETRIEVING, ex.getMessage());
            throw ex;
        } finally {
            MDC.clear();
        }
    }

    /**
     * Retrieve a BNG by both ID and Name.
     * Both parameters must match the same BNG record.
     *
     * @param bngId The BNG identifier
     * @param bngName The BNG name
     * @return GetBngResponse containing the BNG details
     * @throws AAAException if no matching BNG found or if ID and Name don't match
     */
    @Transactional(readOnly = true)
    public GetBngResponse getBngByIdAndName(String bngId, String bngName) {
        MDC.put(BNG_ID_KEY, bngId);
        MDC.put(BNG_NAME_KEY, bngName);

        try {
            log.info("Retrieving BNG with ID '{}' and Name '{}'", bngId, bngName);

            BngEntity bng = bngRepository.findById(bngId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.BNG_NOT_FOUND,
                            "BNG with ID '" + bngId + NOT_FOUND,
                            HttpStatus.NOT_FOUND
                    ));

            // Validate that the name matches
            if (!bng.getBngName().equals(bngName)) {
                log.warn("BNG ID '{}' found but name '{}' does not match the provided name '{}'",
                        bngId, bng.getBngName(), bngName);
                throw new AAAException(
                        LogMessages.BNG_NOT_FOUND,
                        "BNG with ID '" + bngId + "' and Name '" + bngName + NOT_FOUND,
                        HttpStatus.NOT_FOUND
                );
            }

            log.info("BNG retrieved successfully with ID '{}' and Name '{}'", bngId, bngName);

            return mapToGetBngResponse(bng);

        } catch (AAAException ex) {
            log.error(LogMessages.BNG_ERROR_RETRIEVING, ex.getMessage());
            throw ex;
        } finally {
            MDC.clear();
        }
    }
}