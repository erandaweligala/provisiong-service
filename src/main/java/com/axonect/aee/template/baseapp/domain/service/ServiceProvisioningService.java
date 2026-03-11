package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.ActiveServiceRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ActiveServiceResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.DeleteResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.UpdateResponseDTO;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.events.BucketEvent;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.ServiceEvent;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
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

    private static final String ACTIVE = "Active";
    private static final String INACTIVE = "Inactive";

    //   MANUAL ID GENERATION (since no @PrePersist)
    /**
     * Generates a fast, non-cryptographic service ID.
     * <p>
     * This ID is intended for internal identification only and
     * does not require cryptographic randomness.
     */
    @SuppressWarnings("java:S2245")
    private Long generateServiceId() {
        long timestampPart = System.currentTimeMillis() % 1_000_000;
        int random = ThreadLocalRandom.current().nextInt(10_000);
        return timestampPart * 10_000L + random;
    }


    /**
     * Generates a non-cryptographic, fast unique bucket instance ID.
     * <p>
     * This ID is used for internal correlation only and does NOT require
     * cryptographic randomness.
     */
    @SuppressWarnings("java:S2245")
    private Long generateBucketInstanceId() {
        long timestampPart = System.currentTimeMillis() % 1_000_000;
        int random = ThreadLocalRandom.current().nextInt(10_000);
        return timestampPart * 10_000L + random;
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

            if (isGroup) {
                var result = activateGroupService(request);
                serviceInstance = result.serviceInstance;
                bucketInstances = result.bucketInstances;
            } else {
                var result = activateIndividualService(request);
                serviceInstance = result.serviceInstance;
                bucketInstances = result.bucketInstances;
            }

            publishServiceCreatedEvents(serviceInstance, bucketInstances);

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

    //  HELPER CLASS to return both service and buckets
    private static class ServiceActivationResult {
        ServiceInstance serviceInstance;
        List<BucketInstance> bucketInstances;

        ServiceActivationResult(ServiceInstance serviceInstance, List<BucketInstance> bucketInstances) {
            this.serviceInstance = serviceInstance;
            this.bucketInstances = bucketInstances;
        }
    }

    private ServiceActivationResult activateGroupService(ActiveServiceRequestDTO request) {
        log.debug("Starting group service activation for Group: {}, Plan: {}", request.getUserId(), request.getPlanId());
        try {
            String groupId = request.getUserId();
            String planId = request.getPlanId();

            if (request.getStatus() != null && "3".equals(request.getStatus())) {
                throw new AAAException(
                        LogMessages.ERROR_BAD_REQUEST,
                        "Cannot activate service with Inactive status. Only Active or Barred status is allowed.",
                        HttpStatus.BAD_REQUEST
                );
            }

            //  READ-ONLY: Fetch group user and plan in parallel — independent queries
            CompletableFuture<UserEntity> userFuture = CompletableFuture.supplyAsync(() ->
                    userRepository.findFirstByGroupId(groupId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "GROUP_NOT_FOUND", HttpStatus.NOT_FOUND)));
            CompletableFuture<Plan> planFuture = CompletableFuture.supplyAsync(() ->
                    planRepository.findByPlanId(planId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "PLAN_DOES_NOT_EXIST", HttpStatus.NOT_FOUND)));

            UserEntity user = userFuture.join();
            log.debug("Group found: {}", groupId);
            validateUserStatus(user, "Group Service Activation");

            Plan plan = planFuture.join();
            log.debug("Plan found: {} ({}), Recurring: {}", plan.getPlanName(), plan.getPlanType(), plan.getRecurringFlag());

            if (!plan.getStatus().equalsIgnoreCase(ACTIVE)) {
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "PLAN_IS_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            //   BUILD service instance (no DB save)
            ServiceInstance serviceInstance = new ServiceInstance();
            List<BucketInstance> bucketInstances = subscribeResources(user, plan, serviceInstance, request, true);

            log.info("Group service activation completed successfully for Group: {}", groupId);
            return new ServiceActivationResult(serviceInstance, bucketInstances);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during group service activation", ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ServiceActivationResult activateIndividualService(ActiveServiceRequestDTO request) {
        log.debug("Starting individual service activation for User: {}, Plan: {}", request.getUserId(), request.getPlanId());
        try {
            String userName = request.getUserId();
            String planId = request.getPlanId();

            if (request.getStatus() != null && "3".equals(request.getStatus())) {
                throw new AAAException(
                        LogMessages.ERROR_BAD_REQUEST,
                        "Cannot activate service with Inactive status. Only Active or Suspended status is allowed.",
                        HttpStatus.BAD_REQUEST
                );
            }

            //   READ-ONLY: Fetch user and plan in parallel — independent queries
            CompletableFuture<UserEntity> userFuture = CompletableFuture.supplyAsync(() ->
                    userRepository.findByUserName(userName)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "USER_NOT_FOUND", HttpStatus.NOT_FOUND)));
            CompletableFuture<Plan> planFuture = CompletableFuture.supplyAsync(() ->
                    planRepository.findByPlanId(planId)
                            .orElseThrow(() -> new AAAException(LogMessages.ERROR_NOT_FOUND, "PLAN_DOES_NOT_EXIST", HttpStatus.NOT_FOUND)));

            UserEntity user = userFuture.join();
            log.debug("User found: {} with billing type: {}", userName, user.getBilling());
            validateUserStatus(user, "Individual Service Activation");

            Plan plan = planFuture.join();
            log.debug("Plan found: {} ({}), Recurring: {}", plan.getPlanName(), plan.getPlanType(), plan.getRecurringFlag());

            if (!plan.getStatus().equalsIgnoreCase(ACTIVE)) {
                throw new AAAException(LogMessages.ERROR_POLICY_CONFLICT, "PLAN_IS_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            //   BUILD service instance (no DB save)
            ServiceInstance serviceInstance = new ServiceInstance();
            List<BucketInstance> bucketInstances = subscribeResources(user, plan, serviceInstance, request, false);

            log.info("Individual service activation completed successfully for User: {}", userName);
            return new ServiceActivationResult(serviceInstance, bucketInstances);
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during individual service activation", ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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

            validateServiceDates(
                    request.getServiceStartDate(),
                    request.getServiceEndDate(),
                    "One-Time Pack Provisioning"
            );

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
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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

            List<BucketInstance> bucketInstances;
            if (Boolean.FALSE.equals(prorationFlag)) {
                log.debug("Performing direct quota provision for Service Instance ID: {}", serviceInstance.getId());
                bucketInstances = directQuotaProvision(quotaDetails, serviceInstance);
            } else {
                Double prorationFactor = getProrationFactor(serviceInstance);
                log.debug("Performing prorated quota provision with factor: {} for Service Instance ID: {}",
                        prorationFactor, serviceInstance.getId());
                bucketInstances = proratedQuotaProvision(prorationFactor, quotaDetails, serviceInstance);
            }

            log.info("Quota provisioning completed for Service Instance ID: {}", serviceInstance.getId());
            return bucketInstances;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error provisioning quota for Service Instance ID: {}, Plan: {}",
                    serviceInstance.getId(), planId, ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<BucketInstance> proratedQuotaProvision(Double prorationFactor, List<PlanToBucket> quotaDetails,
                                                        ServiceInstance serviceInstance) {
        log.debug("Starting prorated quota provision with factor: {} for Service Instance ID: {}",
                prorationFactor, serviceInstance.getId());
        List<BucketInstance> bucketInstanceList = new ArrayList<>();
        try {
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();

                //   GENERATE BUCKET ID MANUALLY
                bucketInstance.setId(generateBucketInstanceId());
                bucketInstance.setUpdatedAt(LocalDateTime.now());

                setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket);

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
                    "Error during prorated quota provision: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private List<BucketInstance> directQuotaProvision(List<PlanToBucket> quotaDetails, ServiceInstance serviceInstance) {
        log.debug("Starting direct quota provision for Service Instance ID: {}, Quota count: {}",
                serviceInstance.getId(), quotaDetails.size());
        List<BucketInstance> bucketInstanceList = new ArrayList<>();
        try {
            for (PlanToBucket planToBucket : quotaDetails) {
                BucketInstance bucketInstance = new BucketInstance();

                //   GENERATE BUCKET ID MANUALLY
                bucketInstance.setId(generateBucketInstanceId());
                bucketInstance.setUpdatedAt(LocalDateTime.now());

                setBucketDetails(planToBucket.getBucketId(), bucketInstance, serviceInstance, planToBucket);
                log.debug("Bucket provisioned - Bucket ID: {}, Initial quota: {}",
                        planToBucket.getBucketId(), planToBucket.getInitialQuota());
                bucketInstanceList.add(bucketInstance);
            }

            log.info("Built {} bucket instances for Service Instance ID: {} (not saved to DB)",
                    bucketInstanceList.size(), serviceInstance.getId());

            return bucketInstanceList;
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error during direct quota provision for Service Instance ID: {}",
                    serviceInstance.getId(), ex);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(readOnly = true)
    public UpdateResponseDTO updateService(String userId, String planId, String requestId, UpdateRequestDTO updateDto) {
        log.info("Updating service for User ID: {}, Plan ID: {}, Request ID: {}", userId, planId, requestId);
        try {
            boolean requestExists = serviceInstanceRepository.existsByRequestId(requestId);
            if (requestExists) {
                log.error("Service update with Request ID already exists: {}", requestId);
                throw new AAAException(
                        LogMessages.DUPLICATE_REQUEST_ID,
                        "Service update with request ID already exists: " + requestId,
                        HttpStatus.CONFLICT
                );
            }

            ServiceInstance serviceInstance = serviceInstanceRepository
                    .findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(userId, planId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            "No active or suspend service found for the given identifiers.",
                            HttpStatus.NOT_FOUND
                    ));

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

            return performServiceUpdate(serviceInstance, updateDto, userId, requestId);

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

        // Store request_id in service instance
        serviceInstance.setRequestId(requestId);  // ADD THIS

        List<BucketInstance> buckets = bucketInstanceRepository.findByServiceId(serviceInstance.getId());

        serviceInstance.setStatus(INACTIVE);
        serviceInstance.setUpdatedAt(LocalDateTime.now());

        publishServiceDeletedEvents(serviceInstance, buckets);
        coAManagementService.sendServiceStatusCoARequest(
                serviceInstance.getUsername(), serviceInstance.getId(), "INACTIVE");

        log.info("Service deletion events published for Service ID: {}", serviceInstance.getId());

        return buildUpdateSuccessResponse(serviceInstance, userId, requestId);  // Pass request_id
    }

    private UpdateResponseDTO performServiceUpdate(ServiceInstance serviceInstance, UpdateRequestDTO updateDto,
                                                   String userId, String requestId) {
        serviceInstance.setRequestId(requestId);

        // ✅ Capture BEFORE anything is overwritten
        String oldStatus = serviceInstance.getStatus();
        log.info("Service status transition check - user='{}', currentStatus='{}'", userId, oldStatus);

        LocalDateTime newStartDate = updateDto.getServiceStartDate() != null ?
                updateDto.getServiceStartDate() : serviceInstance.getServiceStartDate();
        LocalDateTime newEndDate = updateDto.getServiceEndDate() != null ?
                updateDto.getServiceEndDate() : serviceInstance.getExpiryDate();

        if (updateDto.getServiceStartDate() != null || updateDto.getServiceEndDate() != null) {
            validateServiceDates(newStartDate, newEndDate, "Service Update");
        }

        if (updateDto.getServiceStartDate() != null) serviceInstance.setServiceStartDate(updateDto.getServiceStartDate());
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

        //  CoA — now oldStatus is correctly the PRE-update status
        if (updateDto.getStatus() != null) {
            String newStatus = serviceInstance.getStatus(); // already set above, this is the new status
            log.info("Evaluating CoA for status transition: '{}' -> '{}', user='{}', serviceId='{}'",
                    oldStatus, newStatus, userId, serviceInstance.getId());

            boolean shouldSendCoA =
                    (oldStatus.equals("Active") && newStatus.equals("Suspended")) ||
                            (oldStatus.equals("Suspended") && newStatus.equals("Active"));

            if (shouldSendCoA) {
                log.info("CoA required for transition '{}' -> '{}', user='{}', serviceId='{}'",
                        oldStatus, newStatus, userId, serviceInstance.getId());
                coAManagementService.sendServiceStatusCoARequest(
                        serviceInstance.getUsername(),
                        serviceInstance.getId(),
                        newStatus.toUpperCase());
            } else {
                log.info("No CoA required for transition '{}' -> '{}', user='{}'",
                        oldStatus, newStatus, userId);
            }
        } else {
            log.debug("No status change in request for user='{}', skipping CoA", userId);
        }

        log.info("Successfully published service update events for Request ID: {}", requestId);
        return buildUpdateSuccessResponse(serviceInstance, userId, requestId);
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
            Long newInitialBalance = updateDto.getQuota() + bucketInstance.getInitialBalance();
            bucketInstance.setInitialBalance(newInitialBalance);
        } catch (Exception e) {
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                    "Error Occurred During Initial Quota Update", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(readOnly = true) //   READ-ONLY
    public DeleteResponseDTO deleteService(String userId, String planId, String requestId) {
        log.info("Deleting service for User ID: {}, Plan ID: {}, Request ID: {}", userId, planId, requestId);
        try {
            //   READ-ONLY: Fetch service for validation
            ServiceInstance serviceInstance = serviceInstanceRepository
                    .findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(userId, planId)
                    .orElseThrow(() -> new AAAException(
                            LogMessages.SERVICE_OR_USER_UNAVAILABLE,
                            "Active Service or User Unavailable",
                            HttpStatus.NOT_FOUND
                    ));

            List<BucketInstance> buckets = bucketInstanceRepository.findByServiceId(serviceInstance.getId());

            publishServiceDeletedEvents(serviceInstance, buckets);
            coAManagementService.sendServiceDeleteCoARequest(userId, serviceInstance.getId());

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
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent("CREATE", service);

            // Bundle bucket CREATEs as relatedWrites
            List<DBWriteRequestGeneric> bucketWrites = buckets.stream()
                    .map(b -> eventMapper.toBucketDBWriteEvent("CREATE", b, service.getUsername()))
                    .collect(Collectors.toList());
            mainEvent.setRelatedWrites(bucketWrites);

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        result.getDcError(),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            serviceTTLManager.publishServiceTTL(
                    service.getId(), service.getUsername(), service.getPlanId(), service.getExpiryDate()
            );

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

    private void publishServiceUpdatedEvents(ServiceInstance service, List<BucketInstance> updatedBuckets) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent("UPDATE", service);

            if (!updatedBuckets.isEmpty()) {
                List<DBWriteRequestGeneric> bucketWrites = updatedBuckets.stream()
                        .map(b -> eventMapper.toBucketDBWriteEvent("UPDATE", b, service.getUsername()))
                        .collect(Collectors.toList());
                mainEvent.setRelatedWrites(bucketWrites);
            }

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        result.getDcError(),
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }

            serviceTTLManager.publishServiceTTL(
                    service.getId(), service.getUsername(), service.getPlanId(), service.getExpiryDate()
            );

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

    private void publishServiceDeletedEvents(ServiceInstance service, List<BucketInstance> buckets) {
        try {
            DBWriteRequestGeneric mainEvent = eventMapper.toServiceDBWriteEvent("DELETE", service);

            if (!buckets.isEmpty()) {
                List<DBWriteRequestGeneric> bucketWrites = buckets.stream()
                        .map(b -> eventMapper.toBucketDBWriteEvent("DELETE", b, service.getUsername()))
                        .collect(Collectors.toList());
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

    private void publishBucketCreatedEvents(List<BucketInstance> buckets, String username) {
        try {
            for (BucketInstance bucket : buckets) {
//
                DBWriteRequestGeneric dbEvent = eventMapper.toBucketDBWriteEvent("CREATE", bucket, username);
                PublishResult dbResult = kafkaEventPublisher.publishDBWriteEvent(dbEvent);

                // FAIL IMMEDIATELY on first failure
                if ( dbResult.isCompleteFailure()) {
                    log.error("Failed to publish bucket created events for bucket ID '{}'", bucket.getId());
                    throw new AAAException(
                            LogMessages.ERROR_INTERNAL_ERROR,
                            String.format("Failed to publish bucket creation event for bucket ID %d", bucket.getId()),
                            HttpStatus.INTERNAL_SERVER_ERROR
                    );
                }


                if (!dbResult.isBothSuccess()) {
                    log.warn("Partial failure publishing DB event for bucket ID '{}'. DC: {}, DR: {}",
                            bucket.getId(), dbResult.isDcSuccess(), dbResult.isDrSuccess());
                }
            }

        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish bucket created events for username '{}'", username, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish bucket created events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void publishBucketUpdatedEvents(List<BucketInstance> buckets, String username) {
        try {
            for (BucketInstance bucket : buckets) {

                DBWriteRequestGeneric dbEvent = eventMapper.toBucketDBWriteEvent("UPDATE", bucket, username);
                PublishResult dbResult = kafkaEventPublisher.publishDBWriteEvent(dbEvent);

                if (dbResult.isCompleteFailure() || dbResult.isCompleteFailure()) {
                    log.error("Failed to publish bucket update events for bucket ID '{}'", bucket.getId());
                    throw new AAAException(
                            LogMessages.ERROR_INTERNAL_ERROR,
                            "Failed to publish bucket update events",
                            HttpStatus.INTERNAL_SERVER_ERROR
                    );
                }

                if (!dbResult.isBothSuccess()) {
                    log.warn("Partial failure publishing bucket update event. DC: {}, DR: {}",
                            dbResult.isDcSuccess(), dbResult.isDrSuccess());
                }
                if (!dbResult.isBothSuccess()) {
                    log.warn("Partial failure publishing DB update event. DC: {}, DR: {}",
                            dbResult.isDcSuccess(), dbResult.isDrSuccess());
                }
            }
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Failed to publish bucket updated events for username '{}'", username, e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Failed to publish bucket updated events",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private void publishBucketDeletedEvents(List<BucketInstance> buckets, String username) {
        try {
            for (BucketInstance bucket : buckets) {
                DBWriteRequestGeneric dbEvent = eventMapper.toBucketDBWriteEvent("DELETE", bucket, username);
                kafkaEventPublisher.publishDBWriteEvent(dbEvent);
            }
        } catch (Exception e) {
            log.error("Failed to publish bucket deleted events for username '{}'", username, e);
            // Don't throw for deletes
        }
    }

    // ========== HELPER METHODS (Keep all existing validation logic) ==========

    private void setCycleManagementProperties(ServiceInstance serviceInstance, Plan plan, UserEntity user) {
        log.debug("Setting cycle management properties for User: {}, Billing: {}", user.getUserName(), user.getBilling());
        try {
            LocalDateTime serviceStartDate = serviceInstance.getServiceStartDate();
            serviceInstance.setServiceStartDate(serviceStartDate);

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

        if (startDate.toLocalDate().isBefore(now.toLocalDate())) {
            log.error("{} - Service start date {} is in the past", context, startDate);
            throw new AAAException(
                    LogMessages.ERROR_BAD_REQUEST,
                    "Service start date cannot be in the past. Must be today or a future date.",
                    HttpStatus.BAD_REQUEST
            );
        }

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
                                  PlanToBucket planToBucket) {
        log.debug("Setting bucket details for Bucket ID: {}", bucketId);
        try {
            //  READ-ONLY: Fetch bucket details
            Bucket bucket = bucketRepository.findByBucketId(bucketId)
                    .orElseThrow(() -> {
                        log.error("Bucket not found: {}", bucketId);
                        return new AAAException(LogMessages.ERROR_POLICY_CONFLICT,
                                "BUCKET_NOT_FOUND " + bucketId, HttpStatus.NOT_FOUND);
                    });

            log.debug("Bucket found - Type: {}, Priority: {}", bucket.getBucketType(), bucket.getPriority());

            bucketInstance.setBucketId(bucket.getBucketId());
            bucketInstance.setBucketType(bucket.getBucketType());
            bucketInstance.setPriority(bucket.getPriority());
            bucketInstance.setTimeWindow(bucket.getTimeWindow());
            bucketInstance.setRule(getBNGCodeByRuleId(bucket.getQosId()));
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

    private String getBNGCodeByRuleId(Long qosId) {
        log.debug("Fetching BNG code for QoS ID: {}", qosId);
        try {
            QOSProfile qosProfile = qosProfileRepository.findById(qosId)
                    .orElseThrow(() -> {
                        log.error("QoS profile not found: {}", qosId);
                        return new AAAException(
                                LogMessages.ERROR_POLICY_CONFLICT,
                                "QOS_PROFILE_NOT_FOUND " + qosId,
                                HttpStatus.NOT_FOUND
                        );
                    });
            log.debug("BNG code retrieved: {}", qosProfile.getBngCode());
            return qosProfile.getBngCode();
        } catch (AAAException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error fetching BNG code for QoS ID: {}", qosId, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private static void setBasicServiceInstanceData(ServiceInstance serviceInstance, Plan plan, UserEntity user,
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
        serviceInstance.setExpiryDate(request.getServiceEndDate() != null ?
                request.getServiceEndDate() : setDefaultExpiry(request.getServiceStartDate()));
        serviceInstance.setStatus(mapStatus(Integer.valueOf(request.getStatus())));
        serviceInstance.setIsGroup(isGroup);
        serviceInstance.setRequestId(request.getRequestId());

        log.debug("Basic service instance data set");
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
            case 2 -> "Suspended";
            case 3 -> INACTIVE;
            default -> throw new AAAException(LogMessages.ERROR_BAD_REQUEST,
                    "Invalid status: " + status, HttpStatus.BAD_REQUEST);
        };
    }

    private UpdateResponseDTO buildUpdateSuccessResponse(ServiceInstance entity, String userId, String requestId) {  // ADD requestId parameter
        BucketInstance priorityBucket = bucketInstanceRepository
                .findFirstByServiceIdOrderByPriorityAsc(entity.getId())
                .orElse(null);

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
            case "Active" -> 1;
            case "Suspended" -> 2;
            case "Inactive" -> 3;
            default -> throw new AAAException(LogMessages.ERROR_BAD_REQUEST,
                    "Invalid status: " + status, HttpStatus.BAD_REQUEST);
        };
    }
}
