package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.ActiveServiceRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ActiveServiceResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.DeleteResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.UpdateResponseDTO;
import com.axonect.aee.template.baseapp.domain.adapter.AsyncAdaptorInterface;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceProvisioningService {
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final PlanToBucketRepository planToBucketRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final BucketRepository bucketRepository;
    private final QOSProfileRepository qosProfileRepository;
    private final BucketInstanceRepository bucketInstanceRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final EventMapper eventMapper;
    private final ServiceTTLManager serviceTTLManager;
    private final CoAManagementService coAManagementService;
    private final AsyncAdaptorInterface asyncAdaptor;

    private static final String ACTIVE = "Active";
    private static final String INACTIVE = "Inactive";
    private static final String CREATED = "CREATE";
    private static final String DELETE = "DELETE";
    private static final String UPDATE = "UPDATE";
    private static final String SUSPENDED = "Suspended";
    private final AccountingCacheManagementService accountingCacheManagementService;

    /**
     * Generates a fast, non-cryptographic internal ID.
     * This is used for internal correlation only and does NOT require
     * cryptographic randomness.
     */
    @SuppressWarnings("java:S2245")
    private Long generateInternalId() {
        long timestampPart = System.currentTimeMillis() % 1_000_000;
        int random = ThreadLocalRandom.current().nextInt(10_000);
        return timestampPart * 10_000L + random;
    }

    /**
     * Generates a service ID.
     */
    private Long generateServiceId() {
        return generateInternalId();
    }

    /**
     * Generates a bucket instance ID.
     */
    private Long generateBucketInstanceId() {
        return generateInternalId();
    }


    @Transactional(readOnly = true) //  READ-ONLY for validations only
    public ActiveServiceResponseDTO activateService(ActiveServiceRequestDTO request) {
        log.info("Processing service activation for Plan ID: {}, Request ID: {} and User ID: {}",
                request.getPlanId(), request.getRequestId(), request.getUserId());
        try {
            // Validate status early
            if (request.getStatus() != null && "3".equals(request.getStatus())) {
                log.error("Attempt to activate service with Inactive status for Request ID: {}", request.getRequestId());
                throw new AAAException(
                        LogMessages.ERROR_BAD_REQUEST,
                        "Cannot activate service with Inactive status. Only Active or Suspended status is allowed.",
                        HttpStatus.BAD_REQUEST
                );
            }

            // Validate service dates
            validateServiceDates(
                    request.getServiceStartDate(),
                    request.getServiceEndDate(),
                    "Service Activation"
            );


            //  CHECK if request ID already exists (READ-ONLY)
            boolean requestExists = serviceInstanceRepository.existsByRequestId(request.getRequestId());
            if (requestExists) {
                log.error("Service instance with Request ID already exists: {}", request.getRequestId());
                throw new AAAException(
                        LogMessages.DUPLICATE_REQUEST_ID,
                        "Service instance with request ID already exists: " + request.getRequestId(),
                        HttpStatus.CONFLICT
                );
            }

            boolean isGroup = Boolean.TRUE.equals(request.getIsGroup());
            log.debug("Service activation type: {}", isGroup ? "Group" : "Individual");

            ServiceInstance serviceInstance;
            List<BucketInstance> bucketInstances;

            ServiceActivationResult result;
            if (isGroup) {
                result = activateGroupService(request);
            } else {
                result = activateIndividualService(request);
            }
            serviceInstance = result.serviceInstance;
            bucketInstances = result.bucketInstances;

            publishServiceCreatedEvents(serviceInstance, bucketInstances);
            accountingCacheManagementService.syncBuckets(
                    bucketInstances,
                    result.user != null ? result.user.getSessionTimeout() : String.valueOf(86400L),
                    result.user != null && result.user.getConcurrency() != null ? result.user.getConcurrency() : 5L,
                    serviceInstance);

            log.info("Service activation completed successfully for User ID: {}, Plan ID: {}, Service Instance ID: {}",
                    serviceInstance.getUsername(), serviceInstance.getPlanId(), serviceInstance.getId());

            // Get final quota from the highest priority bucket
            Long finalQuota = bucketInstances.stream()
                    .min((b1, b2) -> Long.compare(b1.getPriority(), b2.getPriority()))
                    .map(BucketInstance::getInitialBalance)
                    .orElse(0L);

            return ActiveServiceResponseDTO.builder()
                    .userId(serviceInstance.getUsername())
                    .planId(serviceInstance.getPlanId())
                    .planName(serviceInstance.getPlanName())
                    .planType(serviceInstance.getPlanType())
                    .status(mapStatusToCode(serviceInstance.getStatus()))
                    .finalQuota(finalQuota)  // Add this
                    .serviceStartDate(serviceInstance.getServiceStartDate())
                    .serviceEndDate(serviceInstance.getExpiryDate())
                    .build();

        } catch (AAAException ex) {
            log.error("AAAException during service activation for Request ID: {} - Code: {}, Message: {}",
                    request.getRequestId(), ex.getCode(), ex.getMessage(), ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during service activation for Request ID: {}",
                    request.getRequestId(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error during service activation",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    //  HELPER CLASS to return service, buckets, and user for sync
    private static class ServiceActivationResult {
        ServiceInstance serviceInstance;
        List<BucketInstance> bucketInstances;
        UserEntity user;

        ServiceActivationResult(ServiceInstance serviceInstance, List<BucketInstance> bucketInstances, UserEntity user) {
            this.serviceInstance = serviceInstance;
            this.bucketInstances = bucketInstances;
            this.user = user;
        }
    }

    private ServiceActivationResult activateGroupService(ActiveServiceRequestDTO request) {
        log.debug("Starting group service activation for Group: {}, Plan: {}", request.getUserId(), request.getPlanId());
        try {
            String groupId = request.getUserId();
            String planId = request.getPlanId();

            //  READ-ONLY: Fetch group user and plan in parallel — independent queries
            CompletableFuture<UserEntity> userFuture = CompletableFuture.supplyAsync(() ->
                    userRepository.findFirstByGroupId(groupId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "GROUP_NOT_FOUND", HttpStatus.NOT_FOUND)));
            CompletableFuture<Plan> planFuture = CompletableFuture.supplyAsync(() ->
                    planRepository.findByPlanId(planId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "PLAN_DOES_NOT_EXIST", HttpStatus.NOT_FOUND)));

            UserEntity user = userFuture.join();
            log.debug("Group found: {}", groupId);
            boolean hasActiveUsers = userRepository.existsByGroupIdAndStatus(groupId, UserStatus.ACTIVE);

            if (!hasActiveUsers) {
                log.error("No active users found for the group: {}", groupId);
                throw new AAAException(
                        LogMessages.ERROR_POLICY_CONFLICT,
                        "Cannot activate service for inactive group: " + groupId,
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }

            Plan plan = planFuture.join();
            log.debug("Plan was found: {} ({}), Recurring: {}", plan.getPlanName(), plan.getPlanType(), plan.getRecurringFlag());

            if (!plan.getStatus().equalsIgnoreCase(ACTIVE)) {
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "PLAN_IS_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            //   BUILD service instance (no DB save)
            ServiceInstance serviceInstance = new ServiceInstance();
            List<BucketInstance> bucketInstances = subscribeResources(user, plan, serviceInstance, request, true);

            log.info("Group service activation completed successfully for Group: {}", groupId);
            return new ServiceActivationResult(serviceInstance, bucketInstances, user);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during group service activation", ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error during group service activation", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ServiceActivationResult activateIndividualService(ActiveServiceRequestDTO request) {
        log.debug("Starting individual service activation for User: {}, Plan: {}", request.getUserId(), request.getPlanId());
        try {
            String userName = request.getUserId();
            String planId = request.getPlanId();

            CompletableFuture<UserEntity> userFuture = CompletableFuture.supplyAsync(() ->
                    userRepository.findByUserName(userName)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "USER_NOT_FOUND", HttpStatus.NOT_FOUND)));
            CompletableFuture<Plan> planFuture = CompletableFuture.supplyAsync(() ->
                    planRepository.findByPlanId(planId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "PLAN_DOES_NOT_EXIST", HttpStatus.NOT_FOUND)));

            // Unwrap CompletionException so AAAExceptions propagate correctly
            UserEntity user;
            Plan plan;
            try {
                user = userFuture.join();
                plan = planFuture.join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof AAAException aaaEx) {
                    throw aaaEx;
                }
                throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error during individual service activation", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            log.debug("User found: {} with billing type: {}", userName, user.getBilling());
            validateUserStatus(user, "Individual Service Activation");

            log.debug("Plan found: {} ({}), Recurring: {}", plan.getPlanName(), plan.getPlanType(), plan.getRecurringFlag());

            if (!plan.getStatus().equalsIgnoreCase(ACTIVE)) {
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "PLAN_IS_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            ServiceInstance serviceInstance = new ServiceInstance();
            List<BucketInstance> bucketInstances = subscribeResources(user, plan, serviceInstance, request, false);

            log.info("Individual service activation completed successfully for User: {}", userName);
            return new ServiceActivationResult(serviceInstance, bucketInstances, user);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during individual service activation", ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error during individual service activation", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void applyQuotaToPriorityBucket(List<BucketInstance> bucketInstances, Long quota) {
        if (quota == null || quota <= 0L || bucketInstances.isEmpty()) {
            return;
        }

        // Find bucket with the highest priority (lowest priority number)
        BucketInstance priorityBucket = bucketInstances.stream()
                .min((b1, b2) -> Long.compare(b1.getPriority(), b2.getPriority()))
                .orElseThrow(() -> new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "No priority bucket found to apply quota",
                        HttpStatus.INTERNAL_SERVER_ERROR
                ));

        log.debug("Applying quota {} to bucket {} with priority {}",
                quota, priorityBucket.getBucketId(), priorityBucket.getPriority());

        // Add quota to both initial and current balance
        Long newInitialBalance = priorityBucket.getInitialBalance() + quota;
        Long newCurrentBalance = priorityBucket.getCurrentBalance() + quota;

        priorityBucket.setInitialBalance(newInitialBalance);
        priorityBucket.setCurrentBalance(newCurrentBalance);

        log.debug("Updated bucket {} - Initial Balance: {}, Current Balance: {}",
                priorityBucket.getBucketId(), newInitialBalance, newCurrentBalance);
    }

    private List<BucketInstance> subscribeResources(UserEntity user, Plan plan, ServiceInstance serviceInstance,
                                                    ActiveServiceRequestDTO request, Boolean isGroup) {
        log.debug("Subscribing resources for User: {}, Plan: {}, Recurring: {}", user.getUserName(), plan.getPlanId(), plan.getRecurringFlag());
        try {
            List<BucketInstance> bucketInstances;
            if (Boolean.TRUE.equals(plan.getRecurringFlag())) {
                log.debug("Provisioning recurring pack for User: {}", user.getUserName());
                bucketInstances = provisionRecurringPack(serviceInstance, plan, user, request, isGroup);
            } else {
                log.debug("Provisioning one-time pack for User: {}", user.getUserName());
                bucketInstances = provisionOneTimePack(serviceInstance, plan, user, request, isGroup);
            }

            // Apply quota to the highest priority bucket if quota is provided
            applyQuotaToPriorityBucket(bucketInstances, request.getQuota());

            return bucketInstances;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error subscribing resources for User: {}", user.getUserName(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error subscribing resources", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<BucketInstance> provisionRecurringPack(ServiceInstance serviceInstance, Plan plan, UserEntity user,
                                                        ActiveServiceRequestDTO request, Boolean isGroup) {
        log.debug("Starting recurring pack provisioning for User: {}, Plan: {}", user.getUserName(), plan.getPlanId());
        try {
            setBasicServiceInstanceData(serviceInstance, plan, user, request, isGroup);

            //   GENERATE SERVICE ID MANUALLY
            serviceInstance.setId(generateServiceId());
            serviceInstance.setCreatedAt(LocalDateTime.now());

            log.debug("Basic service instance data set for User: {}", serviceInstance.getUsername());

            //   READ-ONLY: Check if service already exists
            boolean serviceExists = serviceInstanceRepository
                    .existsByUsernameAndPlanId(serviceInstance.getUsername(), plan.getPlanId());

            if (serviceExists) {
                log.error("Service already exists for User: {}, Plan: {}", serviceInstance.getUsername(), plan.getPlanId());
                throw new AAAException(
                        LogMessages.ERROR_POLICY_CONFLICT,
                        "User already has an active service with plan: " + plan.getPlanId(),
                        HttpStatus.CONFLICT
                );
            }

            setCycleManagementProperties(serviceInstance, plan, user);
            log.debug("Cycle management properties set - Cycle Start: {}, Cycle End: {}, Next Cycle: {}",
                    serviceInstance.getServiceCycleStartDate(),
                    serviceInstance.getServiceCycleEndDate(),
                    serviceInstance.getNextCycleStartDate());

            log.info("Service instance built with ID: {} for User: {} (not saved to DB)",
                    serviceInstance.getId(), user.getUserName());

            Boolean prorationFlag = plan.getQuotaProrationFlag();
            log.debug("Provisioning quota with proration flag: {} for Service Instance ID: {}",
                    prorationFlag, serviceInstance.getId());

            List<BucketInstance> bucketInstances = provisionQuota(prorationFlag, serviceInstance, plan.getPlanId());

            log.info("Recurring pack provisioning completed for User: {}, Service Instance ID: {}",
                    user.getUserName(), serviceInstance.getId());

            return bucketInstances;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning recurring pack for User: {}, Plan: {}", user.getUserName(), plan.getPlanId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error provisioning recurring pack", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<BucketInstance> provisionOneTimePack(ServiceInstance serviceInstance, Plan plan, UserEntity user,
                                                      ActiveServiceRequestDTO request, Boolean isGroup) {
        log.debug("Starting one-time pack provisioning for User: {}, Plan: {}", user.getUserName(), plan.getPlanId());
        try {
            if (request.getServiceEndDate() == null) {
                throw new AAAException(LogMessages.ERROR_BAD_REQUEST,
                        "Service end date is mandatory for One-Time Packs", HttpStatus.BAD_REQUEST);
            }

            setBasicServiceInstanceData(serviceInstance, plan, user, request, isGroup);

            //   GENERATE SERVICE ID MANUALLY
            serviceInstance.setId(generateServiceId());
            serviceInstance.setCreatedAt(LocalDateTime.now());

            log.debug("Basic service instance data set for one-time pack");

            log.info("Service instance built with ID: {} for one-time pack (not saved to DB)",
                    serviceInstance.getId());

            Boolean prorationFlag = plan.getQuotaProrationFlag();
            log.debug("Provisioning quota for one-time pack with proration flag: {}", prorationFlag);

            List<BucketInstance> bucketInstances = provisionQuota(prorationFlag, serviceInstance, plan.getPlanId());

            log.info("One-time pack provisioning completed for User: {}, Service Instance ID: {}",
                    user.getUserName(), serviceInstance.getId());

            return bucketInstances;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning one-time pack for User: {}, Plan: {}", user.getUserName(), plan.getPlanId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error provisioning one-time pack", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<BucketInstance> provisionQuota(Boolean prorationFlag, ServiceInstance serviceInstance, String planId) {
        log.debug("Starting quota provisioning for Service Instance ID: {}, Plan: {}, Proration: {}",
                serviceInstance.getId(), planId, prorationFlag);
        try {
            //   READ-ONLY: Fetch quota details
            List<PlanToBucket> quotaDetails = planToBucketRepository.findByPlanId(planId);
            if (quotaDetails == null || quotaDetails.isEmpty()) {
                log.error("No quota details found for Plan ID: {}", planId);
                throw new AAAException(LogMessages.ERROR_NOT_FOUND, "NO_QUOTA_DETAILS_FOUND", HttpStatus.NOT_FOUND);
            }
            log.debug("Found {} quota details for Plan ID: {}", quotaDetails.size(), planId);

            // Batch-fetch all Bucket and QOSProfile records in 2 queries instead of 2N
            List<String> bucketIds = quotaDetails.stream()
                    .map(PlanToBucket::getBucketId)
                    .collect(Collectors.toList());
            Map<String, Bucket> bucketMap = bucketRepository.findAllById(bucketIds).stream()
                    .collect(Collectors.toMap(Bucket::getBucketId, Function.identity()));

            List<Long> qosIds = bucketMap.values().stream()
                    .map(Bucket::getQosId)
                    .distinct()
                    .collect(Collectors.toList());
            Map<Long, String> qosCodeMap = qosProfileRepository.findAllById(qosIds).stream()
                    .collect(Collectors.toMap(QOSProfile::getId, QOSProfile::getBngCode));

            List<BucketInstance> bucketInstances;
            if (Boolean.FALSE.equals(prorationFlag)) {
                log.debug("Performing direct quota provision for Service Instance ID: {}", serviceInstance.getId());
                bucketInstances = directQuotaProvision(quotaDetails, serviceInstance, bucketMap, qosCodeMap);
            } else {
                Double prorationFactor = getProrationFactor(serviceInstance);
                log.debug("Performing prorated quota provision with factor: {} for Service Instance ID: {}",
                        prorationFactor, serviceInstance.getId());
                bucketInstances = proratedQuotaProvision(prorationFactor, quotaDetails, serviceInstance, bucketMap, qosCodeMap);
            }

            log.info("Quota provisioning completed for Service Instance ID: {}", serviceInstance.getId());
            return bucketInstances;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning quota for Service Instance ID: {}, Plan: {}",
                    serviceInstance.getId(), planId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error provisioning quota for Service Instance", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<BucketInstance> proratedQuotaProvision(Double prorationFactor, List<PlanToBucket> quotaDetails,
                                                        ServiceInstance serviceInstance,
                                                        Map<String, Bucket> bucketMap, Map<Long, String> qosCodeMap) {
        log.debug("Starting prorated quota provision with factor: {} for Service Instance ID: {}",
                prorationFactor, serviceInstance.getId());
        List<BucketInstance> bucketInstanceList = new ArrayList<>();
        try {
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();

                //   GENERATE BUCKET ID MANUALLY
                bucketInstance.setId(generateBucketInstanceId());
                bucketInstance.setUpdatedAt(LocalDateTime.now());

                setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket, bucketMap, qosCodeMap);

                if (Boolean.FALSE.equals(planToBucket.getIsUnlimited())) {
                    Long originalQuota = planToBucket.getInitialQuota();
                    if (originalQuota != null) {
                        Long proratedQuota = Math.round(originalQuota * prorationFactor);

                        log.debug("Bucket ID: {} - Original quota: {}, Prorated quota: {} (Factor: {})",
                                planToBucket.getBucketId(), originalQuota, proratedQuota, prorationFactor);

                        bucketInstance.setCurrentBalance(proratedQuota);
                        bucketInstance.setInitialBalance(proratedQuota);
                    }
                }
                bucketInstanceList.add(bucketInstance);
            }

            log.info("Built {} prorated bucket instances for Service Instance ID: {} (not saved to DB)",
                    bucketInstanceList.size(), serviceInstance.getId());

            return bucketInstanceList;
        } catch (NumberFormatException ex) {
            log.error("Invalid quota value format during prorated provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "Invalid quota value format",
                    HttpStatus.BAD_REQUEST
            );
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during prorated quota provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error during prorated quota provision",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private List<BucketInstance> directQuotaProvision(List<PlanToBucket> quotaDetails, ServiceInstance serviceInstance,
                                                      Map<String, Bucket> bucketMap, Map<Long, String> qosCodeMap) {
        log.debug("Starting direct quota provision for Service Instance ID: {}, Quota count: {}",
                serviceInstance.getId(), quotaDetails.size());
        List<BucketInstance> bucketInstanceList = new ArrayList<>();
        try {
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();

                //   GENERATE BUCKET ID MANUALLY
                bucketInstance.setId(generateBucketInstanceId());
                bucketInstance.setUpdatedAt(LocalDateTime.now());

                setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket, bucketMap, qosCodeMap);
                log.debug("Bucket provisioned - Bucket ID: {}, Initial quota: {}",
                        planToBucket.getBucketId(), planToBucket.getInitialQuota());
                bucketInstanceList.add(bucketInstance);
            }

            log.info("Built {} bucket instances for the Service Instance ID: {} (not saved to DB)",
                    bucketInstanceList.size(), serviceInstance.getId());

            return bucketInstanceList;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during direct quota provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Error during direct quota provision", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @SneakyThrows
    @Transactional(readOnly = true)
    public UpdateResponseDTO updateService(String userId, String planId, String requestId, UpdateRequestDTO updateDto) {
        log.info("Updating service for User ID: {}, Plan ID: {}, Request ID: {}", userId, planId, requestId);
        try {
            // Run all independent DB lookups in parallel
            CompletableFuture<Object>[] results = asyncAdaptor.supplyAll(
                    2000L,
                    () -> serviceInstanceRepository.existsByRequestId(requestId),
                    () -> serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(userId, planId),
                    () -> userRepository.findByUserName(userId).orElse(null)
            );

            boolean requestExists = (boolean) results[0].get();
            if (requestExists) {
                log.error("Service update with Request ID already exists: {}", requestId);
                throw new AAAException(
                        LogMessages.DUPLICATE_REQUEST_ID,
                        "Service update with request ID already exists: " + requestId,
                        HttpStatus.CONFLICT
                );
            }

            @SuppressWarnings("unchecked")
            ServiceInstance serviceInstance = ((java.util.Optional<ServiceInstance>) results[1].get())
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            "No active or suspend service found for the given identifiers.",
                            HttpStatus.NOT_FOUND
                    ));

            UserEntity user = (UserEntity) results[2].get();

            if (serviceInstance.getStatus().equalsIgnoreCase(INACTIVE)) {
                throw new AAAException(
                        LogMessages.ERROR_POLICY_CONFLICT,
                        "Inactive Services Not Allowed to Update",
                        HttpStatus.CONFLICT
                );
            }

            if (updateDto.getStatus() != null && mapStatus(updateDto.getStatus()).equalsIgnoreCase(INACTIVE)) {
                return handleServiceInactivation(serviceInstance, userId, requestId);
            }

            return performServiceUpdate(serviceInstance, updateDto, userId, requestId, user);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Error updating service for Request ID: {}", requestId, e);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private UpdateResponseDTO handleServiceInactivation(ServiceInstance serviceInstance, String userId, String requestId) {
        log.info("Status change to Inactive detected - triggering deletion process for Service ID: {}",
                serviceInstance.getId());

        serviceInstance.setRequestId(requestId);

        List<BucketInstance> buckets = bucketInstanceRepository.findByServiceId(serviceInstance.getId());
        BucketInstance priorityBucket = buckets.stream()
                .min(Comparator.comparing(BucketInstance::getPriority))
                .orElse(null);

        serviceInstance.setStatus(INACTIVE);
        serviceInstance.setUpdatedAt(LocalDateTime.now());

        publishServiceDeletedEvents(serviceInstance, buckets);

        // Fire CoA async — Kafka write already committed above
        Long capturedServiceId = serviceInstance.getId();
        String capturedUsername = serviceInstance.getUsername();
        CompletableFuture.runAsync(() -> sendServiceStatusCoAAsync(capturedUsername, capturedServiceId, "INACTIVE"));

        log.info("Service deletion events published for Service ID: {}", serviceInstance.getId());

        return buildUpdateSuccessResponse(serviceInstance, userId, requestId, priorityBucket);
    }

    private UpdateResponseDTO performServiceUpdate(ServiceInstance serviceInstance, UpdateRequestDTO updateDto,
                                                   String userId, String requestId, UserEntity user) {
        serviceInstance.setRequestId(requestId);

        // Capture BEFORE anything is overwritten
        String oldStatus = serviceInstance.getStatus();
        log.info("Service status transition check - user='{}', currentStatus='{}'", userId, oldStatus);

        LocalDateTime newStartDate = updateDto.getServiceStartDate() != null ?
                updateDto.getServiceStartDate() : serviceInstance.getServiceStartDate();
        LocalDateTime newEndDate = updateDto.getServiceEndDate() != null ?
                updateDto.getServiceEndDate() : serviceInstance.getExpiryDate();

        if (updateDto.getServiceStartDate() != null || updateDto.getServiceEndDate() != null) {
            validateServiceDates(newStartDate, newEndDate, "Service Update");
        }

        if (updateDto.getServiceStartDate() != null)
            serviceInstance.setServiceStartDate(updateDto.getServiceStartDate());
        if (updateDto.getServiceEndDate() != null) serviceInstance.setExpiryDate(updateDto.getServiceEndDate());
        if (updateDto.getStatus() != null) serviceInstance.setStatus(mapStatus(updateDto.getStatus()));

        serviceInstance.setUpdatedAt(LocalDateTime.now());

        BucketInstance bucketInstance = bucketInstanceRepository
                .findFirstByServiceIdOrderByPriorityAsc(serviceInstance.getId())
                .orElseThrow(() -> new AAAException(
                        LogMessages.ERROR_NOT_FOUND,
                        "No active bucket instances found for " + serviceInstance.getId(),
                        HttpStatus.NOT_FOUND
                ));

        List<BucketInstance> updatedBuckets = manageMainBucketQuota(updateDto, bucketInstance);
        publishServiceUpdatedEvents(serviceInstance, updatedBuckets);
        accountingCacheManagementService.syncBuckets(
                updatedBuckets,
                user != null ? user.getSessionTimeout() : String.valueOf(86400L),
                user != null && user.getConcurrency() != null ? user.getConcurrency() : 5L,
                serviceInstance);

        if (updateDto.getStatus() != null) {
            String newStatus = serviceInstance.getStatus();
            log.info("Evaluating CoA for status transition: '{}' -> '{}', user='{}', serviceId='{}'",
                    oldStatus, newStatus, userId, serviceInstance.getId());

            boolean shouldSendCoA =
                    (oldStatus.equals("Active") && newStatus.equals("Suspended")) ||
                            (oldStatus.equals("Suspended") && newStatus.equals("Active"));

            if (shouldSendCoA) {
                log.info("CoA required for transition '{}' -> '{}', user='{}', serviceId='{}'",
                        oldStatus, newStatus, userId, serviceInstance.getId());

                Long capturedServiceId = serviceInstance.getId();
                String capturedUsername = serviceInstance.getUsername();
                String capturedStatus = newStatus.toUpperCase();
                CompletableFuture.runAsync(
                        () -> sendServiceStatusCoAAsync(capturedUsername, capturedServiceId, capturedStatus));
            } else {
                log.info("No CoA required for transition '{}' -> '{}', user='{}'", oldStatus, newStatus, userId);
            }
        } else {
            log.debug("No status change in request for user='{}', skipping CoA", userId);
        }

        log.info("Successfully published service update events for Request ID: {}", requestId);
        return buildUpdateSuccessResponse(serviceInstance, userId, requestId, bucketInstance);
    }

    private List<BucketInstance> manageMainBucketQuota(UpdateRequestDTO updateDto, BucketInstance bucketInstance) {
        boolean isUpdated = false;

        if (updateDto.getQuota() != null && updateDto.getQuota() > 0L) {
            updateInitialQuotaOfMainBucket(updateDto, bucketInstance);
            isUpdated = true;
        }
        if (updateDto.getBalanceQuota() != null && updateDto.getBalanceQuota() > 0L) {
            updateBalanceQuotaOfMainBucket(bucketInstance, updateDto.getBalanceQuota());
            isUpdated = true;
        }

        if (isUpdated) {
            bucketInstance.setUpdatedAt(LocalDateTime.now());
            return List.of(bucketInstance);
        }

        return List.of();
    }

    private void updateBalanceQuotaOfMainBucket(BucketInstance bucketInstance, Long balanceQuota) {
        long updatedRemainingQuota = bucketInstance.getCurrentBalance() + balanceQuota;
        if (updatedRemainingQuota > bucketInstance.getInitialBalance()) {
            throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT,
                    "Balance Quota Exceeds Limit", HttpStatus.BAD_REQUEST);
        } else {
            bucketInstance.setCurrentBalance(updatedRemainingQuota);
        }
    }

    private void updateInitialQuotaOfMainBucket(UpdateRequestDTO updateDto, BucketInstance bucketInstance) {
        try {
            if ( bucketInstance.getIsUnlimited())
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT,
                        "Quota Cannot Be Updated For Unlimited Buckets", HttpStatus.BAD_REQUEST);
            Long newInitialBalance = updateDto.getQuota() + bucketInstance.getInitialBalance();
            bucketInstance.setInitialBalance(newInitialBalance);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                    "Error Occurred During Initial Quota Update", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(readOnly = true) //   READ-ONLY
    public DeleteResponseDTO deleteService(String userId, String planId, String requestId) {
        log.info("Deleting service for User ID: {}, Plan ID: {}, Request ID: {}", userId, planId, requestId);
        try {
            ServiceInstance serviceInstance = serviceInstanceRepository
                    .findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(userId, planId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.SERVICE_OR_USER_UNAVAILABLE,
                            "Active Service or User Unavailable",
                            HttpStatus.NOT_FOUND
                    ));

            List<BucketInstance> buckets = bucketInstanceRepository.findByServiceId(serviceInstance.getId());

            publishServiceDeletedEvents(serviceInstance, buckets);

            // Fire CoA async — best-effort, never blocks the response
            Long capturedServiceId = serviceInstance.getId();
            CompletableFuture.runAsync(() -> sendServiceDeleteCoAAsync(userId, capturedServiceId));

            log.info("Successfully published service deletion events for Request ID: {}", requestId);
            return buildDeleteSuccessResponse(userId, planId);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Error deleting service for Request ID: {}", requestId, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    // ========== KAFKA EVENT PUBLISHING METHODS ==========

    private void publishServiceCreatedEvents(ServiceInstance service, List<BucketInstance> buckets) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent(CREATED, service);

            // Bundle bucket CREATE as relatedWrites
            List<DBWriteRequestGeneric> bucketWrites = buckets.stream()
                    .map(b -> eventMapper.toBucketDBWriteEvent(CREATED, b, service.getUsername()))
                    .toList();
            mainEvent.setRelatedWrites(bucketWrites);

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                log.error("Error occurred while publishing service into Kafka: {}",result.getError());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Something went wrong",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            serviceTTLManager.publishServiceTTL(service, buckets);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Something went wrong",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void publishServiceUpdatedEvents(ServiceInstance service, List<BucketInstance> updatedBuckets) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent(UPDATE, service);

            if (!updatedBuckets.isEmpty()) {
                List<DBWriteRequestGeneric> bucketWrites = updatedBuckets.stream()
                        .map(b -> eventMapper.toBucketDBWriteEvent(UPDATE, b, service.getUsername()))
                        .toList();
                mainEvent.setRelatedWrites(bucketWrites);
            }

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                log.error("Complete Error occurred while publishing payload into Kafka: {}", result.getError());
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Something went wrong",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            // Only re-publish TTLs for buckets that were actually modified
            if (!updatedBuckets.isEmpty()) {
                serviceTTLManager.publishServiceTTL(service, updatedBuckets);
            }

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error(LogMessages.ERROR_PUBLISHING_INTO_KAFKA, e.getMessage());
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Something went wrong",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void publishServiceDeletedEvents(ServiceInstance service, List<BucketInstance> buckets) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent(DELETE, service);

            if (!buckets.isEmpty()) {
                List<DBWriteRequestGeneric> bucketWrites = buckets.stream()
                        .map(b -> eventMapper.toBucketDBWriteEvent(DELETE, b, service.getUsername()))
                        .toList();
                mainEvent.setRelatedWrites(bucketWrites);
            }

            kafkaEventPublisher.publishDBWriteEvent(mainEvent);

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
    // ========== HELPER METHODS (Keep all existing validation logic) ==========

    private void setCycleManagementProperties(ServiceInstance serviceInstance, Plan plan, UserEntity user) {
        log.debug("Setting cycle management properties for User: {}, Billing: {}", user.getUserName(), user.getBilling());
        try {
            LocalDateTime serviceStartDate = serviceInstance.getServiceStartDate();
            String billing = user.getBilling();
            Integer cycleDate = null;

            if (user.getCycleDate() != null && "3".equals(billing)) {
                cycleDate = user.getCycleDate();
            }
            long validityDays;

            LocalDateTime cycleStartDate;
            LocalDateTime cycleEndDate;
            LocalDateTime nextCycleDate;

            if ("3".equals(billing)) {
                log.debug("Processing billing type 3 (Cycle-based) with cycle date: {}", cycleDate);
                cycleStartDate = getCycleStartDate(serviceStartDate, cycleDate);
                validityDays = getNumberOfValidityDays(plan.getRecurringPeriod(), user.getBilling(), cycleStartDate);
                cycleStartDate = adjustCycleStartForServiceStart(cycleStartDate, serviceStartDate, validityDays, plan.getRecurringPeriod());
                cycleEndDate = cycleStartDate.plusDays(validityDays - 1).toLocalDate().atTime(23, 59, 59);
                nextCycleDate = cycleEndDate.plusSeconds(1);

            } else if ("2".equals(billing)) {
                log.debug("Processing billing type 2 (Calendar month start)");
                cycleStartDate = serviceStartDate.withDayOfMonth(1);
                validityDays = getNumberOfValidityDays(plan.getRecurringPeriod(), user.getBilling(), cycleStartDate);
                cycleStartDate = adjustCycleStartForServiceStart(cycleStartDate, serviceStartDate, validityDays, plan.getRecurringPeriod());
                cycleEndDate = cycleStartDate.plusDays(validityDays - 1).toLocalDate().atTime(23, 59, 59);
                nextCycleDate = cycleEndDate.plusSeconds(1);
            } else {
                log.debug("Processing billing type {} (Direct validity)", billing);
                cycleStartDate = serviceStartDate.toLocalDate().atStartOfDay();
                validityDays = getNumberOfValidityDays(plan.getRecurringPeriod(), user.getBilling(), cycleStartDate);
                cycleEndDate = cycleStartDate.plusDays(validityDays - 1).toLocalDate().atTime(23, 59, 59);
                nextCycleDate = cycleEndDate.plusSeconds(1);
            }

            if (serviceInstance.getExpiryDate() != null && nextCycleDate.isAfter(serviceInstance.getExpiryDate()) || Boolean.FALSE.equals(plan.getRecurringFlag())) {
                log.debug("Setting next cycle date to null");
                nextCycleDate = null;
            }

            serviceInstance.setServiceCycleStartDate(cycleStartDate);
            serviceInstance.setServiceCycleEndDate(cycleEndDate);
            serviceInstance.setNextCycleStartDate(nextCycleDate);
            serviceInstance.setBilling(billing);
            serviceInstance.setCycleDate(cycleDate);

            log.debug("Cycle management properties set successfully");

        } catch (Exception ex) {
            log.error("Error setting cycle management properties for User: {}", user.getUserName(), ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error setting cycle management properties: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private LocalDateTime adjustCycleStartForServiceStart(LocalDateTime initialCycleStart, LocalDateTime serviceStartDate, long validityDays, String recurringPeriod) {
        if (!"WEEKLY".equalsIgnoreCase(recurringPeriod) && !"DAILY".equalsIgnoreCase(recurringPeriod)) {
            return initialCycleStart;
        }

        // Reset time to 00:00:00
        LocalDateTime cycleStartDate = initialCycleStart.toLocalDate().atStartOfDay();
        LocalDateTime cycleEndDate = cycleStartDate.plusDays(validityDays - 1);

        while (serviceStartDate.toLocalDate().isAfter(cycleEndDate.toLocalDate())) {
            cycleStartDate = cycleStartDate.plusDays(validityDays);
            cycleEndDate = cycleStartDate.plusDays(validityDays - 1);
            log.debug("Adjusted cycle start to: {}, cycle end to: {}", cycleStartDate, cycleEndDate);
        }

        return cycleStartDate;
    }

    private LocalDateTime getCycleStartDate(LocalDateTime serviceStartDate, Integer cycleDate) {
        log.debug("Calculating cycle start date for service start: {}, cycle date: {}", serviceStartDate, cycleDate);

        LocalDateTime currentMonthCycleDate = serviceStartDate.withDayOfMonth(cycleDate)
                .toLocalDate().atStartOfDay();

        if (serviceStartDate.toLocalDate().isBefore(currentMonthCycleDate.toLocalDate())) {
            LocalDateTime previousMonth = currentMonthCycleDate.minusMonths(1);
            log.debug("Cycle start date is in previous month: {}", previousMonth);
            return previousMonth;
        } else {
            log.debug("Cycle start date is in current month: {}", currentMonthCycleDate);
            return currentMonthCycleDate;
        }
    }

    private Integer getNumberOfValidityDays(String recurringPeriod, String billing, LocalDateTime currentBillCycleDate) {
        log.debug("Calculating validity days for recurring period: {}, billing: {}", recurringPeriod, billing);
        try {
            if ("DAILY".equalsIgnoreCase(recurringPeriod)) {
                return 1;
            }

            if ("WEEKLY".equalsIgnoreCase(recurringPeriod)) {
                return 7;
            }

            if ("2".equals(billing) || "1".equals(billing)) {
                int days = currentBillCycleDate.toLocalDate().lengthOfMonth();
                log.debug("Validity days: {} (Calendar month length)", days);
                return days;
            }

            LocalDateTime nextBillCycleDate = currentBillCycleDate.plusMonths(1);
            int days = (int) ChronoUnit.DAYS.between(
                    currentBillCycleDate.toLocalDate(),
                    nextBillCycleDate.toLocalDate()
            );
            log.debug("Validity days: {} (Days till next cycle)", days);
            return days;

        } catch (Exception ex) {
            log.error("Error calculating validity days", ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error calculating validity days: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void validateServiceDates(LocalDateTime startDate, LocalDateTime endDate, String context) {
        log.debug("{} - Validating service dates: Start={}, End={}", context, startDate, endDate);

        LocalDateTime now = LocalDateTime.now();

        if (startDate == null) {
            log.error("{} - Service start date is null", context);
            throw new AAAException(
                    LogMessages.ERROR_BAD_REQUEST,
                    "Service start date is mandatory",
                    HttpStatus.BAD_REQUEST
            );
        }

//        if (startDate.toLocalDate().isBefore(now.toLocalDate())) {
//            log.error("{} - Service start date {} is in the past", context, startDate);
//            throw new AAAException(
//                    LogMessages.ERROR_BAD_REQUEST,
//                    "Service start date cannot be in the past. Must be today or a future date.",
//                    HttpStatus.BAD_REQUEST
//            );
//        }

        if (endDate != null) {
            if (endDate.isBefore(startDate) || endDate.isEqual(startDate)) {
                log.error("{} - Service end date {} is not after start date {}", context, endDate, startDate);
                throw new AAAException(
                        LogMessages.ERROR_BAD_REQUEST,
                        "Service end date must be after service start date",
                        HttpStatus.BAD_REQUEST
                );
            }

            if (endDate.toLocalDate().isBefore(now.toLocalDate())) {
                log.error("{} - Service end date {} is in the past", context, endDate);
                throw new AAAException(
                        LogMessages.ERROR_BAD_REQUEST,
                        "Service end date cannot be in the past",
                        HttpStatus.BAD_REQUEST
                );
            }
        }

        log.debug("{} - Service dates validated successfully", context);
    }

    private Double getProrationFactor(ServiceInstance serviceInstance) {
        log.debug("Calculating proration factor for Service Instance ID: {}", serviceInstance.getId());
        try {
            LocalDateTime serviceStartDate = serviceInstance.getServiceStartDate();
            LocalDateTime cycleStartDate = serviceInstance.getServiceCycleStartDate();
            LocalDateTime cycleEndDate = serviceInstance.getServiceCycleEndDate();

            if (serviceStartDate == null || cycleStartDate == null || cycleEndDate == null) {
                log.error("Service dates not properly set");
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Service dates are not properly set for proration calculation",
                        HttpStatus.BAD_REQUEST
                );
            }

            long totalCycleDays = ChronoUnit.DAYS.between(cycleStartDate, cycleEndDate);
            long remainingDays = ChronoUnit.DAYS.between(serviceStartDate, cycleEndDate);

            log.debug("Proration calculation - Total cycle days: {}, Remaining days: {}", totalCycleDays, remainingDays);

            if (totalCycleDays == 0) {
                log.error("Invalid cycle dates");
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Invalid cycle dates: cycle start and end dates are the same",
                        HttpStatus.BAD_REQUEST
                );
            }

            Double prorationFactor = (double) remainingDays / (double) totalCycleDays;
            log.debug("Calculated proration factor: {}", prorationFactor);

            if (prorationFactor < 0 || prorationFactor > 1) {
                log.error("Invalid proration factor calculated: {}", prorationFactor);
                throw new AAAException(
                        LogMessages.ERROR_VALIDATION_FAILED,
                        "Invalid proration factor calculated: " + prorationFactor,
                        HttpStatus.BAD_REQUEST
                );
            }

            return prorationFactor;

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error calculating proration factor", ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Error calculating proration factor: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void setBucketDetails(String bucketId, BucketInstance bucketInstance, ServiceInstance serviceInstance,
                                  PlanToBucket planToBucket, Map<String, Bucket> bucketMap, Map<Long, String> qosCodeMap) {
        log.debug("Setting bucket details for Bucket ID: {}", bucketId);
        try {
            Bucket bucket = bucketMap.get(bucketId);
            if (bucket == null) {
                log.error("Bucket not found: {}", bucketId);
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT,
                        "BUCKET_NOT_FOUND " + bucketId, HttpStatus.NOT_FOUND);
            }

            log.debug("Bucket found - Type: {}, Priority: {}", bucket.getBucketType(), bucket.getPriority());

            String bngCode = qosCodeMap.get(bucket.getQosId());
            if (bngCode == null) {
                log.error("QoS profile not found for QoS ID: {}", bucket.getQosId());
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT,
                        "QOS_PROFILE_NOT_FOUND " + bucket.getQosId(), HttpStatus.NOT_FOUND);
            }

            bucketInstance.setBucketId(bucket.getBucketId());
            bucketInstance.setBucketType(bucket.getBucketType());
            bucketInstance.setPriority(bucket.getPriority());
            bucketInstance.setTimeWindow(bucket.getTimeWindow());
            bucketInstance.setRule(bngCode);
            bucketInstance.setServiceId(serviceInstance.getId());
            bucketInstance.setCarryForward(planToBucket.getCarryForward());
            bucketInstance.setMaxCarryForward(planToBucket.getMaxCarryForward());
            bucketInstance.setTotalCarryForward(planToBucket.getTotalCarryForward());
            bucketInstance.setConsumptionLimit(planToBucket.getConsumptionLimit());
            bucketInstance.setConsumptionLimitWindow(planToBucket.getConsumptionLimitWindow());
            bucketInstance.setCurrentBalance(planToBucket.getInitialQuota());
            bucketInstance.setInitialBalance(planToBucket.getInitialQuota());

            if (Boolean.TRUE.equals(serviceInstance.getRecurringFlag())) {
                bucketInstance.setExpiration(serviceInstance.getServiceCycleEndDate());
            } else {
                bucketInstance.setExpiration(serviceInstance.getExpiryDate());
            }

            bucketInstance.setIsUnlimited(planToBucket.getIsUnlimited());
            bucketInstance.setUsage(0L);

            log.debug("Bucket details set successfully");
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error setting bucket details for Bucket ID: {}", bucketId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void setBasicServiceInstanceData(ServiceInstance serviceInstance, Plan plan, UserEntity user,
                                             ActiveServiceRequestDTO request, Boolean isGroup) {
        log.debug("Setting basic service instance data");
        serviceInstance.setPlanId(plan.getPlanId());
        serviceInstance.setPlanName(plan.getPlanName());
        serviceInstance.setPlanType(plan.getPlanType());
        serviceInstance.setRecurringFlag(plan.getRecurringFlag());

        if (Boolean.TRUE.equals(isGroup)) {
            serviceInstance.setUsername(user.getGroupId());
        } else {
            serviceInstance.setUsername(user.getUserName());
        }

        serviceInstance.setServiceStartDate(request.getServiceStartDate());
        if (Boolean.FALSE.equals(plan.getRecurringFlag())) {
            serviceInstance.setExpiryDate(generateExpiryDate(plan, request.getServiceStartDate(), user));
        } else {
            serviceInstance.setExpiryDate(request.getServiceEndDate() != null ?
                    request.getServiceEndDate() : setDefaultExpiry(request.getServiceStartDate()));
        }

        serviceInstance.setStatus(mapStatus(Integer.valueOf(request.getStatus())));
        serviceInstance.setIsGroup(isGroup);
        serviceInstance.setRequestId(request.getRequestId());

        log.debug("Basic service instance data set");
    }

    private LocalDateTime generateExpiryDate(Plan plan, LocalDateTime serviceStartDate, UserEntity user) {
        String validityType = plan.getValidityType();
        Integer validityPeriod = plan.getValidityPeriod();

        if (validityType == null || validityPeriod == null) {
            throw new AAAException(LogMessages.ERROR_VALIDATION_FAILED,
                    "validityType and validityPeriod are mandatory for one-time packs", HttpStatus.BAD_REQUEST);
        }

        return switch (validityType) {
            case "Minutes" -> serviceStartDate.plusMinutes(validityPeriod);
            case "Hours" -> serviceStartDate.plusHours(validityPeriod);
            case "Days" -> serviceStartDate.plusDays(validityPeriod);
            case "BillCycle" -> calculateBillCycleExpiry(serviceStartDate, validityPeriod, user);
            default -> throw new AAAException(LogMessages.ERROR_VALIDATION_FAILED,
                    "Unknown validityType: " + validityType, HttpStatus.BAD_REQUEST);
        };
    }

    private LocalDateTime calculateBillCycleExpiry(LocalDateTime serviceStartDate, int validityPeriod, UserEntity user) {
        String billing = user.getBilling();
        Integer cycleDate = "3".equals(billing) ? user.getCycleDate() : null;

        LocalDateTime cycleStartDate;
        if ("3".equals(billing)) {
            cycleStartDate = getCycleStartDate(serviceStartDate, cycleDate);
        } else if ("2".equals(billing)) {
            cycleStartDate = serviceStartDate.withDayOfMonth(1).toLocalDate().atStartOfDay();
        } else {
            cycleStartDate = serviceStartDate.toLocalDate().atStartOfDay();
        }

        long validityDays = getNumberOfValidityDays("MONTHLY", billing, cycleStartDate);
        cycleStartDate = adjustCycleStartForServiceStart(cycleStartDate, serviceStartDate, validityDays, "MONTHLY");
        LocalDateTime cycleEnd = cycleStartDate.plusDays(validityDays - 1).toLocalDate().atTime(23, 59, 59);

        for (int i = 1; i < validityPeriod; i++) {
            LocalDateTime nextCycleStart = cycleEnd.plusSeconds(1);
            validityDays = getNumberOfValidityDays("MONTHLY", billing, nextCycleStart);
            cycleEnd = nextCycleStart.plusDays(validityDays - 1).toLocalDate().atTime(23, 59, 59);
        }

        return cycleEnd;
    }

    private static LocalDateTime setDefaultExpiry(@NotNull LocalDateTime serviceStartDate) {
        return serviceStartDate.plusYears(100);
    }

    private void validateUserStatus(UserEntity user, String context) {
        log.debug("{} - Validating user status for: {}", context, user.getUserName());

        if (user.getStatus() == null) {
            log.error("{} - User status is null", context);
            throw new AAAException(
                    LogMessages.ERROR_VALIDATION_FAILED,
                    "User status is not set",
                    HttpStatus.BAD_REQUEST
            );
        }

        if (!ACTIVE.equalsIgnoreCase(String.valueOf(user.getStatus()))) {
            log.error("{} - User is not active. Status: {}", context, user.getStatus());
            throw new AAAException(
                    LogMessages.ERROR_POLICY_CONFLICT,
                    "Cannot activate service for inactive user: " + user.getUserName(),
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        log.debug("{} - User status validated successfully", context);
    }

    private static String mapStatus(Integer status) {
        return switch (status) {
            case 1 -> ACTIVE;
            case 2 -> SUSPENDED;
            case 3 -> INACTIVE;
            default -> throw new AAAException(LogMessages.ERROR_BAD_REQUEST,
                    "Invalid status: " + status, HttpStatus.BAD_REQUEST);
        };
    }

    private UpdateResponseDTO buildUpdateSuccessResponse(ServiceInstance entity, String userId, String requestId, BucketInstance priorityBucket) {
        Long finalQuota = priorityBucket != null ? priorityBucket.getInitialBalance() : null;
        Long balanceQuota = priorityBucket != null ? priorityBucket.getCurrentBalance() : null;

        return UpdateResponseDTO.builder()
                .requestId(requestId)  // ADD THIS
                .userId(userId)
                .planId(entity.getPlanId())
                .planName(entity.getPlanName())
                .status(mapStatusToCode(entity.getStatus()))
                .finalQuota(finalQuota)
                .balanceQuota(balanceQuota)
                .serviceStartDate(entity.getServiceStartDate())
                .serviceEndDate(entity.getExpiryDate())
                .nextCycleStartDate(entity.getNextCycleStartDate())
                .updatedDate(entity.getUpdatedAt())
                .build();
    }

    private DeleteResponseDTO buildDeleteSuccessResponse(String userId, String planId) {
        return DeleteResponseDTO.builder()
                .userId(userId)
                .planId(planId)
                .removedDate(LocalDateTime.now())
                .build();
    }

    private static Integer mapStatusToCode(String status) {
        return switch (status) {
            case ACTIVE -> 1;
            case SUSPENDED -> 2;
            case INACTIVE -> 3;
            default -> throw new AAAException(LogMessages.ERROR_BAD_REQUEST,
                    "Invalid status: " + status, HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * Sends a service status CoA request asynchronously.
     * Best-effort: failure is logged but never blocks the response.
     */
    private void sendServiceStatusCoAAsync(String username, Long serviceId, String newStatus) {
        try {
            log.info("Sending async service status CoA for user '{}', serviceId={}, status={}",
                    username, serviceId, newStatus);
            coAManagementService.sendServiceStatusCoARequest(username, serviceId, newStatus);
            log.info("Async service status CoA completed for user '{}'", username);
        } catch (Exception e) {
            log.warn("Async service status CoA failed for user '{}', serviceId={}, status={}: {}",
                    username, serviceId, newStatus, e.getMessage(), e);
        }
    }

    /**
     * Sends a service delete CoA request asynchronously.
     * Best-effort: failure is logged but never blocks the response.
     */
    private void sendServiceDeleteCoAAsync(String username, Long serviceId) {
        try {
            log.info("Sending async service delete CoA for user '{}', serviceId={}", username, serviceId);
            coAManagementService.sendServiceDeleteCoARequest(username, serviceId);
            log.info("Async service delete CoA completed for user '{}'", username);
        } catch (Exception e) {
            log.warn("Async service delete CoA failed for user '{}', serviceId={}: {}",
                    username, serviceId, e.getMessage(), e);
        }
    }
}
