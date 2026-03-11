package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.config.NotificationPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateUserRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateUserRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.Plan;
import com.axonect.aee.template.baseapp.domain.adapter.AsyncAdaptorInterface;
import com.axonect.aee.template.baseapp.domain.algorithm.EncryptionPlugin;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.enums.EncryptionMethod;
import com.axonect.aee.template.baseapp.domain.enums.Subscription;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class UserProvisioningService {
    private final BucketInstanceRepository bucketInstanceRepository;

    private static final String PPPOE = "PPPoE";
    private static final String IPOE = "IPoE";
    private static final String STATIC = "static";
    private static final String DYNAMIC = "Dynamic";
    private static final String BILLING_CYCLE = "3";
    private static final String BILLING_DAILY = "1";
    private static final String BILLING_MONTHLY = "2";
    private static final String USERNAME = "userName";
    private static final String USERID = "userId";
    private static final String CREATE = "CREATE";
    private static final String DELETE = "DELETE";
    private static final String AAA_USER = "AAA_USER";
    private static final String USER_NAME = "USER_NAME";
    private static final String AAA_USER_MAC = "AAA_USER_MAC_ADDRESS";
    private static final String INVALID_ENCRYPTION_METHOD = "Invalid encryption_method : ";
    private static final String VALID_ENCRYPTION_METHODS = " Valid values : 0 (PLAIN), 1 (MD5), 2 (CSG_ADL)";
    private static final String REQUEST_ID_ALREADY_EXIST = "request_id '{}' already exists for another user";
    private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private static final String ALREADY_EXISTS = "' already exists";
    private static final String MSG_TYPE_USER_CREATION = "USER_CREATION";
    private static final String MSG_TYPE_USER_UPDATE   = "USER_UPDATE";
    private static final String MSG_TYPE_USER_DELETION = "USER_DELETION";// ← ADD




    private final UserRepository userRepository;
    private final EncryptionPlugin encryptionPlugin;
    private final AsyncAdaptorInterface asyncAdaptor;
    private final SuperTemplateRepository superTemplateRepository;
    private final UserToMacRepository userToMacRepository;
    private final KafkaEventPublisher kafkaEventPublisher;  // ADD THIS
    private final EventMapper eventMapper;  // ADD THIS
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final CoAManagementService coaManagementService;
    private final NotificationGenerator notificationGenerator;
    private final NotificationPublisher notificationPublisher;

    @Value("${app.default.group-id}")
    private String defaultGroupId;

    @Value("${app.encryption.algorithm:AES}") // Add this
    private String encryptionAlgorithm;

    @Value("${app.encryption.secret-key}") // Add this
    private String encryptionSecretKey;

    public UserProvisioningService(
            UserRepository userRepository,
            UserToMacRepository userToMacRepository,
            EncryptionPlugin encryptionPlugin,
            AsyncAdaptorInterface asyncAdaptor,
            SuperTemplateRepository superTemplateRepository,
            KafkaEventPublisher kafkaEventPublisher,  // ADD THIS
            EventMapper eventMapper, ServiceInstanceRepository serviceInstanceRepository, CoAManagementService coaManagementService, NotificationGenerator notificationGenerator, NotificationPublisher notificationPublisher,
            BucketInstanceRepository bucketInstanceRepository) {  // ADD THIS
        this.userRepository = userRepository;
        this.encryptionPlugin = encryptionPlugin;
        this.asyncAdaptor = asyncAdaptor;
        this.superTemplateRepository = superTemplateRepository;
        this.userToMacRepository = userToMacRepository;
        this.kafkaEventPublisher = kafkaEventPublisher;  // ADD THIS
        this.eventMapper = eventMapper;  // ADD THIS
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.coaManagementService = coaManagementService;
        this.notificationGenerator = notificationGenerator;
        this.notificationPublisher = notificationPublisher;
        this.bucketInstanceRepository = bucketInstanceRepository;
    }

    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        MDC.put(USERNAME, request.getUserName());
        long methodStart = System.currentTimeMillis();

        try {
            log.info(LogMessages.USER_CREATE_REQUEST, request.getUserName());

            asyncValidate(request);
            validateGroupBillingConsistency(request);
            Long templateId = getTemplateIdOrDefault(request);
            validateEncryptionMethod(request);
            validateContactNumbers(request);
            validateContactEmails(request);
            validateNasPortType(request);
            validateIpAllocationValue(request);
            validateNetworkRules(request);
            validateBillingRules(request);
            validateConcurrencyAndStatus(request);

            String groupId = (request.getGroupId() == null || request.getGroupId().isBlank())
                    ? defaultGroupId
                    : request.getGroupId();

            validateBandwidthForGroup(request, groupId);

            //     BUILD user entity (don't save to DB)
            UserEntity user = mapToEntity(request, groupId, templateId);

            //     Generate userId manually (since @PrePersist won't run)
            user.setUserId(generateUserId());
            user.setCreatedDate(LocalDateTime.now());

            //     Set MAC addresses in transient field
            if (request.getMacAddress() != null && !request.getMacAddress().isBlank()) {
                user.setMacAddress(request.getMacAddress());
            }

            //     Fetch template name
            if (user.getTemplateId() != null) {
                superTemplateRepository.findById(user.getTemplateId()).ifPresent(template -> user.setTemplateName(template.getTemplateName()));
            }

            //  PUBLISH TO KAFKA
            publishUserCreatedEvents(user);
            sendUserCreationNotification(user);


            MDC.put(USERID, user.getUserId());
            log.info(LogMessages.USER_CREATED, user.getUserName(), user.getUserId());

            return mapToCreateUserResponse(user);

        } catch (AAAException ex) {
            log.error("Validation error received: {}", ex.getMessage());
            throw ex;
        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for createUser('{}') = {} ms",
                    request.getUserName(), totalDuration);
            MDC.clear();
        }
    }

    // ADD: Manual userId generation (since @PrePersist won't run)
    /**
     * Generates a non-cryptographic user reference ID.
     * <p>
     * This identifier is for business/display purposes only
     * and is not used for authentication or authorization.
     */
    @SuppressWarnings("java:S2245")
    private String generateUserId() {
        long timestamp = System.currentTimeMillis();
        int random = ThreadLocalRandom.current().nextInt(10_000);
        return "USR" + timestamp + String.format("%04d", random);
    }


    @SneakyThrows
    private void asyncValidate(CreateUserRequest request) {
        validateDuplicateUserAndRequestId(request);
        validateMacAddresses(request);
    }
    private void validateDuplicateUserAndRequestId(CreateUserRequest request) throws Exception {
        CompletableFuture<Object>[] checks = asyncAdaptor.supplyAll(
                6000L,
                () -> userRepository.existsByUserName(request.getUserName()),
                () -> userRepository.existsByRequestId(request.getRequestId())
        );

        if ((boolean) checks[0].get()) {
            log.warn(LogMessages.DUPLICATE_USER, request.getUserName());
            throw new AAAException(
                    LogMessages.ERROR_DUPLICATE_USER,
                    "User '" + request.getUserName() + ALREADY_EXISTS,
                    HttpStatus.CONFLICT
            );
        }

        if ((boolean) checks[1].get()) {
            log.warn(LogMessages.DUPLICATE_REQUEST_ID, request.getRequestId());
            throw new AAAException(
                    LogMessages.ERROR_DUPLICATE_USER,
                    "Request ID '" + request.getRequestId() + ALREADY_EXISTS,
                    HttpStatus.CONFLICT
            );
        }
    }
    private void validateMacAddresses(CreateUserRequest request) {
        String macAddress = request.getMacAddress();
        if (macAddress == null || macAddress.isBlank()) {
            return;
        }

        Set<String> normalizedMacs = new HashSet<>();
        List<String> macsToCheck = new ArrayList<>();

        parseAndValidateMacs(macAddress, normalizedMacs, macsToCheck);
        validateMacsAgainstDatabase(macAddress, macsToCheck);
    }
    private void parseAndValidateMacs(
            String macAddress,
            Set<String> normalizedMacs,
            List<String> macsToCheck
    ) {
        for (String rawMac : macAddress.split(",")) {
            String mac = rawMac.trim();
            if (mac.isEmpty()) {
                continue;
            }

            validateMacFormat(mac);

            String normalizedMac = normalizeMacAddress(mac);
            validateDuplicateMacInRequest(mac, normalizedMacs, normalizedMac);

            normalizedMacs.add(normalizedMac);
            macsToCheck.add(normalizedMac);
        }
    }
    private void validateDuplicateMacInRequest(
            String mac,
            Set<String> normalizedMacs,
            String normalizedMac
    ) {
        boolean isDuplicate = normalizedMacs.stream()
                .anyMatch(existing -> existing.equalsIgnoreCase(normalizedMac));

        if (isDuplicate) {
            log.error("Duplicate MAC address found in request: {}", mac);
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Duplicate MAC address found in request: " + mac,
                    HttpStatus.BAD_REQUEST
            );
        }
    }
    private void validateMacsAgainstDatabase(String macAddress, List<String> macsToCheck) {
        if (macsToCheck.isEmpty()) {
            return;
        }

        List<UserToMac> existingMacRecords =
                userToMacRepository.findByMacAddressIn(macsToCheck);

        for (UserToMac existingRecord : existingMacRecords) {
            for (String checkMac : macsToCheck) {
                if (existingRecord.getMacAddress().equalsIgnoreCase(checkMac)) {
                    String duplicateMac =
                            findOriginalMacFormat(macAddress, checkMac);

                    throw new AAAException(
                            LogMessages.ERROR_VALIDATION_FAILED,
                            "MAC address '" + duplicateMac + ALREADY_EXISTS,
                            HttpStatus.CONFLICT
                    );
                }
            }
        }
    }



    // Helper method to find original MAC format from normalized
    private String findOriginalMacFormat(String originalMacString, String normalizedMac) {
        for (String mac : originalMacString.split(",")) {
            mac = mac.trim();
            if (normalizeMacAddress(mac).equalsIgnoreCase(normalizedMac)) {  //     Already using equalsIgnoreCase
                return mac;
            }
        }
        return normalizedMac; // fallback
    }


    @Transactional(readOnly = true)
    public GetUserResponse getUserByUserName(String userName) {
        MDC.put(USERNAME, userName);
        long methodStart = System.currentTimeMillis();

        try {
            log.info("Retrieving user with username: {}", userName);

            UserEntity user = userRepository.findByUserName(userName)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            String.format("User '%s' not found", userName),
                            HttpStatus.NOT_FOUND
                    ));

            // Fetch MAC addresses
            List<UserToMac> macAddresses = userToMacRepository.findByUserName(userName);
            if (!macAddresses.isEmpty()) {
                String macString = macAddresses.stream()
                        .map(UserToMac::getOriginalMacAddress)
                        .collect(Collectors.joining(", "));
                user.setMacAddress(macString);
            }

            // Fetch template if exists
            if (user.getTemplateId() != null) {
                SuperTemplate template = superTemplateRepository.findById(user.getTemplateId())
                        .orElse(null);
                if (template != null) {
                    user.setTemplateName(template.getTemplateName());
                }
            }

            MDC.put(USERID, user.getUserId());
            log.info("User '{}' successfully retrieved", userName);

            return mapToGetResponse(user);

        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for getUserByUserName('{}') = {} ms", userName, totalDuration);
            MDC.clear();
        }
    }


    @Transactional(readOnly = true)
    public PagedUserResponse getAllUsers(Integer page, Integer pageSize, Integer status,
                                         String userName, Integer subscription, String groupId) {

        long methodStart = System.currentTimeMillis();
        log.info(LogMessages.RETRIEVE_ALL_USERS, page, pageSize, status, userName, subscription, groupId);

        try {
            int pageIndex = Math.max(page - 1, 0);
            Pageable pageable = PageRequest.of(pageIndex, pageSize);

            Specification<UserEntity> spec = Specification.where(null);

            if (status != null) {
                UserStatus statusEnum = parseStatus(status);
                spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), statusEnum));
            }

            if (userName != null && !userName.trim().isEmpty()) {
                spec = spec.and((root, query, cb) ->
                        cb.like(cb.lower(root.get(USERNAME)), "%" + userName.toLowerCase() + "%"));
            }

            if (groupId != null && !groupId.trim().isEmpty()) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("groupId"), groupId));
            }

            if (subscription != null) {
                Subscription subscriptionEnum = parseSubscription(subscription);
                spec = spec.and((root, query, cb) -> cb.equal(root.get("subscription"), subscriptionEnum));
            }

            // --- DB Query Timing ---
            long dbStart = System.currentTimeMillis();
            Page<UserEntity> userPage = userRepository.findAll(spec, pageable);
            long dbDuration = System.currentTimeMillis() - dbStart;
            log.info("DB query for getAllUsers completed in {} ms", dbDuration);

            // --- Batch fetch template names (single query instead of N+1) ---
            List<Long> templateIds = userPage.getContent().stream()
                    .map(UserEntity::getTemplateId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();

            if (!templateIds.isEmpty()) {
                Map<Long, String> templateNameMap = superTemplateRepository.findByIdIn(templateIds)
                        .stream()
                        .collect(Collectors.toMap(SuperTemplate::getId, SuperTemplate::getTemplateName));

                for (UserEntity user : userPage.getContent()) {
                    if (user.getTemplateId() != null) {
                        user.setTemplateName(templateNameMap.get(user.getTemplateId()));
                    }
                }
            }

            // --- Mapping Timing ---
            long mapStart = System.currentTimeMillis();
            List<UserResponse> users = userPage.getContent().stream()
                    .map(this::mapToResponse)
                    .toList();
            long mapDuration = System.currentTimeMillis() - mapStart;
            log.info("Mapping UserEntity → UserResponse for {} records took {} ms", users.size(), mapDuration);

            return PagedUserResponse.builder()
                    .users(users)
                    .totalRecords(userPage.getTotalElements())
                    .page(page)
                    .pageSize(pageSize)
                    .build();

        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for getAllUsers = {} ms", totalDuration);
        }
    }

    /**
     * Validates billing and cycle date consistency for users in the same group (excluding groupId = 1)
     */
    private void validateGroupBillingConsistency(CreateUserRequest request) {
        // Skip validation for default group (groupId = 1)
        if ("1".equals(request.getGroupId())) {
            log.debug("Skipping billing consistency validation for default groupId: 1");
            return;
        }

        // Skip if groupId is not provided (defaults to 1)
        if (!StringUtils.hasText(request.getGroupId())) {
            log.debug("No groupId provided, skipping billing consistency validation");
            return;
        }

        log.debug("Validating billing consistency for groupId: {}", request.getGroupId());

        // Find any existing user in the same group
        Optional<UserEntity> existingGroupUser = userRepository.findFirstByGroupId(request.getGroupId());

        if (existingGroupUser.isEmpty()) {
            // First user in this group - no validation needed
            log.debug("First user in groupId: {}, no billing validation required", request.getGroupId());
            return;
        }

        UserEntity referenceUser = existingGroupUser.get();

        // Compare billing values
        if (!referenceUser.getBilling().equals(request.getBilling())) {
            log.error("Billing mismatch for groupId: {}. Expected: {}, Provided: {}",
                    request.getGroupId(), referenceUser.getBilling(), request.getBilling());
            throw new AAAException(
                    LogMessages.USER_VALIDATION_ERROR_CODE,
                    String.format("Billing value mismatch for group %s. All users in the same group must have the same billing type. Expected: %s, Provided: %s",
                            request.getGroupId(), referenceUser.getBilling(), request.getBilling()),
                    HttpStatus.BAD_REQUEST
            );
        }

        // Validate cycle_date for ALL billing types (not just billing = 3)
        // Compare cycle dates - both must be null or both must match
        if (!Objects.equals(referenceUser.getCycleDate(), request.getCycleDate())) {
            log.error("Cycle date mismatch for groupId: {}. Expected: {}, Provided: {}",
                    request.getGroupId(), referenceUser.getCycleDate(), request.getCycleDate());
            throw new AAAException(
                    LogMessages.USER_VALIDATION_ERROR_CODE,
                    String.format("Cycle date mismatch for group %s. All users in the same group must have the same cycle date. Expected: %s, Provided: %s",
                            request.getGroupId(), referenceUser.getCycleDate(), request.getCycleDate()),
                    HttpStatus.BAD_REQUEST
            );
        }

        log.info("Billing consistency validated successfully for groupId: {}", request.getGroupId());
    }

    /**
     * Validates and updates billing/cycle_date for all users in a group when groupId is being updated
     */
    private void validateAndUpdateGroupBilling(UserEntity currentUser, UpdateUserRequest request) {

        // Determine the groupId to use: from request if provided, otherwise use current user's groupId
        String effectiveGroupId = StringUtils.hasText(request.getGroupId())
                ? request.getGroupId()
                : currentUser.getGroupId();

        if (shouldSkipGroupBilling(effectiveGroupId)) {
            return;
        }

        List<UserEntity> groupUsers = userRepository.findAllByGroupId(effectiveGroupId);

        if (groupUsers.isEmpty()) {
            log.debug("No existing users found for groupId: {}, proceeding with update", effectiveGroupId);
            return;
        }

        UserEntity referenceUser = groupUsers.get(0);
        BillingInfo billingInfo = resolveBillingInfo(request, referenceUser);

        applyBillingToCurrentUser(currentUser, billingInfo);

        if (shouldUpdateEntireGroup(request)) {
            updateGroupUsers(groupUsers, effectiveGroupId, billingInfo, request);
        }
    }
/* ---------------------------------------------------
   Helper Methods – reduce cognitive complexity
--------------------------------------------------- */

    private boolean shouldSkipGroupBilling(String groupId) {

        if (!StringUtils.hasText(groupId)) {
            log.debug("No valid groupId found, skipping group billing update");
            return true;
        }

        if ("1".equals(groupId)) {
            log.debug("Skipping group billing update for default groupId: 1");
            return true;
        }

        log.info("Processing group billing update for groupId: {}", groupId);
        return false;
    }

    private BillingInfo resolveBillingInfo(UpdateUserRequest request, UserEntity referenceUser) {

        String billing = request.getBilling() != null
                ? request.getBilling()
                : referenceUser.getBilling();

        Integer cycleDate = request.getCycleDate() != null
                ? request.getCycleDate()
                : referenceUser.getCycleDate();

        return new BillingInfo(billing, cycleDate);
    }

    private void applyBillingToCurrentUser(UserEntity currentUser, BillingInfo billingInfo) {
        currentUser.setBilling(billingInfo.getBilling());
        currentUser.setCycleDate(billingInfo.getCycleDate());
    }

    private boolean shouldUpdateEntireGroup(UpdateUserRequest request) {
        return request.getBilling() != null || request.getCycleDate() != null;
    }

    private void updateGroupUsers(
            List<UserEntity> groupUsers,
            String groupId,
            BillingInfo billingInfo,
            UpdateUserRequest request) {

        log.info(
                "Publishing billing/cycle_date updates for all {} users in groupId: {}. New billing: {}, New cycle_date: {}",
                groupUsers.size(), groupId, billingInfo.getBilling(), billingInfo.getCycleDate()
        );

        int publishedCount = 0;

        for (UserEntity user : groupUsers) {
            if (updateUserBilling(user, billingInfo, request)) {
                user.setUpdatedDate(LocalDateTime.now());

                // PUBLISH TO KAFKA INSTEAD OF SAVING TO DB
                try {
                    DBWriteRequestGeneric dbEvent = eventMapper.toDBWriteEvent("UPDATE", user, AAA_USER);
                    PublishResult result = kafkaEventPublisher.publishDBWriteEvent(dbEvent);

                    if (!result.isCompleteFailure()) {
                        publishedCount++;
                    } else {
                        log.error("Failed to publish billing update for user: {}", user.getUserName());
                    }
                } catch (Exception e) {
                    log.error("Error publishing billing update for user: {}", user.getUserName(), e);
                }
            }
        }

        log.info("Successfully published billing/cycle_date updates for {} users in groupId: {}", publishedCount, groupId);
    }

    private boolean updateUserBilling(
            UserEntity user,
            BillingInfo billingInfo,
            UpdateUserRequest request) {

        boolean updated = false;

        if (request.getBilling() != null && !billingInfo.getBilling().equals(user.getBilling())) {
            user.setBilling(billingInfo.getBilling());
            updated = true;
        }

        if (request.getCycleDate() != null &&
                !Objects.equals(billingInfo.getCycleDate(), user.getCycleDate())) {
            user.setCycleDate(billingInfo.getCycleDate());
            updated = true;
        }

        if (updated) {
            log.debug(
                    "Updated billing/cycle_date for user: {} in groupId: {}",
                    user.getUserName(), user.getGroupId()
            );
        }

        return updated;
    }

    public ServiceLineResponse getServiceDetailsByUsername(String userName) {
        long start = System.currentTimeMillis();
        try {
            ServiceLineResponse response = new ServiceLineResponse();
            UserEntity user = userRepository.findByUserName(userName)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            String.format("User '%s' not found", userName),
                            HttpStatus.NOT_FOUND
                    ));

            response.setServiceLineNumber(user.getUserName());
            response.setCategory(String.valueOf(user.getSubscription()));
            response.setCurrentStatus(user.getStatus() != null ? String.valueOf(user.getStatus().getCode()) : null);

            List<String> userNameList = new ArrayList<>();
            userNameList.add(userName);
            if (!user.getGroupId().equalsIgnoreCase("1")) {
                userNameList.add(user.getGroupId());
            }

            List<BucketFlatProjection> bucketSummaryList = bucketInstanceRepository.findFlatBucketDetailsByUsernames(userNameList);
            List<Plan> planSummaryList = mapPlanSummaryList(bucketSummaryList);
            response.setPlans(planSummaryList);

            log.info(LogMessages.SERVICE_TERMINATION, System.currentTimeMillis() - start, "User Service Summary Retrieved Successfully");
            return response;
        } catch (AAAException e) {
            throw e;
        } catch (Exception ex) {
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<Plan> mapPlanSummaryList(List<BucketFlatProjection> bucketSummaryList) {
        return bucketSummaryList.stream()
                .map(bucket -> Plan.builder()
                        .planName(bucket.getPlanName())
                        .priority(bucket.getPriority() != null
                                ? bucket.getPriority().intValue()
                                : null)
                        .status(mapStatusToCode(bucket.getServiceStatus()))   // mapped to integer
                        .planType(bucket.getPlanType())
                        .recurringMode(bucket.getRecurringPeriod())
                        .isGroup(bucket.getIsGroup())
                        .groupId(Boolean.TRUE.equals(bucket.getIsGroup())
                                ? bucket.getUsername()
                                : null)
                        .quota(Quota.builder()
                                .totalQuota(bucket.getInitialBalance())
                                .utilizedQuota(bucket.getUsage())
                                .remainingQuota(bucket.getCurrentBalance())
                                .build())
                        .allocatedBandwidth(AllocatedBandwidth.builder()
                                .downlink(bucket.getDownLink())
                                .uplink(bucket.getUpLink())
                                .build())
                        .build())
                .collect(Collectors.toList());
    }

    private Integer mapStatusToCode(String status) {
        if (status == null) return null;
        return switch (status.toLowerCase()) {
            case "active"   -> 1;
            case "draft"    -> 2;
            case "inactive" -> 3;
            default         -> null;
        };
    }

/* ---------------------------------------------------
   Simple DTO used internally
--------------------------------------------------- */

    @Getter
    @AllArgsConstructor
    private static class BillingInfo {
        private final String billing;
        private final Integer cycleDate;
    }



    @Transactional
    public UpdateUserResponse updateUser(String userName, UpdateUserRequest request) {
        MDC.put(USERNAME, userName);
        long methodStart = System.currentTimeMillis();

        try {
            log.info(LogMessages.USER_UPDATE_REQUEST, userName);

            // Fetch user for validation
            UserEntity user = userRepository.findByUserName(userName)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            String.format("User '%s' not found", userName),
                            HttpStatus.NOT_FOUND
                    ));

            MDC.put(USERID, user.getUserId());

            // All validations (including status with CoA)
            asyncValidateUpdate(user, request);
            validateAndApplyUpdates(user, request); // ← This handles status + CoA
            user.setUpdatedDate(LocalDateTime.now());

            // Set MAC addresses in transient field if updated
            if (request.getMacAddress() != null) {
                user.setMacAddress(request.getMacAddress());
            } else {
                // Fetch existing MACs for response
                enrichUserWithMacAddresses(user);
            }

            // Publish to Kafka
            publishUserUpdatedEvents(user);
            sendUserUpdateNotification(user);

            // Fetch template name
            if (user.getTemplateId() != null) {
                SuperTemplate template = superTemplateRepository.findById(user.getTemplateId())
                        .orElse(null);
                if (template != null) {
                    user.setTemplateName(template.getTemplateName());
                }
            }

            UpdateUserResponse response = mapToUpdateResponse(user);
            log.info(LogMessages.USER_UPDATED, userName);
            return response;

        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for updateUser('{}') = {} ms", userName, totalDuration);
            MDC.clear();
        }
    }

    @SneakyThrows
    private void asyncValidateUpdate(UserEntity user, UpdateUserRequest request) {
        // Always validate request ID if it's being changed
        if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
            validateRequestIdForUpdate(user, request);
        }

        // Validate MAC addresses if provided
        if (request.getMacAddress() != null && !request.getMacAddress().isBlank()) {
            validateMacAddressesForUpdate(user, request);
        }
    }


    @SneakyThrows
    private void validateMacAddressesForUpdate(UserEntity user, UpdateUserRequest request) {
        if (isMacAddressEmpty(request)) {
            return;
        }

        Set<String> normalizedMacs = new HashSet<>();
        List<String> macsToCheck = new ArrayList<>();

        for (String mac : extractAndValidateMacs(request, normalizedMacs)) {
            macsToCheck.add(mac);
        }

        checkMacsAgainstDatabase(user, request, macsToCheck);
    }
    private boolean isMacAddressEmpty(UpdateUserRequest request) {
        return request.getMacAddress() == null || request.getMacAddress().isBlank();
    }
    private List<String> extractAndValidateMacs(
            UpdateUserRequest request,
            Set<String> normalizedMacs) {

        List<String> result = new ArrayList<>();

        for (String rawMac : request.getMacAddress().split(",")) {
            String mac = rawMac.trim();
            if (mac.isEmpty()) {
                continue;
            }

            validateMacFormat(mac);

            String normalizedMac = normalizeMacAddress(mac);
            validateDuplicateInRequest(mac, normalizedMacs, normalizedMac);

            normalizedMacs.add(normalizedMac);
            result.add(normalizedMac);
        }

        return result;
    }
    private void validateMacFormat(String mac) {
        if (!MAC_PATTERN.matcher(mac).matches()) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Invalid MAC address format: " + mac,
                    HttpStatus.BAD_REQUEST
            );
        }
    }
    private void validateDuplicateInRequest(
            String originalMac,
            Set<String> normalizedMacs,
            String normalizedMac) {

        boolean duplicate = normalizedMacs.stream()
                .anyMatch(existing -> existing.equalsIgnoreCase(normalizedMac));

        if (duplicate) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Duplicate MAC address found in request: " + originalMac,
                    HttpStatus.BAD_REQUEST
            );
        }
    }
    private void checkMacsAgainstDatabase(
            UserEntity user,
            UpdateUserRequest request,
            List<String> macsToCheck) {

        if (macsToCheck.isEmpty()) {
            return;
        }

        List<UserToMac> existingMacRecords =
                userToMacRepository.findByMacAddressInAndUserNameNot(
                        macsToCheck,
                        user.getUserName()
                );

        for (UserToMac userToMac : existingMacRecords) {
            for (String checkMac : macsToCheck) {
                if (userToMac.getMacAddress().equalsIgnoreCase(checkMac)) {
                    String duplicateMac =
                            findOriginalMacFormat(request.getMacAddress(), checkMac);

                    throw new AAAException(
                            LogMessages.ERROR_VALIDATION_FAILED,
                            "MAC address '" + duplicateMac + "' already exists for another user",
                            HttpStatus.CONFLICT
                    );
                }
            }
        }

    }




    @Transactional
    public DeleteUserResponse deleteUser(String userName, String requestId) {
        MDC.put(USERNAME, userName);
        long methodStart = System.currentTimeMillis();

        try {
            log.info("Starting cascade delete for user: {}", userName);

            //  Fetch user for validation
            UserEntity user = userRepository.findByUserName(userName)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            "User '" + userName + "' not found",
                            HttpStatus.NOT_FOUND
                    ));

            MDC.put(USERID, user.getUserId());

            // Enrich with MAC addresses for Kafka event
            enrichUserWithMacAddresses(user);

            // CASCADE DELETE: Service Instances & Bucket Instances
            publishCascadeDeleteEvents(userName);

            // PUBLISH USER DELETE EVENT TO KAFKA
            publishUserDeletedEvents(user);
            sendUserDeletionNotification(user);


            log.info("User '{}' and all related data delete events published successfully", userName);

            return DeleteUserResponse.builder()
                    .requestId(requestId)
                    .status("success")
                    .message(String.format("User '%s' and all related services deletion events published successfully.", userName))
                    .build();

        } catch (AAAException ex) {
            log.error("Error during cascade delete for user '{}': {}", userName, ex.getMessage());
            throw ex;
        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for cascade deleteUser('{}') = {} ms", userName, totalDuration);
            MDC.clear();
        }
    }
    /**
     * Publishes delete events for all service instances and bucket instances related to a user
     */
    private void publishCascadeDeleteEvents(String userName) {
        try {
            log.info("Starting cascade delete events for user: {}", userName);

            // Step 1: Fetch all service instances for the user
            List<ServiceInstance> serviceInstances = serviceInstanceRepository.findByUsername(userName);

            if (serviceInstances.isEmpty()) {
                log.info("No service instances found for user: {}", userName);
            } else {
                log.info("Found {} service instance(s) for user: {}", serviceInstances.size(), userName);

                // Step 2: For each service instance, delete its bucket instances first
                for (ServiceInstance service : serviceInstances) {
                    // Delete bucket instances for this service
                    publishBucketInstanceDeleteEvents(service.getId(), userName);
                }

                // Step 3: Delete all service instances
                publishServiceInstanceDeleteEvents(userName, serviceInstances.size());
            }

        } catch (Exception e) {
            log.error("Failed to publish cascade delete events for user '{}'", userName, e);
            // Don't throw - allow user deletion to proceed even if service cleanup fails
        }
    }

    /**
     * Publishes delete events for all bucket instances of a service
     */
    private void publishBucketInstanceDeleteEvents(Long serviceId, String userName) {
        try {
            log.debug("Publishing bucket instance delete events for serviceId: {}", serviceId);

            // Publish DELETE event for all buckets of this service
            DBWriteRequestGeneric bucketEvent = DBWriteRequestGeneric.builder()
                    .eventType(DELETE)
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN)))
                    .userName(userName)
                    .tableName("BUCKET_INSTANCE")
                    .whereConditions(Map.of("SERVICE_ID", serviceId))
                    .build();

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(bucketEvent);

            if (result.isCompleteFailure()) {
                log.error("Failed to publish bucket delete events for serviceId: {}", serviceId);
            } else {
                log.debug("Successfully published bucket delete events for serviceId: {}", serviceId);
            }

        } catch (Exception e) {
            log.error("Error publishing bucket delete events for serviceId: {}", serviceId, e);
        }
    }

    /**
     * Publishes delete event for all service instances of a user
     */
    private void publishServiceInstanceDeleteEvents(String userName, int serviceCount) {
        try {
            log.debug("Publishing service instance delete events for user: {}, count: {}", userName, serviceCount);

            // Publish single DELETE event for all services of this user
            DBWriteRequestGeneric serviceEvent = DBWriteRequestGeneric.builder()
                    .eventType(DELETE)
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN)))
                    .userName(userName)
                    .tableName("SERVICE_INSTANCE")
                    .whereConditions(Map.of("USERNAME", userName))
                    .build();

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(serviceEvent);

            if (result.isCompleteFailure()) {
                log.error("Failed to publish service instance delete events for user: {}", userName);
            } else {
                log.info("Successfully published delete events for {} service instance(s) for user: {}",
                        serviceCount, userName);
            }

        } catch (Exception e) {
            log.error("Error publishing service instance delete events for user: {}", userName, e);
        }
    }


    @Transactional(readOnly = true)
    public PagedGroupUsersResponse getUsersByGroupId(String groupId, Integer page, Integer pageSize, Integer status) {

        long methodStart = System.currentTimeMillis();
        log.info(LogMessages.RETRIEVE_GROUP_USERS, groupId, page, pageSize, status);

        int pageIndex = (page != null && page > 0) ? page - 1 : 0;
        int size = (pageSize != null && pageSize > 0) ? pageSize : 20;
        Pageable pageable = PageRequest.of(pageIndex, size);

        try {

            // --- DB Fetch Timing ---
            long dbStart = System.currentTimeMillis();
            Page<UserEntity> userPage;

            if (status != null) {
                UserStatus statusEnum = parseStatus(status);
                userPage = userRepository.findByGroupIdAndStatus(groupId, statusEnum, pageable);

                long dbDuration = System.currentTimeMillis() - dbStart;
                log.info("DB query (groupId={}, status={}) completed in {} ms with {} records",
                        groupId, status, dbDuration, userPage.getTotalElements());
            } else {
                userPage = userRepository.findByGroupId(groupId, pageable);

                long dbDuration = System.currentTimeMillis() - dbStart;
                log.info("DB query (groupId={}) completed in {} ms with {} records",
                        groupId, dbDuration, userPage.getTotalElements());
            }

            // --- Mapping Timing ---
            long mapStart = System.currentTimeMillis();
            List<PagedGroupUsersResponse.GroupUserInfo> users =
                    userPage.getContent().stream()
                            .map(this::mapToGroupUserInfo)
                            .toList();
            long mapDuration = System.currentTimeMillis() - mapStart;

            log.info("Mapping UserEntity → GroupUserInfo for {} records took {} ms",
                    users.size(), mapDuration);

            // Build response
            return PagedGroupUsersResponse.builder()
                    .groupId(groupId)
                    .page(page)
                    .pageSize(pageSize)
                    .totalUsers(userPage.getTotalElements())
                    .users(users)
                    .build();

        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for getUsersByGroupId(groupId={}) = {} ms", groupId, totalDuration);
        }
    }

// 1. With separators: 00:1A:2B:3C:4D:5E or AA-BB-CC-DD-EE-FF
// 2. Without separators: 123456789ABC
// Supports:
// - With colons: 00:1A:2B:3C:4D:5E
// - With hyphens: 00-1A-2B-3C-4D-5E
// - With dots (Cisco): 001A.2B3C.4D5E
// - Without separators: 001A2B3C4D5E
// CORRECTED MAC_PATTERN - Add this to UserProvisioningService.java

    private static final Pattern MAC_PATTERN = Pattern.compile(
            "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$|" +        // XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX
                    "^([0-9A-Fa-f]{2} ){5}[0-9A-Fa-f]{2}$|" +          // XX XX XX XX XX XX
                    "^[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}$|" + // XXXX.XXXX.XXXX (Cisco)
                    "^[0-9A-Fa-f]{12}$"                                // XXXXXXXXXXXX
    );


    private void publishUserCreatedEvents(UserEntity user) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toDBWriteEvent(CREATE, user, AAA_USER);

            // Bundle MAC writes as relatedWrites instead of separate events
            if (user.getMacAddress() != null && !user.getMacAddress().isBlank()) {
                List<DBWriteRequestGeneric> macWrites = buildMacCreateEvents(user.getUserName(), user.getMacAddress());
                mainEvent.setRelatedWrites(macWrites);
            }

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        result.getDcError(),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            if (!result.isDcSuccess()) log.warn("Failed to publish to DC cluster for user '{}'", user.getUserName());
            if (!result.isDrSuccess()) log.warn("Failed to publish to DR cluster for user '{}'", user.getUserName());

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish user created events for '{}'", user.getUserName(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }


    private void publishUserUpdatedEvents(UserEntity user) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toDBWriteEvent("UPDATE", user, AAA_USER);

            if (user.getMacAddress() != null) {
                List<DBWriteRequestGeneric> macWrites = new ArrayList<>();

                // DELETE old MACs first, then INSERT new ones — all in same event
                macWrites.add(buildMacDeleteEvent(user.getUserName()));
                macWrites.addAll(buildMacCreateEvents(user.getUserName(), user.getMacAddress()));

                mainEvent.setRelatedWrites(macWrites);
            }

            kafkaEventPublisher.publishDBWriteEvent(mainEvent);

        } catch (Exception e) {
            log.error("Failed to publish user updated events for '{}'", user.getUserName(), e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
    private void publishUserDeletedEvents(UserEntity user) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toDBWriteEvent(DELETE, user, AAA_USER);

            // Bundle MAC delete as relatedWrite
            mainEvent.setRelatedWrites(List.of(buildMacDeleteEvent(user.getUserName())));

            kafkaEventPublisher.publishDBWriteEvent(mainEvent);

        } catch (Exception e) {
            log.error("Failed to publish user deleted events for '{}'", user.getUserName(), e);
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        e.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
        }
    }

    // NEW helper — builds list of MAC INSERT events
    private List<DBWriteRequestGeneric> buildMacCreateEvents(String userName, String macAddressString) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN));

        return Arrays.stream(macAddressString.split(","))
                .map(String::trim)
                .filter(mac -> !mac.isEmpty())
                .map(mac -> {
                    String normalized = normalizeMacAddress(mac);
                    Map<String, Object> cols = new HashMap<>();
                    cols.put(USER_NAME, userName);
                    cols.put("MAC_ADDRESS", normalized);
                    cols.put("ORIGINAL_MAC_ADDRESS", mac);
                    cols.put("CREATED_DATE", timestamp);

                    return DBWriteRequestGeneric.builder()
                            .eventType(CREATE)
                            .timestamp(timestamp)
                            .userName(userName)
                            .tableName(AAA_USER_MAC)
                            .columnValues(cols)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // NEW helper — builds a MAC DELETE event
    private DBWriteRequestGeneric buildMacDeleteEvent(String userName) {
        return DBWriteRequestGeneric.builder()
                .eventType(DELETE)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN)))
                .userName(userName)
                .tableName(AAA_USER_MAC)
                .whereConditions(Map.of(USER_NAME, userName))
                .build();
    }


    private void updateMacAddressesWithRetry(String userName, String macAddressString) {
        try {
            List<String> macList = extractValidMacs(macAddressString);

            // Step 1: Publish DELETE
            log.info("Publishing MAC DELETE event for user '{}'", userName);
            publishMacAddressDeleteEvent(userName);

            // Step 2: Minimal wait - DB op is fire-and-forget direct SQL (~10ms)
            // 100ms is more than enough buffer
            Thread.sleep(350);

            // Step 3: Publish INSERTs sequentially with tiny gap
            List<String> failedMacs = new ArrayList<>();
            int successCount = 0;

            for (int i = 0; i < macList.size(); i++) {
                String mac = macList.get(i);

                boolean success = processSingleMac(userName, mac, failedMacs);
                if (success) {
                    successCount++;
                }

                // 30ms gap is enough since DB write is direct SQL fire-and-forget
                if (i < macList.size() - 1) {
                    Thread.sleep(30);
                }
            }

            // Step 4: Fail loudly if any MAC failed
            if (!failedMacs.isEmpty()) {
                log.error("Failed to publish {}/{} MAC addresses for user '{}'. Failed MACs: {}",
                        failedMacs.size(), macList.size(), userName, String.join(", ", failedMacs));
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "MAC address update incomplete. Failed to publish: " + String.join(", ", failedMacs),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            log.info("Successfully published {}/{} MAC addresses for user '{}'",
                    successCount, macList.size(), userName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted during MAC update for user '{}'", userName, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "MAC address update was interrupted",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to update MAC addresses for user '{}'", userName, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to update MAC addresses",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /*private void publishUserDeletedEvents(UserEntity user) {
        try {

            DBWriteRequestGeneric dbEvent = eventMapper.toDBWriteEvent(DELETE, user, AAA_USER);
            kafkaEventPublisher.publishDBWriteEvent(dbEvent);

            //  PUBLISH MAC ADDRESS DELETE EVENT
            publishMacAddressEvents(DELETE, user.getUserName(), null);

        } catch (Exception e) {
            log.error("Failed to publish user deleted events for '{}'", user.getUserName(), e);
        }
    }*/

    private void publishMacAddressEvents(String eventType, String userName, String macAddressString) {
        try {
            if (DELETE.equals(eventType)) {
                publishMacAddressDeleteEvent(userName);
                return;
            }

            if (CREATE.equals(eventType)) {
                publishMacAddressCreateEventsWithValidation(userName, macAddressString);
            }

        } catch (Exception e) {
            log.error("Failed to publish MAC address events for user '{}'", userName, e);
        }
    }

    private int publishMacAddressCreateEventsWithValidation(String userName, String macAddressString) {

        if (isMacStringEmpty(macAddressString)) {
            return 0;
        }

        List<String> macList = extractValidMacs(macAddressString);
        int successCount = 0;
        List<String> failedMacs = new ArrayList<>();

        for (String mac : macList) {
            if (processSingleMac(userName, mac, failedMacs)) {
                successCount++;
            }
        }

        logMacSummary(userName, successCount, macList.size(), failedMacs);

        return successCount;
    }

    private boolean isMacStringEmpty(String macAddressString) {
        return macAddressString == null || macAddressString.isBlank();
    }

    private List<String> extractValidMacs(String macAddressString) {
        return Arrays.stream(macAddressString.split(","))
                .map(String::trim)
                .filter(mac -> !mac.isEmpty())
                .toList();
    }

    private boolean processSingleMac(String userName, String mac, List<String> failedMacs) {

        try {
            String normalizedMac = normalizeMacAddress(mac);

            DBWriteRequestGeneric request = buildMacCreateEvent(userName, mac, normalizedMac);

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(request);

            return handlePublishResult(result, userName, mac, failedMacs);

        } catch (Exception e) {
            log.error("Error processing MAC '{}' for user '{}'", mac, userName, e);
            failedMacs.add(mac);
            return false;
        }
    }
    private DBWriteRequestGeneric buildMacCreateEvent(String userName,
                                                      String originalMac,
                                                      String normalizedMac) {

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern(DATE_PATTERN));

        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put(USER_NAME, userName);
        columnValues.put("MAC_ADDRESS", normalizedMac);
        columnValues.put("ORIGINAL_MAC_ADDRESS", originalMac);
        columnValues.put("CREATED_DATE", timestamp);

        return DBWriteRequestGeneric.builder()
                .eventType(CREATE)
                .timestamp(timestamp)
                .userName(userName)
                .columnValues(columnValues)
                .tableName(AAA_USER_MAC)
                .build();
    }

    private boolean handlePublishResult(PublishResult result,
                                        String userName,
                                        String mac,
                                        List<String> failedMacs) {

        if (result.isCompleteFailure()) {
            log.error("Failed to publish MAC create event for MAC '{}' (user: '{}')", mac, userName);
            failedMacs.add(mac);
            return false;
        }

        log.debug("Successfully published MAC create event for MAC '{}' (user: '{}')", mac, userName);

        if (!result.isDcSuccess()) {
            log.warn("MAC create failed on DC cluster for MAC '{}' (user: '{}')", mac, userName);
        }

        if (!result.isDrSuccess()) {
            log.warn("MAC create failed on DR cluster for MAC '{}' (user: '{}')", mac, userName);
        }

        return true;
    }

    private void logMacSummary(String userName,
                               int successCount,
                               int totalMacs,
                               List<String> failedMacs) {

        if (!failedMacs.isEmpty()) {
            log.error("Failed to publish {} out of {} MAC addresses for user '{}'. Failed MACs: {}",
                    failedMacs.size(), totalMacs, userName, String.join(", ", failedMacs));
        }

        log.info("MAC address creation summary for user '{}': {}/{} successful",
                userName, successCount, totalMacs);
    }







    private void publishMacAddressDeleteEvent(String userName) {
        try {
            DBWriteRequestGeneric macEvent = DBWriteRequestGeneric.builder()
                    .eventType(DELETE)
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN)))
                    .userName(userName)
                    .tableName(AAA_USER_MAC)
                    .whereConditions(Map.of(USER_NAME, userName))
                    .build();

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(macEvent);

            if (result.isCompleteFailure()) {
                log.error("Failed to publish MAC delete event for user '{}'", userName);
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Failed to delete existing MAC addresses",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            if (!result.isDcSuccess()) {
                log.warn("MAC delete failed on DC cluster for user '{}'", userName);
            }
            if (!result.isDrSuccess()) {
                log.warn("MAC delete failed on DR cluster for user '{}'", userName);
            }

            log.debug("Successfully published MAC delete event for user '{}'", userName);

        } catch (Exception e) {
            log.error("Error publishing MAC delete event for user '{}'", userName, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to delete existing MAC addresses",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // KEEP for READ-ONLY operations (fetching existing MACs)
    private void enrichUserWithMacAddresses(UserEntity user) {
        List<UserToMac> macAddresses = userToMacRepository.findByUserName(user.getUserName());
        if (!macAddresses.isEmpty()) {
            String macString = macAddresses.stream()
                    .map(UserToMac::getOriginalMacAddress)
                    .collect(Collectors.joining(", "));
            user.setMacAddress(macString);
        }
    }
    private void validateBandwidthForGroup(CreateUserRequest request, String groupId) {
        // If groupId is provided (not default and not blank), bandwidth is mandatory
        if (!groupId.equals(defaultGroupId) &&
                (request.getBandwidth() == null || request.getBandwidth().isBlank())) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_BANDWIDTH_REQUIRED,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    private void validateIpAllocationValue(CreateUserRequest request) {
        String ipAllocation = request.getIpAllocation();

        if (ipAllocation == null || ipAllocation.isBlank()) {
            return; // Will be caught by other validations if required
        }

        if (!(DYNAMIC.equalsIgnoreCase(ipAllocation) || STATIC.equalsIgnoreCase(ipAllocation))) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "ip_allocation must either 'Dynamic' or 'static'",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void validateNasPortType(CreateUserRequest request) {
        String nasPortType = request.getNasPortType();

        if (nasPortType == null || nasPortType.isBlank()) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_NAS_PORT_MANDATORY,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (!(PPPOE.equalsIgnoreCase(nasPortType) || IPOE.equalsIgnoreCase(nasPortType))) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_NAS_PORT_INVALID,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (PPPOE.equalsIgnoreCase(nasPortType) &&
                (request.getPassword() == null || request.getPassword().isBlank())) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_PASSWORD_REQUIRED_PPPOE,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (IPOE.equalsIgnoreCase(nasPortType) &&
                (request.getMacAddress() == null || request.getMacAddress().isBlank())) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_MAC_REQUIRED_IPOE,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (IPOE.equalsIgnoreCase(nasPortType) &&
                (request.getIpAllocation() == null || request.getIpAllocation().isBlank())) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_IP_ALLOCATION_REQUIRED_IPOE,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }
    private void validateCycleDate(Integer cycleDate) {
        if (cycleDate == null) {
            return; // Optional when billing is not 3
        }

        if (cycleDate < 1 || cycleDate > 28) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "cycle_date must be between 1 and 28 (inclusive)",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void validateNetworkRules(CreateUserRequest request) {
        if (STATIC.equalsIgnoreCase(request.getIpAllocation()) && (request.getIpv4() == null || request.getIpv4().isBlank()) && (request.getIpv6() == null || request.getIpv6().isBlank()) ) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_IPV4_IPV6_REQUIRED_STATIC,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }


        if (DYNAMIC.equalsIgnoreCase(request.getIpAllocation()) &&
                (request.getIpPoolName() == null || request.getIpPoolName().isBlank())) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_IP_POOL_REQUIRED_DYNAMIC,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    private void validateBillingRules(CreateUserRequest request) {
        String billing = request.getBilling();
        if (billing != null && !billing.isBlank()) {
            if (!(BILLING_DAILY.equals(billing) || BILLING_MONTHLY.equals(billing) || BILLING_CYCLE.equals(billing))) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_BILLING_INVALID,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }

            if (BILLING_CYCLE.equals(billing) && request.getCycleDate() == null) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_CYCLE_DATE_REQUIRED,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
        }

        // Move this OUTSIDE the billing check to validate for all cases
        validateCycleDate(request.getCycleDate());
    }
    // Utility method to parse EncryptionMethod
    private void parseEncryptionMethod(Integer encryptionCode) {
        if (encryptionCode == null) {
            return;
        }

        try {
            EncryptionMethod.fromCode(encryptionCode);
        } catch (IllegalArgumentException e) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    INVALID_ENCRYPTION_METHOD + encryptionCode + VALID_ENCRYPTION_METHODS,
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    // Refactored validation for CreateUserRequest
    private void validateEncryptionMethod(CreateUserRequest request) {
        String password = request.getPassword();
        Integer encryptionMethod = request.getEncryptionMethod();

        // If password is provided, encryption_method is mandatory
        if (password != null && !password.isBlank()) {
            if (encryptionMethod == null) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "encryption_method is mandatory when password is provided",
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }

            // Validate using parse method
            parseEncryptionMethod(encryptionMethod);
        }
    }
    private void validateTemplateId(Long templateId) {
        if (templateId != null && !superTemplateRepository.existsById(templateId)) {
            throw new AAAException(
                    LogMessages.ERROR_NOT_FOUND,
                    "Template with ID " + templateId + " not found",
                    HttpStatus.NOT_FOUND
            );
        }
    }

    private Long getTemplateIdOrDefault(CreateUserRequest request) {
        // If templateId is provided, validate and return it
        if (request.getTemplateId() != null) {
            validateTemplateId(request.getTemplateId());
            return request.getTemplateId();
        }

        // Otherwise, get the default template
        log.debug("No templateId provided, fetching default template");
        SuperTemplate defaultTemplate = superTemplateRepository.findByIsDefault(true)
                .orElseThrow(() -> {
                    log.error("No default template found in the system");
                    return new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            "No default template configured in the system",
                            HttpStatus.NOT_FOUND
                    );
                });

        log.info("Using default template: {} (ID: {})", defaultTemplate.getTemplateName(), defaultTemplate.getId());
        return defaultTemplate.getId();
    }

    // Refactored validation for UpdateUserRequest
    private void validateEncryptionMethodForUpdate(UpdateUserRequest request) {
        // This method now only validates IF encryption_method is provided
        // The actual requirement check is done in updatePassword()

        if (request.getEncryptionMethod() != null) {
            // Just validate the value is correct
            try {
                EncryptionMethod.fromCode(request.getEncryptionMethod());
            } catch (IllegalArgumentException e) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        INVALID_ENCRYPTION_METHOD + request.getEncryptionMethod() +
                                VALID_ENCRYPTION_METHODS,
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }
    @SneakyThrows
    private void validateContactNumbers(CreateUserRequest request) {
        if (request.getContactNumber() == null || request.getContactNumber().isBlank()) {
            return;
        }

        String[] contactNumbers = request.getContactNumber().split(",");
        Set<String> uniqueNumbers = new HashSet<>();

        for (String number : contactNumbers) {
            number = number.trim();
            if (number.isEmpty()) continue;

            // Check for duplicate contact numbers within the request itself
            if (!uniqueNumbers.add(number)) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Duplicate contact number found in request: " + number,
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }
    @SneakyThrows
    private void validateContactNumbersForUpdate(UpdateUserRequest request) {
        if (request.getContactNumber() == null || request.getContactNumber().isBlank()) {
            return;
        }

        String[] contactNumbers = request.getContactNumber().split(",");
        Set<String> uniqueNumbers = new HashSet<>();

        for (String number : contactNumbers) {
            number = number.trim();
            if (number.isEmpty()) continue;

            // Check for duplicate contact numbers within the request itself
            if (!uniqueNumbers.add(number)) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Duplicate contact number found in request: " + number,
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }

    @SneakyThrows
    private void validateContactEmailsForUpdate(UpdateUserRequest request) {
        if (request.getContactEmail() == null || request.getContactEmail().isBlank()) {
            return;
        }

        String[] contactEmails = request.getContactEmail().split(",");
        Set<String> uniqueEmails = new HashSet<>();

        for (String email : contactEmails) {
            email = email.trim();
            if (email.isEmpty()) continue;

            // Check for duplicate contact emails within the request itself
            if (!uniqueEmails.add(email.toLowerCase())) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Duplicate contact email found in request: " + email,
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }
    @SneakyThrows
    private void validateContactEmails(CreateUserRequest request) {
        if (request.getContactEmail() == null || request.getContactEmail().isBlank()) {
            return;
        }

        String[] contactEmails = request.getContactEmail().split(",");
        Set<String> uniqueEmails = new HashSet<>();

        for (String email : contactEmails) {
            email = email.trim();
            if (email.isEmpty()) continue;

            // Check for duplicate contact emails within the request itself
            if (!uniqueEmails.add(email.toLowerCase())) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Duplicate contact email found in request: " + email,
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }

    private void validateConcurrencyAndStatus(CreateUserRequest request) {

        // concurrency is optional — defaults to 5 if not provided
        if (request.getConcurrency() != null && request.getConcurrency() < 1) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_CONCURRENCY_MIN,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (request.getStatus() == null) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Status must be a valid integer value.",
                    HttpStatus.BAD_REQUEST
            );
        }

        validateStatusValue(request.getStatus());
    }


    void validateAndApplyUpdates(UserEntity user, UpdateUserRequest request) {
        // Validate encryption method first if password is being updated
        validateEncryptionMethodForUpdate(request);
        validateContactNumbersForUpdate(request);
        validateContactEmailsForUpdate(request);
        validateAndUpdateGroupBilling(user, request);

        // CAPTURE OLD STATUS BEFORE ANY UPDATES
        UserStatus oldStatus = user.getStatus();

        // STATUS UPDATE WITH COA LOGIC
        if (request.getStatus() != null) {
            validateStatusValue(request.getStatus());
            UserStatus newStatus = parseStatus(request.getStatus());

            // CHECK IF STATUS ACTUALLY CHANGED AND NOT FROM INACTIVE
            if (oldStatus != newStatus) {
                log.info("User '{}' status changing from {} to {}",
                        user.getUserName(), oldStatus, newStatus);

                // SEND COA REQUEST TO ACCOUNTING SERVICE
                // Will automatically skip if oldStatus is INACTIVE
                coaManagementService.sendCoARequest(user.getUserName(), oldStatus, newStatus);
            }

            user.setStatus(newStatus);
        }

        updateNasPortType(user, request);
        updatePassword(user, request);
        updateGroupAndBandwidth(user, request);
        updateTemplateId(user, request);
        updateNetworkFields(user, request);
        updateMacAddress(user, request);
        updateIpAllocation(user, request);
        updateIpFields(user, request);
        updateBilling(user, request);
        updateContactFields(user, request);
        updateConcurrency(user, request);
        updateTimeoutFields(user, request);
        updateMiscFields(user, request);
        validateIpAllocation(request.getIpAllocation());
    }


    private void updateNasPortType(UserEntity user, UpdateUserRequest request) {
        if (request.getNasPortType() != null && !request.getNasPortType().isBlank()) {
            validateNasPortTypeUpdate(user, request);
            user.setNasPortType(request.getNasPortType());
        }
    }
    private void validateTemplateIdForUpdate(Long templateId) {
        if (templateId != null && !superTemplateRepository.existsById(templateId)) {
            throw new AAAException(
                    LogMessages.ERROR_NOT_FOUND,
                    "Template with ID " + templateId + " not found",
                    HttpStatus.NOT_FOUND
            );
        }
    }
    private void updateTemplateId(UserEntity user, UpdateUserRequest request) {
        if (request.getTemplateId() != null) {
            validateTemplateIdForUpdate(request.getTemplateId());
            user.setTemplateId(request.getTemplateId());
            log.debug("Template ID updated to {} for user: {}", request.getTemplateId(), user.getUserName());
        }
    }

    private void updatePassword(UserEntity user, UpdateUserRequest request) {
        boolean isPasswordProvided = request.getPassword() != null && !request.getPassword().isBlank();
        boolean isEncryptionMethodProvided = request.getEncryptionMethod() != null;

        // Case 1: Only encryption_method provided (no password)
        if (!isPasswordProvided && isEncryptionMethodProvided) {
            // Optional: Re-encrypt existing password with new encryption method
            if (user.getPassword() != null && !user.getPassword().isBlank()) {
                // You could implement re-encryption logic here if needed
                log.warn("Encryption method change without password update for user: {}", user.getUserName());
                user.setEncryptionMethod(request.getEncryptionMethod());
            } else {
                log.debug("Encryption method updated but no password exists for user: {}", user.getUserName());
                user.setEncryptionMethod(request.getEncryptionMethod());
            }
            return;
        }

        // Case 2: No password update
        if (!isPasswordProvided) {
            return;
        }

        // Case 3: Password is being updated
        // Determine which encryption method to use
        Integer effectiveEncryptionMethod;

        if (isEncryptionMethodProvided) {
            // Priority 1: Use from request
            effectiveEncryptionMethod = request.getEncryptionMethod();
            log.debug("Using encryption_method from request: {}", effectiveEncryptionMethod);
        } else if (user.getEncryptionMethod() != null) {
            // Priority 2: Use from database
            effectiveEncryptionMethod = user.getEncryptionMethod();
            log.debug("Using existing encryption_method from DB: {} for user: {}",
                    effectiveEncryptionMethod, user.getUserName());
        } else {
            // Priority 3: Error - no encryption method available
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "encryption_method is required when updating password. " +
                            "Either provide it in the request or ensure the user has an existing encryption_method in the database.",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        // Validate the encryption method value
        try {
            EncryptionMethod.fromCode(effectiveEncryptionMethod);
        } catch (IllegalArgumentException e) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    INVALID_ENCRYPTION_METHOD + effectiveEncryptionMethod +
                            VALID_ENCRYPTION_METHODS,
                    HttpStatus.BAD_REQUEST
            );
        }

        // Process password with the determined encryption method
        String processedPassword = processPassword(request.getPassword(), effectiveEncryptionMethod);

        // Update password
        user.setPassword(processedPassword);

        // Update encryption_method ONLY if it was provided in the request
        // This preserves the DB value if only password is being updated
        if (isEncryptionMethodProvided) {
            user.setEncryptionMethod(request.getEncryptionMethod());
            log.debug("Updated encryption_method to: {} for user: {}",
                    request.getEncryptionMethod(), user.getUserName());
        }

        log.info("Password updated successfully for user: {}", user.getUserName());
    }

    private String processPassword(String plainPassword, Integer encryptionMethodCode) {
        if (plainPassword == null || plainPassword.isBlank()) {
            return plainPassword;
        }

        if (encryptionMethodCode == null) {
            return plainPassword;
        }

        EncryptionMethod encryptionMethod = EncryptionMethod.fromCode(encryptionMethodCode);

        // If encryption method is PLAIN (0), save password as-is
        if (encryptionMethod == EncryptionMethod.PLAIN) {
            log.debug("Password saved as plain text for PLAIN encryption method");
            return plainPassword;
        }

        // If encryption method is MD5 (1), hash using MD5
        if (encryptionMethod == EncryptionMethod.MD5) {
            try {
                String md5Hash = encryptionPlugin.hashMd5(plainPassword);
                log.debug("Password hashed using MD5 for MD5 encryption method");
                return md5Hash;
            } catch (Exception e) {
                log.error("Failed to hash password with MD5", e);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Failed to hash password with MD5",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }
        }

        // For CSG_ADL (2), encrypt using AES and save
        if (encryptionMethod == EncryptionMethod.CSG_ADL) {
            try {
                String encryptedPassword = encryptionPlugin.encrypt(
                        plainPassword,
                        encryptionAlgorithm,
                        encryptionSecretKey
                );
                log.debug("Password encrypted using AES for CSG_ADL encryption method");
                return encryptedPassword;
            } catch (Exception e) {
                log.error("Failed to encrypt password", e);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Failed to encrypt password",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }
        }

        // Default: save as-is
        return plainPassword;
    }

    private void updateGroupAndBandwidth(UserEntity user, UpdateUserRequest request) {
        if (request.getGroupId() != null) {
            user.setGroupId(request.getGroupId());

            // Simply update bandwidth if provided
            if (request.getBandwidth() != null) {
                user.setBandwidth(request.getBandwidth());
            }
        } else if (request.getBandwidth() != null) {
            user.setBandwidth(request.getBandwidth());
        }
    }




    private void updateNetworkFields(UserEntity user, UpdateUserRequest request) {
        if (request.getVlanId() != null) {
            user.setVlanId(request.getVlanId());
        }
        if (request.getCircuitId() != null) {
            user.setCircuitId(request.getCircuitId());
        }
        if (request.getRemoteId() != null) {
            user.setRemoteId(request.getRemoteId());
        }
    }

    private void updateMacAddress(UserEntity user, UpdateUserRequest request) {
        // If MAC address is not in the request, skip update
        if (request.getMacAddress() == null) {
            return;
        }

        // If user is IPoE and trying to clear MAC (empty string), check if it's allowed
        if (IPOE.equalsIgnoreCase(user.getNasPortType()) && request.getMacAddress().isBlank()) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_MAC_REQUIRED_IPOE_USER,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        // If changing TO IPoE via nas_port_type in same request
        if (IPOE.equalsIgnoreCase(request.getNasPortType()) && request.getMacAddress().isBlank()) {
            // Check if MAC exists in DB
            List<UserToMac> existingMacs = userToMacRepository.findByUserName(user.getUserName());
            if (existingMacs.isEmpty()) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_MAC_REQUIRED_IPOE,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
            // MAC exists in DB, no need to update
            return;
        }

        // Set transient field - Kafka will handle the DB writes
        user.setMacAddress(request.getMacAddress());
    }

    private void updateIpAllocation(UserEntity user, UpdateUserRequest request) {
        if (request.getIpAllocation() != null && !request.getIpAllocation().isBlank()) {
            validateIpAllocationUpdate(user, request);
            user.setIpAllocation(request.getIpAllocation());
        }
    }

    private void updateIpFields(UserEntity user, UpdateUserRequest request) {
        if (request.getIpPoolName() != null) {
            user.setIpPoolName(request.getIpPoolName());
        }
        if (request.getIpv4() != null) {
            user.setIpv4(request.getIpv4());
        }
        if (request.getIpv6() != null) {
            user.setIpv6(request.getIpv6());
        }
    }

    private void updateBilling(UserEntity user, UpdateUserRequest request) {
        if (request.getBilling() != null && !request.getBilling().isBlank()) {
            // Add validation BEFORE setting the billing value
            if (BILLING_CYCLE.equals(request.getBilling()) && request.getCycleDate() == null) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_CYCLE_DATE_REQUIRED,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }

            user.setBilling(request.getBilling());
        }

        // Validate cycle date range for all billing types
        validateCycleDate(request.getCycleDate());

        // Update cycle date if provided (regardless of billing value)
        if (request.getCycleDate() != null) {
            user.setCycleDate(request.getCycleDate());
        }

        if (request.getStatus() != null) {
            user.setStatus(UserStatus.fromCode(request.getStatus()));
        }
        if (request.getSubscription() != null) {
            Subscription subscription = parseSubscription(request.getSubscription());
            user.setSubscription(subscription);
        }
    }

    private void updateContactFields(UserEntity user, UpdateUserRequest request) {
        if (request.getContactName() != null) {
            user.setContactName(request.getContactName());
        }
        if (request.getContactEmail() != null) {
            user.setContactEmail(request.getContactEmail());
        }
        if (request.getContactNumber() != null) {
            user.setContactNumber(request.getContactNumber());
        }
    }
    void validateIpAllocation(String ipAllocation) {
        if (ipAllocation == null || ipAllocation.isBlank()) {
            return; // null/blank is acceptable for updates
        }

        if (!(DYNAMIC.equalsIgnoreCase(ipAllocation) || STATIC.equalsIgnoreCase(ipAllocation))) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "ip_allocation must be either 'Dynamic' or 'static'",
                    HttpStatus.BAD_REQUEST  // Changed from UNPROCESSABLE_ENTITY to BAD_REQUEST
            );
        }
    }

    private void updateConcurrency(UserEntity user, UpdateUserRequest request) {
        if (request.getConcurrency() == null) {
            return;
        }

        if (request.getConcurrency() < 1) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    LogMessages.MSG_CONCURRENCY_MIN,
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
        user.setConcurrency(request.getConcurrency());
    }

    private void updateTimeoutFields(UserEntity user, UpdateUserRequest request) {
        if (request.getSessionTimeout() != null) {
            user.setSessionTimeout(request.getSessionTimeout());
        }
    }

    // Add this new validation method
    @SneakyThrows
    private void validateRequestIdForUpdate(UserEntity user, UpdateUserRequest request) {
        // Skip if request_id is same as current (no change)
        if (request.getRequestId().equals(user.getRequestId())) {
            log.warn("Duplicate update attempt for user '{}' with same request_id: {}",
                    user.getUserName(), request.getRequestId());
            throw new AAAException(
                    LogMessages.ERROR_DUPLICATE_USER,
                    "request_id '" + request.getRequestId() + "' is the same as the current request_id. No changes detected.",
                    HttpStatus.CONFLICT
            );
        }

        // Check if request_id exists for another user
        boolean requestIdExists = (boolean) asyncAdaptor.supplyAll(
                6000L,
                () -> userRepository.existsByRequestId(request.getRequestId())
        )[0].get();

        if (requestIdExists) {
            log.warn(REQUEST_ID_ALREADY_EXIST, request.getRequestId());
            throw new AAAException(
                    LogMessages.ERROR_DUPLICATE_USER,
                    "request_id '" + request.getRequestId() + ALREADY_EXISTS,
                    HttpStatus.CONFLICT
            );
        }
    }

    // Update the updateMiscFields method to handle requestId properly
    private void updateMiscFields(UserEntity user, UpdateUserRequest request) {
        if (request.getBillingAccountRef() != null) {
            user.setBillingAccountRef(request.getBillingAccountRef());
        }

        // Only update requestId if validation passed
        if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
            user.setRequestId(request.getRequestId());
        }
    }

    private void validateNasPortTypeUpdate(UserEntity user, UpdateUserRequest request) {
        String newNasPortType = request.getNasPortType();

        // Validate nas_port_type value format
        if (!(PPPOE.equalsIgnoreCase(newNasPortType) || IPOE.equalsIgnoreCase(newNasPortType))) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "nas_port_type must be either 'PPPoE' or 'IPoE'",
                    HttpStatus.BAD_REQUEST
            );
        }

        // PPPOE validation - check BOTH request and existing user password
        if (PPPOE.equalsIgnoreCase(newNasPortType)) {
            boolean hasPasswordInRequest = !isBlank(request.getPassword());
            boolean hasPasswordInDb = !isBlank(user.getPassword());

            if (!hasPasswordInRequest && !hasPasswordInDb) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_PASSWORD_REQUIRED_PPPOE,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
        }

        // IPOE validation - check BOTH request and existing user MAC
        if (IPOE.equalsIgnoreCase(newNasPortType)) {
            boolean hasMacInRequest = !isBlank(request.getMacAddress());

            // Check if MAC exists in database
            if (!hasMacInRequest) {
                List<UserToMac> existingMacs = userToMacRepository.findByUserName(user.getUserName());
                if (existingMacs.isEmpty()) {
                    throw new AAAException(
                            LogMessages.ERROR_VALIDATION_FAILED,
                            LogMessages.MSG_MAC_REQUIRED_IPOE,
                            HttpStatus.UNPROCESSABLE_ENTITY
                    );
                }
            }

            // Second check: IP allocation requirement
            boolean hasIpAllocationInRequest = !isBlank(request.getIpAllocation());
            boolean hasIpAllocationInDb = !isBlank(user.getIpAllocation());

            if (!hasIpAllocationInRequest && !hasIpAllocationInDb) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_IP_ALLOCATION_REQUIRED_IPOE,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
        }
    }

    private void validateIpAllocationUpdate(UserEntity user, UpdateUserRequest request) {
        String newIpAllocation = request.getIpAllocation();

        if (isBlank(newIpAllocation)) {
            return; // No change to ip_allocation
        }

        // Validate format
        if (!(DYNAMIC.equalsIgnoreCase(newIpAllocation) || STATIC.equalsIgnoreCase(newIpAllocation))) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "ip_allocation must be either 'Dynamic' or 'static'",
                    HttpStatus.BAD_REQUEST
            );
        }

        // STATIC IP validation - need IPv4 OR IPv6 (check both request and DB)
        if (STATIC.equalsIgnoreCase(newIpAllocation)) {
            boolean hasIpv4 = !isBlank(request.getIpv4()) || !isBlank(user.getIpv4());
            boolean hasIpv6 = !isBlank(request.getIpv6()) || !isBlank(user.getIpv6());

            if (!hasIpv4 && !hasIpv6) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "IPv4 or IPv6 must be provided when ip_allocation is 'static'",
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
        }

        // DYNAMIC validation - need ip_pool_name (check both request and DB)
        if (DYNAMIC.equalsIgnoreCase(newIpAllocation)) {
            boolean hasIpPoolInRequest = !isBlank(request.getIpPoolName());
            boolean hasIpPoolInDb = !isBlank(user.getIpPoolName());

            if (!hasIpPoolInRequest && !hasIpPoolInDb) {
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        LogMessages.MSG_IP_POOL_REQUIRED_DYNAMIC,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
        }
    }

    // Helper method if not already present
    boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    UserStatus parseStatus(Integer statusCode) {
        if (statusCode == null) {
            return null;
        }

        try {
            return UserStatus.fromCode(statusCode);
        } catch (IllegalArgumentException e) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Invalid status value: " + statusCode +
                            ". Status must be 1 (Active), 2 (Barred), or 3 (Inactive).",
                    HttpStatus.BAD_REQUEST
            );
        }
    }
    public Subscription parseSubscription(Integer modeCode) {
        if (modeCode == null) {
            return null;
        }

        try {
            return Subscription.fromCode(modeCode);
        } catch (IllegalArgumentException e) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Invalid paid mode value: " + modeCode +
                            ". Paid mode must be 0 (Prepaid), 1 (Postpaid), or 2 (Hybrid).",
                    HttpStatus.BAD_REQUEST
            );
        }
    }


    private void validateStatusValue(Integer status) {

        if (status == null) {
            return; // separately handled above
        }

        if (status < 1 || status > 3) {
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Invalid status value: " + status +
                            ". Status must be 1 (Active), 2 (Barred), or 3 (Inactive).",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    String normalizeMacAddress(String mac) {
        if (mac == null || mac.isBlank()) {
            return mac;
        }

        // Remove all separators (-, :, ., and spaces)
        return mac.trim().replaceAll("[-:.\\s]", "").toLowerCase();
    }

    private UserEntity mapToEntity(CreateUserRequest request, String groupId, Long templateId) {
        UserStatus userStatus = parseStatus(request.getStatus());
        Subscription subscription = parseSubscription(request.getSubscription());

        // Process password based on encryption method
        String processedPassword = processPassword(request.getPassword(), request.getEncryptionMethod());

        return UserEntity.builder()
                .userName(request.getUserName())
                .password(processedPassword)
                .encryptionMethod(request.getEncryptionMethod())
                .nasPortType(request.getNasPortType())
                .groupId(groupId)
                .bandwidth(request.getBandwidth())
                .vlanId(request.getVlanId())
                .circuitId(request.getCircuitId())
                .remoteId(request.getRemoteId())
                .ipAllocation(request.getIpAllocation())
                .ipPoolName(request.getIpPoolName())
                .ipv4(request.getIpv4())
                .ipv6(request.getIpv6())
                .templateId(templateId)
                .status(request.getStatus() != null ? userStatus : null)
                .subscription(request.getSubscription() != null ? subscription : null)
                .contactName(request.getContactName())
                .contactEmail(request.getContactEmail())
                .contactNumber(request.getContactNumber())
                .concurrency(request.getConcurrency())
                .billing(request.getBilling()!=null? request.getBilling() : "2")
                .cycleDate(request.getCycleDate())
                .billingAccountRef(request.getBillingAccountRef())
                .sessionTimeout(request.getSessionTimeout())
                .requestId(request.getRequestId())
                .build();
    }
    private CreateUserResponse mapToCreateUserResponse(UserEntity user) {
        // Fetch MAC addresses for the user
        List<UserToMac> macAddresses = userToMacRepository.findByUserName(user.getUserName());
        String macString = null;
        if (!macAddresses.isEmpty()) {
            macString = macAddresses.stream()
                    .map(UserToMac::getOriginalMacAddress)
                    .collect(Collectors.joining(", "));
        }

        return CreateUserResponse.builder()
                .userId(user.getUserId())
                .encryptionMethod(user.getEncryptionMethod())
                .userName(user.getUserName())
                .nasPortType(user.getNasPortType())
                .groupId(user.getGroupId())
                .bandwidth(user.getBandwidth())
                .vlanId(user.getVlanId())
                .circuitId(user.getCircuitId())
                .remoteId(user.getRemoteId())
                .macAddress(user.getMacAddress())
                .ipAllocation(user.getIpAllocation())
                .ipPoolName(user.getIpPoolName())
                .ipv4(user.getIpv4())
                .ipv6(user.getIpv6())
                .templateName(user.getTemplateName())
                .status(user.getStatus() != null ? user.getStatus().getCode() : null)
                .subscription(user.getSubscription() != null ? user.getSubscription().getCode() : null)
                .contactName(user.getContactName())
                .contactEmail(user.getContactEmail())
                .contactNumber(user.getContactNumber())
                .concurrency(user.getConcurrency())
                .billing(user.getBilling())
                .cycleDate(user.getCycleDate())
                .billingAccountRef(user.getBillingAccountRef())
                .sessionTimeout(user.getSessionTimeout())
                .requestId(user.getRequestId())
                .createdDate(user.getCreatedDate() != null ? LocalDateTime.parse(user.getCreatedDate().toString()) : null)
                .build();
    }



    private GetUserResponse mapToGetResponse(UserEntity user) {
        return GetUserResponse.builder()
                .userName(user.getUserName())
                .encryptionMethod(user.getEncryptionMethod())
                .nasPortType(user.getNasPortType())
                .groupId(user.getGroupId())
                .vlanId(user.getVlanId())
                .bandwidth(user.getBandwidth())
                .remoteId(user.getRemoteId())
                .circuitId(user.getCircuitId())
                .macAddress(user.getMacAddress())  //  Gets from TRANSIENT field
                .billing(user.getBilling())
                .concurrency(user.getConcurrency())
                .cycleDate(user.getCycleDate())
                .ipAllocation(user.getIpAllocation())
                .ipPoolName(user.getIpPoolName())
                .ipv4(user.getIpv4())
                .ipv6(user.getIpv6())
                .status(user.getStatus() != null ? user.getStatus().getCode() : null) // Get code from enum
                .subscription(user.getSubscription() != null ? user.getSubscription().getCode() : null)
                .contactName(user.getContactName())
                .contactEmail(user.getContactEmail())
                .contactNumber(user.getContactNumber())
                .templateId(user.getTemplateId())
                .templateName(user.getTemplateName())
                .billingAccountRef(user.getBillingAccountRef())
                .createdDate(user.getCreatedDate() != null ? LocalDateTime.parse(user.getCreatedDate().toString()) : null)
                .build();
    }

    private UserResponse mapToResponse(UserEntity user) {
        // Fetch MAC addresses for the user
        List<UserToMac> macAddresses = userToMacRepository.findByUserName(user.getUserName());
        String macString = null;
        if (!macAddresses.isEmpty()) {
            macString = macAddresses.stream()
                    .map(UserToMac::getOriginalMacAddress)
                    .collect(Collectors.joining(", "));
        }

        return UserResponse.builder()
                .userId(user.getUserId())
                .encryptionMethod(user.getEncryptionMethod())
                .userName(user.getUserName())
                .nasPortType(user.getNasPortType())
                .groupId(user.getGroupId())
                .bandwidth(user.getBandwidth())
                .vlanId(user.getVlanId())
                .circuitId(user.getCircuitId())
                .remoteId(user.getRemoteId())
                .macAddress(macString)  // Add this
                .ipAllocation(user.getIpAllocation())
                .ipPoolName(user.getIpPoolName())
                .ipv4(user.getIpv4())
                .ipv6(user.getIpv6())
                .status(user.getStatus() != null ? user.getStatus().getCode() : null)
                .subscription(user.getSubscription() != null ? user.getSubscription().getCode() : null)
                .contactName(user.getContactName())
                .contactEmail(user.getContactEmail())
                .contactNumber(user.getContactNumber())
                .concurrency(user.getConcurrency())
                .billing(user.getBilling())
                .cycleDate(user.getCycleDate())
                .billingAccountRef(user.getBillingAccountRef())
                .sessionTimeout(user.getSessionTimeout())
                .requestId(user.getRequestId())
                .templateName(user.getTemplateName())
                .createdDate(user.getCreatedDate() != null ? LocalDateTime.parse(user.getCreatedDate().toString()) : null)
                .updatedDate(user.getUpdatedDate() != null ? LocalDateTime.parse(user.getUpdatedDate().toString()) : null)
                .build();
    }

    private UpdateUserResponse mapToUpdateResponse(UserEntity user) {

        String macString = user.getMacAddress();

        return UpdateUserResponse.builder()
                .userId(user.getUserId())
                .encryptionMethod(user.getEncryptionMethod())
                .userName(user.getUserName())
                .nasPortType(user.getNasPortType())
                .groupId(user.getGroupId())
                .bandwidth(user.getBandwidth())
                .vlanId(user.getVlanId())
                .circuitId(user.getCircuitId())
                .remoteId(user.getRemoteId())
                .macAddress(macString)  // Add this
                .ipAllocation(user.getIpAllocation())
                .ipPoolName(user.getIpPoolName())
                .ipv4(user.getIpv4())
                .ipv6(user.getIpv6())
                .status(user.getStatus() != null ? user.getStatus().getCode() : null)
                .subscription(user.getSubscription() != null ? user.getSubscription().getCode() : null)
                .contactName(user.getContactName())
                .contactEmail(user.getContactEmail())
                .contactNumber(user.getContactNumber())
                .concurrency(user.getConcurrency())
                .billing(user.getBilling())
                .cycleDate(user.getCycleDate())
                .billingAccountRef(user.getBillingAccountRef())
                .sessionTimeout(user.getSessionTimeout())
                .requestId(user.getRequestId())
                .templateId(user.getTemplateId())
                .templateName(user.getTemplateName())
                .updatedDate(user.getUpdatedDate() != null ? LocalDateTime.parse(user.getUpdatedDate().toString()) : null)
                .build();
    }

    private PagedGroupUsersResponse.GroupUserInfo mapToGroupUserInfo(UserEntity user) {
        Integer statusCode = null;
        if (user.getStatus() != null) {
            statusCode = user.getStatus().getCode();
        }

        return PagedGroupUsersResponse.GroupUserInfo.builder()
                .userId(user.getUserName())
                .status(statusCode)
                .build();
    }



    @Transactional(readOnly = true)
    public List<UserListResponse> getUserList() {
        log.debug("Fetching simple user list (userName only)");
        long methodStart = System.currentTimeMillis();

        try {
            // --- DB Fetch Timing ---
            long dbStart = System.currentTimeMillis();
            List<UserEntity> allUsers = userRepository.findAll();
            long dbDuration = System.currentTimeMillis() - dbStart;
            log.info("DB fetch for getUserList completed in {} ms with {} records", dbDuration, allUsers.size());

            // --- Mapping Timing ---
            long mapStart = System.currentTimeMillis();
            List<UserListResponse> userList = allUsers.stream()
                    .map(user -> new UserListResponse(user.getUserName()))
                    .toList();
            long mapDuration = System.currentTimeMillis() - mapStart;
            log.info("Mapping UserEntity → UserListResponse for {} usernames took {} ms", userList.size(), mapDuration);

            return userList;

        } catch (Exception e) {
            log.error("Failed to fetch user list", e);
            throw new AAAException(
                    "USER_LIST_FETCH_ERROR",
                    "Failed to fetch user list",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } finally {
            long totalDuration = System.currentTimeMillis() - methodStart;
            log.info("Total execution time for getUserList = {} ms", totalDuration);
        }
    }

    /**
     * Generates and publishes a USER_CREATION notification for the given user.
     *
     * Best-effort: failure here is logged as a warning but does NOT
     * roll back or fail the user creation response.
     *
     * Flow:
     *  1. NotificationGenerator looks up child templates by superTemplateId
     *     and finds the one with messageType = "USER_CREATION".
     *  2. Replaces {userName} placeholder with the actual username.
     *  3. NotificationPublisher sends the event to the Notification Kafka Topic.
     */
    private void sendUserCreationNotification(UserEntity user) {
        try {
            notificationGenerator.generate(MSG_TYPE_USER_CREATION, user)
                    .ifPresentOrElse(
                            event -> {
                                notificationPublisher.publish(event);
                                log.info("USER_CREATION notification dispatched for user '{}'",
                                        user.getUserName());
                            },
                            () -> log.info(
                                    "No USER_CREATION child template for superTemplateId={}, " +
                                            "notification skipped for user '{}'",
                                    user.getTemplateId(), user.getUserName())
                    );
        } catch (Exception e) {
            // Notification failure must NEVER block the user creation response
            log.warn("Failed to send USER_CREATION notification for user '{}': {}",
                    user.getUserName(), e.getMessage(), e);
        }
    }

    /**
     * Generates and publishes a USER_UPDATE notification for the given user.
     * Best-effort: failure here never blocks the update response.
     */
    private void sendUserUpdateNotification(UserEntity user) {
        try {
            notificationGenerator.generate(MSG_TYPE_USER_UPDATE, user)
                    .ifPresentOrElse(
                            event -> {
                                notificationPublisher.publish(event);
                                log.info("USER_UPDATE notification dispatched for user '{}'",
                                        user.getUserName());
                            },
                            () -> log.info(
                                    "No USER_UPDATE child template for superTemplateId={}, " +
                                            "notification skipped for user '{}'",
                                    user.getTemplateId(), user.getUserName())
                    );
        } catch (Exception e) {
            log.warn("Failed to send USER_UPDATE notification for user '{}': {}",
                    user.getUserName(), e.getMessage(), e);
        }
    }
    private void sendUserDeletionNotification(UserEntity user) {
        try {
            notificationGenerator.generate(MSG_TYPE_USER_DELETION, user)
                    .ifPresentOrElse(
                            event -> {
                                notificationPublisher.publish(event);
                                log.info("USER_DELETION notification dispatched for user '{}'",
                                        user.getUserName());
                            },
                            () -> log.info(
                                    "No USER_DELETION child template for superTemplateId={}, " +
                                            "notification skipped for user '{}'",
                                    user.getTemplateId(), user.getUserName())
                    );
        } catch (Exception e) {
            log.warn("Failed to send USER_DELETION notification for user '{}': {}",
                    user.getUserName(), e.getMessage(), e);
        }
    }
}