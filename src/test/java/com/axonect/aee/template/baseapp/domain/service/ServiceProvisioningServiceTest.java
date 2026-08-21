/*
package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.ActiveServiceRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ActiveServiceResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.DeleteResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.UpdateResponseDTO;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceProvisioningServiceTest {

    // ── Repositories ──────────────────────────────────────────────────────────
    @Mock private UserRepository            userRepository;
    @Mock private PlanRepository            planRepository;
    @Mock private PlanToBucketRepository    planToBucketRepository;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private BucketRepository          bucketRepository;
    @Mock private QOSProfileRepository      qosProfileRepository;
    @Mock private BucketInstanceRepository  bucketInstanceRepository;

    // ── Infrastructure ────────────────────────────────────────────────────────
    // ServiceTTLManager and CoAManagementService are constructor-injected via
    // @RequiredArgsConstructor.  Without mocks, @InjectMocks leaves them null
    // and every successful code path throws NullPointerException.
    @Mock private KafkaEventPublisher  kafkaEventPublisher;
    @Mock private EventMapper          eventMapper;
    @Mock private ServiceTTLManager    serviceTTLManager;
    @Mock private CoAManagementService coAManagementService;

    @InjectMocks
    private ServiceProvisioningService service;

    // ── Shared fixtures ───────────────────────────────────────────────────────
    private UserEntity      user;
    private Plan            plan;
    private PlanToBucket    ptb;
    private Bucket          bucket;
    private QOSProfile      qos;
    private BucketInstance  bucketInstance;
    private ServiceInstance savedServiceInstance;
    private ActiveServiceRequestDTO req;

    @BeforeEach
    void setUp() {
        // User  (billing "3" = cycle-based, cycleDate 1 = 1st of month)
        user = new UserEntity();
        user.setUserName("user123");
        user.setGroupId("group123");
        user.setBilling("3");
        user.setCycleDate(1);
        user.setStatus(UserStatus.ACTIVE);

        // Plan  (recurring, proration OFF → directQuotaProvision, simplest path)
        plan = new Plan();
        plan.setPlanId("plan123");
        plan.setPlanName("Basic Plan");
        plan.setPlanType("TypeA");
        plan.setRecurringFlag(true);
        plan.setQuotaProrationFlag(false);
        plan.setRecurringPeriod("MONTHLY");
        plan.setStatus("Active");

        // PlanToBucket
        ptb = new PlanToBucket();
        ptb.setBucketId("bucket123");
        ptb.setInitialQuota(1000L);
        ptb.setIsUnlimited(false);
        ptb.setCarryForward(false);
        ptb.setMaxCarryForward(0L);
        ptb.setTotalCarryForward(0L);

        // Bucket – priority must be non-null: stream .min() comparator calls Long.compare
        bucket = new Bucket();
        bucket.setBucketId("bucket123");
        bucket.setBucketType("DATA");
        bucket.setPriority(1L);
        bucket.setTimeWindow("ANY");
        bucket.setQosId(10L);

        // QoS
        qos = new QOSProfile();
        qos.setId(10L);
        qos.setBngCode("BNG-1");

        // BucketInstance – priority must be non-null for the same reason
        bucketInstance = new BucketInstance();
        bucketInstance.setId(1L);
        bucketInstance.setBucketId("bucket123");
        bucketInstance.setInitialBalance(1000L);
        bucketInstance.setCurrentBalance(500L);
        bucketInstance.setPriority(1L);
        bucketInstance.setServiceId(99L);

        // ServiceInstance – represents an already-persisted record
        savedServiceInstance = new ServiceInstance();
        savedServiceInstance.setId(99L);
        savedServiceInstance.setUsername("user123");
        savedServiceInstance.setPlanId("plan123");
        savedServiceInstance.setPlanName("Basic Plan");
        savedServiceInstance.setPlanType("TypeA");
        savedServiceInstance.setStatus("Active");
        savedServiceInstance.setRecurringFlag(true);
        savedServiceInstance.setServiceStartDate(LocalDateTime.now());
        savedServiceInstance.setExpiryDate(LocalDateTime.now().plusDays(30));
        savedServiceInstance.setServiceCycleStartDate(LocalDateTime.now());
        savedServiceInstance.setServiceCycleEndDate(LocalDateTime.now().plusDays(29));

        // Activation request – requestId must be set; anyString() does NOT match null
        req = new ActiveServiceRequestDTO();
        req.setRequestId("req-1");
        req.setUserId("user123");
        req.setPlanId("plan123");
        req.setServiceStartDate(LocalDateTime.now());
        req.setStatus("1");
        req.setIsGroup(false);
    }

    // =========================================================================
    // ACTIVATE SERVICE – error / validation paths  (tests 1–15)
    // =========================================================================

    */
/** Status "3" is rejected before any repository call. *//*

    @Test
    void testActivateService_InactiveStatus_ShouldThrowException() {
        req.setStatus("3");

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Inactive status"));
    }

    */
/** Null start-date is caught by validateServiceDates. *//*

    @Test
    void testActivateService_NullStartDate_ShouldThrowException() {
        req.setServiceStartDate(null);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** Past start-date is rejected by validateServiceDates. *//*

    @Test
    void testActivateService_StartDateInPast_ShouldThrowException() {
        req.setServiceStartDate(LocalDateTime.now().minusDays(5));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** End-date before start-date is rejected. *//*

    @Test
    void testActivateService_EndDateBeforeStartDate_ShouldThrowException() {
        req.setServiceStartDate(LocalDateTime.now().plusDays(5));
        req.setServiceEndDate(LocalDateTime.now().plusDays(2));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** End-date equal to start-date is rejected (not strictly after). *//*

    @Test
    void testActivateService_EndDateEqualsStartDate_ShouldThrowException() {
        LocalDateTime date = LocalDateTime.now().plusDays(1);
        req.setServiceStartDate(date);
        req.setServiceEndDate(date);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** A request whose ID already exists yields CONFLICT. *//*

    @Test
    void testActivateService_DuplicateRequestId() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(true);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(serviceInstanceRepository).existsByRequestId("req-1");
    }


    */
/** User with null status yields BAD_REQUEST. *//*

    @Test
    void testActivateService_UserStatusNull_ShouldThrowException() {
        user.setStatus(null);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** Inactive user yields UNPROCESSABLE_ENTITY. *//*

    @Test
    void testActivateService_UserInactive_ShouldThrowException() {
        user.setStatus(UserStatus.INACTIVE);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }



    */
/** Inactive plan yields UNPROCESSABLE_ENTITY. *//*

    @Test
    void testActivateService_PlanNotActive() {
        plan.setStatus("Inactive");
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    */
/** Duplicate service (same user + plan) yields CONFLICT. *//*

    @Test
    void testActivateService_ServiceAlreadyExists() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString()))
                .thenReturn(true);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    */
/** Plan with no bucket mappings yields NOT_FOUND. *//*

    @Test
    void testActivateService_NoQuotaDetails() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString()))
                .thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(new ArrayList<>());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    */
/** One-time pack without an end-date yields BAD_REQUEST. *//*

    @Test
    void testActivateService_OneTimePack_NoEndDate() {
        plan.setRecurringFlag(false);
        req.setServiceEndDate(null);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("mandatory for One-Time Packs"));
    }

    // =========================================================================
    // ACTIVATE SERVICE – success paths  (tests 16–23)
    // =========================================================================

    */
/**
     * Individual + recurring + proration OFF → directQuotaProvision path.
     * Covers: activateService, activateIndividualService, subscribeResources,
     * provisionRecurringPack, setCycleManagementProperties (billing "3"),
     * provisionQuota, directQuotaProvision, setBucketDetails (recurring branch),
     * getBNGCodeByRuleId, publishServiceCreatedEvents (success), serviceTTLManager.
     *//*

    @Test
    void testActivateService_IndividualRecurring_NoProration_Success() {
        plan.setQuotaProrationFlag(false);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        assertEquals("plan123", response.getPlanId());
        verify(kafkaEventPublisher, atLeastOnce()).publishDBWriteEvent(any());
        verify(serviceTTLManager, atLeastOnce())
                .publishServiceTTL(anyLong(), anyString(), anyString(), any());
    }

    */
/**
     * Individual + recurring + proration ON → proratedQuotaProvision path.
     * Covers: getProrationFactor (main calculation body), proratedQuotaProvision
     * (isUnlimited=false → originalQuota × factor → Math.round).
     *//*

    @Test
    void testActivateService_IndividualRecurring_WithProration_Success() {
        plan.setQuotaProrationFlag(true);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        verify(kafkaEventPublisher, atLeastOnce()).publishDBWriteEvent(any());
    }

    */
/**
     * Group activation – response userId must equal the group-id.
     * Covers: activateGroupService, findFirstByGroupId, setBasicServiceInstanceData
     * (isGroup=true → setUsername(groupId) branch).
     *//*

    @Test
    void testActivateService_GroupService_Success() {
        req.setIsGroup(true);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findFirstByGroupId(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString()))
                .thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals(user.getGroupId(), response.getUserId());
    }

    */
/**
     * NEW – Kafka complete failure in publishServiceCreatedEvents.
     * Covers: publishServiceCreatedEvents → result.isCompleteFailure() == true
     * → throw AAAException(INTERNAL_SERVER_ERROR) branch.
     *//*

    @Test
    void testActivateService_KafkaCompleteFailure_ShouldThrowInternalError() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString()))
                .thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
        // Both DC and DR fail → isCompleteFailure() = true
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(false).build());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    */
/**
     * NEW – req.quota > 0 exercises applyQuotaToPriorityBucket body.
     * Covers: applyQuotaToPriorityBucket → stream .min() select, newInitialBalance =
     * initialBalance + quota, setInitialBalance, setCurrentBalance lines.
     * finalQuota in the response reflects the top-up (1000 + 500 = 1500).
     *//*

    @Test
    void testActivateService_WithQuota_AppliedToPriorityBucket() {
        req.setQuota(500L);
        plan.setQuotaProrationFlag(false);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals(1500L, response.getFinalQuota());
    }

    */
/**
     * NEW – One-time pack with a valid end-date, full success path.
     * Covers: provisionOneTimePack (all lines after the null-endDate guard),
     * setBasicServiceInstanceData (expiryDate from request),
     * setBucketDetails → recurringFlag=false → expiration = expiryDate branch.
     * provisionOneTimePack does NOT call existsByUsernameAndPlanId.
     *//*

    @Test
    void testActivateService_OneTimePack_ValidEndDate_Success() {
        plan.setRecurringFlag(false);
        plan.setQuotaProrationFlag(false);
        req.setServiceEndDate(LocalDateTime.now().plusDays(30));

        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        // No existsByUsernameAndPlanId stub needed for one-time packs
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        assertEquals(1000L, response.getFinalQuota());
    }

    */
/**
     * NEW – billing "1" takes the else-branch in setCycleManagementProperties.
     * Covers: else { cycleStartDate = serviceStartDate.toLocalDate().atStartOfDay(); ... }
     * and the "1".equals(billing) path in getNumberOfValidityDays.
     *//*

    @Test
    void testActivateService_BillingType1_CoversCycleElseBranch() {
        user.setBilling("1");
        plan.setQuotaProrationFlag(false);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
    }

    */
/**
     * NEW – billing "2" takes the else-if branch in setCycleManagementProperties.
     * Covers: } else if ("2".equals(billing)) { cycleStartDate = withDayOfMonth(1); ... }
     * and the "2".equals(billing) path in getNumberOfValidityDays.
     *//*

    @Test
    void testActivateService_BillingType2_CoversCycleElseIfBranch() {
        user.setBilling("2");
        plan.setQuotaProrationFlag(false);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
    }

    // =========================================================================
    // UPDATE SERVICE – error paths  (tests 24–30)
    // =========================================================================

    */
/** Duplicate requestId yields CONFLICT. *//*

    @Test
    void testUpdateService_DuplicateRequestId() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(true);

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId",
                        new UpdateRequestDTO()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    */
/** No service found yields NOT_FOUND. *//*

    @Test
    void testUpdateService_ServiceNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId",
                        new UpdateRequestDTO()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    */
/** Updating an Inactive service yields CONFLICT. *//*

    @Test
    void testUpdateService_InactiveService() {
        savedServiceInstance.setStatus("Inactive");
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId",
                        new UpdateRequestDTO()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    */
/** Status → Inactive routes to handleServiceInactivation; CoA is fired. *//*

    @Test
    void testUpdateService_StatusToInactive_TriggersDelete() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong()))
                .thenReturn(List.of(bucketInstance));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setStatus(3); // Inactive

        UpdateResponseDTO response = service.updateService("user123", "plan123", "requestId", dto);

        assertNotNull(response);
        assertEquals("Inactive", savedServiceInstance.getStatus());
        verify(coAManagementService)
                .sendServiceStatusCoARequest(anyString(), anyLong(), argThat(s -> s.equalsIgnoreCase("INACTIVE")));
    }

    */
/** Missing bucket yields NOT_FOUND. *//*

    @Test
    void testUpdateService_BucketNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.empty());

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setQuota(100L); // forces bucket lookup

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId", dto));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    */
/** currentBalance + balanceQuota > initialBalance yields BAD_REQUEST. *//*

    @Test
    void testUpdateService_BalanceQuotaExceedsLimit() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.of(bucketInstance));

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setBalanceQuota(600L); // 500 + 600 = 1100 > 1000 → rejected

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId", dto));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    */
/** End-date before start-date in update yields BAD_REQUEST. *//*

    @Test
    void testUpdateService_InvalidDates_EndBeforeStart() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setServiceStartDate(LocalDateTime.now().plusDays(10));
        dto.setServiceEndDate(LocalDateTime.now().plusDays(5));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId", dto));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // =========================================================================
    // UPDATE SERVICE – success paths  (tests 31–35)
    // =========================================================================

    */
/** Quota top-up increases initialBalance; event is published. *//*

    @Test
    void testUpdateService_QuotaTopUp_Success() {
        setupSuccessfulUpdate();

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setQuota(500L);

        UpdateResponseDTO response = service.updateService("user123", "plan123", "requestId", dto);

        assertNotNull(response);
        assertEquals(1500L, bucketInstance.getInitialBalance()); // 1000 + 500
        verify(kafkaEventPublisher, atLeastOnce()).publishDBWriteEvent(any());
    }

    */
/**
     * NEW – balance top-up within the limit succeeds.
     * Covers: updateBalanceQuotaOfMainBucket → updatedRemainingQuota ≤ initialBalance
     * → setCurrentBalance(updatedRemainingQuota) success branch.
     *//*

    @Test
    void testUpdateService_BalanceTopUp_WithinLimit_Success() {
        setupSuccessfulUpdate();

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setBalanceQuota(400L); // 500 + 400 = 900 ≤ 1000 → OK

        UpdateResponseDTO response = service.updateService("user123", "plan123", "requestId", dto);

        assertNotNull(response);
        assertEquals(900L, bucketInstance.getCurrentBalance());
    }

    */
/**
     * NEW – Active → Suspended status change fires a CoA request.
     * Covers: performServiceUpdate → shouldSendCoA=true (first condition:
     * oldStatus="Active" && newStatus="Suspended"), sendServiceStatusCoARequest("SUSPENDED").
     *//*

    @Test
    void testUpdateService_ActiveToSuspended_TriggersCoA() {
        // savedServiceInstance.status is "Active" from setUp
        setupSuccessfulUpdate();

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setStatus(2); // Suspended

        UpdateResponseDTO response = service.updateService("user123", "plan123", "requestId", dto);

        assertNotNull(response);
        assertEquals("Suspended", savedServiceInstance.getStatus());
        verify(coAManagementService)
                .sendServiceStatusCoARequest(eq("user123"), eq(99L), eq("SUSPENDED"));
    }

    */
/**
     * NEW – Suspended → Active status change fires a CoA request.
     * Covers: performServiceUpdate → shouldSendCoA=true (second condition:
     * oldStatus="Suspended" && newStatus="Active"), sendServiceStatusCoARequest("ACTIVE").
     *//*

    @Test
    void testUpdateService_SuspendedToActive_TriggersCoA() {
        savedServiceInstance.setStatus("Suspended");
        setupSuccessfulUpdate();

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setStatus(1); // Active

        UpdateResponseDTO response = service.updateService("user123", "plan123", "requestId", dto);

        assertNotNull(response);
        assertEquals("Active", savedServiceInstance.getStatus());
        verify(coAManagementService)
                .sendServiceStatusCoARequest(eq("user123"), eq(99L), eq("ACTIVE"));
    }

    */
/**
     * NEW – Kafka complete failure in publishServiceUpdatedEvents.
     * Covers: publishServiceUpdatedEvents → result.isCompleteFailure() == true
     * → throw AAAException(INTERNAL_SERVER_ERROR) branch.
     * A quota update produces a non-empty updatedBuckets list, triggering
     * the bucket-write bundling path as well.
     *//*

    @Test
    void testUpdateService_KafkaCompleteFailure_ShouldThrowInternalError() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.of(bucketInstance));
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(false).build());

        UpdateRequestDTO dto = new UpdateRequestDTO();
        dto.setQuota(100L); // produces non-empty updatedBuckets

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", "requestId", dto));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    // =========================================================================
    // DELETE SERVICE  (tests 36–39)
    // =========================================================================

    */
/** Happy-path: events published, CoA fired, response populated. *//*

    @Test
    void testDeleteService_Success() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong()))
                .thenReturn(List.of(bucketInstance));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        DeleteResponseDTO response = service.deleteService("user123", "plan123", "req-1");

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        assertEquals("plan123", response.getPlanId());
        verify(coAManagementService).sendServiceDeleteCoARequest(eq("user123"), anyLong());
    }

    */
/** No service found yields NOT_FOUND. *//*

    @Test
    void testDeleteService_ServiceNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class,
                () -> service.deleteService("user123", "plan123", "req-1"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    */
/** Repository exception is wrapped and re-thrown as INTERNAL_SERVER_ERROR. *//*

    @Test
    void testDeleteService_UnexpectedException() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenThrow(new RuntimeException("DB Error"));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.deleteService("user123", "plan123", "req-1"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    */
/** Multiple bucket instances: one toBucketDBWriteEvent call per bucket. *//*

    @Test
    void testDeleteService_WithMultipleBuckets() {
        BucketInstance bucket2 = new BucketInstance();
        bucket2.setId(2L);
        bucket2.setBucketId("bucket456");

        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong()))
                .thenReturn(List.of(bucketInstance, bucket2));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        DeleteResponseDTO response = service.deleteService("user123", "plan123", "req-1");

        assertNotNull(response);
        verify(eventMapper, times(2)).toBucketDBWriteEvent(anyString(), any(), anyString());
    }

    // =========================================================================
    // PRIVATE-METHOD COVERAGE via reflection  (tests 40–42)
    // =========================================================================

    */
/**
     * NEW – getProrationFactor with null service dates.
     * Covers: if (serviceStartDate == null || cycleStartDate == null || cycleEndDate == null)
     * → throw AAAException(BAD_REQUEST) guard branch.
     *//*

    @Test
    void testGetProrationFactor_NullServiceDates_ThrowsValidationError() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(null); // null triggers the guard

        Method method = ServiceProvisioningService.class
                .getDeclaredMethod("getProrationFactor", ServiceInstance.class);
        method.setAccessible(true);

        Exception wrapper = assertThrows(Exception.class, () -> method.invoke(service, si));
        assertInstanceOf(AAAException.class, wrapper.getCause());
        assertEquals(HttpStatus.BAD_REQUEST, ((AAAException) wrapper.getCause()).getStatus());
    }

    */
/**
     * NEW – getProrationFactor when cycleStart == cycleEnd (totalCycleDays == 0).
     * Covers: if (totalCycleDays == 0) → throw AAAException(BAD_REQUEST) branch.
     *//*

    @Test
    void testGetProrationFactor_ZeroCycleDays_ThrowsValidationError() throws Exception {
        LocalDateTime same = LocalDateTime.now();
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(same);
        si.setServiceCycleStartDate(same);
        si.setServiceCycleEndDate(same); // DAYS.between(same, same) = 0

        Method method = ServiceProvisioningService.class
                .getDeclaredMethod("getProrationFactor", ServiceInstance.class);
        method.setAccessible(true);

        Exception wrapper = assertThrows(Exception.class, () -> method.invoke(service, si));
        assertInstanceOf(AAAException.class, wrapper.getCause());
    }

    */
/**
     * NEW – mapStatus default case (integer value outside 1/2/3).
     * Covers: default → throw AAAException(BAD_REQUEST) branch in the switch expression.
     *//*

    @Test
    void testMapStatus_InvalidValue_ThrowsBadRequest() throws Exception {
        Method method = ServiceProvisioningService.class
                .getDeclaredMethod("mapStatus", Integer.class);
        method.setAccessible(true);

        Exception wrapper = assertThrows(Exception.class, () -> method.invoke(null, 99));
        assertInstanceOf(AAAException.class, wrapper.getCause());
        assertEquals(HttpStatus.BAD_REQUEST, ((AAAException) wrapper.getCause()).getStatus());
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    */
/**
     * Full happy-path stubs for individual recurring service activation.
     *
     * <p><b>Key:</b> {@code publishServiceCreatedEvents} makes exactly ONE
     * {@code publishDBWriteEvent} call – bucket events travel via
     * {@code relatedWrites}, not as separate calls.
     *//*

    private void setupSuccessfulActivation() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString()))
                .thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
        // serviceTTLManager.publishServiceTTL is void – Mockito stubs nothing by default
    }

    */
/**
     * Minimal happy-path stubs for service update.
     *
     * <p>lenient() on Kafka/mapper stubs prevents "unnecessary stubbing" failures
     * in tests that exit before reaching the publish step (e.g. date-validation tests).
     *//*

    private void setupSuccessfulUpdate() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(
                anyString(), anyString())).thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.of(bucketInstance));
        lenient().when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().Success(true).build());
        lenient().when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        lenient().when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
    }
}*/
