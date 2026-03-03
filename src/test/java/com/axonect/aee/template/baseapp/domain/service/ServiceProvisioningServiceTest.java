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
import com.axonect.aee.template.baseapp.domain.events.ServiceEvent;
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

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PlanToBucketRepository planToBucketRepository;

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;

    @Mock
    private BucketRepository bucketRepository;

    @Mock
    private QOSProfileRepository qosProfileRepository;

    @Mock
    private BucketInstanceRepository bucketInstanceRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private ServiceProvisioningService service;

    private UserEntity user;
    private Plan plan;
    private PlanToBucket ptb;
    private Bucket bucket;
    private QOSProfile qos;
    private BucketInstance bucketInstance;
    private ServiceInstance savedServiceInstance;
    private ActiveServiceRequestDTO req;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setUserName("user123");
        user.setGroupId("group123");
        user.setBilling("3");
        user.setCycleDate(1);
        user.setStatus(UserStatus.ACTIVE);

        plan = new Plan();
        plan.setPlanId("plan123");
        plan.setPlanName("Basic Plan");
        plan.setPlanType("TypeA");
        plan.setRecurringFlag(true);
        plan.setQuotaProrationFlag(true);
        plan.setRecurringPeriod("MONTHLY");
        plan.setStatus("Active");

        ptb = new PlanToBucket();
        ptb.setBucketId("bucket123");
        ptb.setInitialQuota(1000L);
        ptb.setIsUnlimited(false);
        ptb.setCarryForward(false);
        ptb.setMaxCarryForward(0L);
        ptb.setTotalCarryForward(0L);
        ptb.setConsumptionLimit(null);
        ptb.setConsumptionLimitWindow(null);

        bucket = new Bucket();
        bucket.setBucketId("bucket123");
        bucket.setBucketType("DATA");
        bucket.setPriority(1L);
        bucket.setTimeWindow("ANY");
        bucket.setQosId(10L);

        qos = new QOSProfile();
        qos.setId(10L);
        qos.setBngCode("BNG-1");

        bucketInstance = new BucketInstance();
        bucketInstance.setId(1L);
        bucketInstance.setBucketId("bucket123");
        bucketInstance.setInitialBalance(1000L);
        bucketInstance.setCurrentBalance(500L);

        savedServiceInstance = new ServiceInstance();
        savedServiceInstance.setId(99L);
        savedServiceInstance.setUsername("user123");
        savedServiceInstance.setPlanId("plan123");
        savedServiceInstance.setPlanName("Basic Plan");
        savedServiceInstance.setPlanType("TypeA");
        savedServiceInstance.setStatus("Active");
        savedServiceInstance.setServiceStartDate(LocalDateTime.now());
        savedServiceInstance.setExpiryDate(LocalDateTime.now().plusDays(30));
        savedServiceInstance.setServiceCycleStartDate(LocalDateTime.now());
        savedServiceInstance.setServiceCycleEndDate(LocalDateTime.now().plusDays(29));
        savedServiceInstance.setRecurringFlag(true);

        req = new ActiveServiceRequestDTO();
        req.setUserId("user123");
        req.setPlanId("plan123");
        req.setRequestId("req-1");
        req.setServiceStartDate(LocalDateTime.now());
        req.setStatus("1");
        req.setIsGroup(false);
    }

    // ========== ACTIVATE SERVICE TESTS ==========

    @Test
    void testActivateService_DuplicateRequestId() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(true);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(serviceInstanceRepository).existsByRequestId("req-1");
    }

    @Test
    void testActivateService_InactiveStatus_ShouldThrowException() {
        req.setStatus("3");

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Inactive status"));
    }

    @Test
    void testActivateService_UserNotFound() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void testActivateService_UserStatusNull_ShouldThrowException() {
        user.setStatus(null);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testActivateService_UserInactive_ShouldThrowException() {
        user.setStatus(UserStatus.INACTIVE);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void testActivateService_PlanNotFound() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void testActivateService_PlanNotActive() {
        plan.setStatus("Inactive");
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void testActivateService_ServiceAlreadyExists() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString())).thenReturn(true);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void testActivateService_NoQuotaDetails() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString())).thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(new ArrayList<>());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void testActivateService_GroupNotFound() {
        req.setIsGroup(true);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findFirstByGroupId(anyString())).thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

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
    @Test
    void testActivateService_UnlimitedBucket() {
        ptb.setIsUnlimited(true);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeastOnce()).publishDBWriteEvent(any());
    }


    @Test
    void testActivateService_UnexpectedException() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenThrow(new RuntimeException("DB Error"));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    // ========== UPDATE SERVICE TESTS ==========

    @Test
    void testUpdateService_ServiceNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   new UpdateRequestDTO()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void testUpdateService_InactiveService() {
        savedServiceInstance.setStatus("Inactive");
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   new UpdateRequestDTO()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void testUpdateService_StatusChangeToInactive_TriggersDelete() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong())).thenReturn(List.of(bucketInstance));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());

        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setStatus(3);

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        assertEquals("Inactive", savedServiceInstance.getStatus());
    }

    @Test
    void testUpdateService_BucketNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.empty());

        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setQuota(100L);

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   updateDto));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }


    @Test
    void testUpdateService_InvalidDates_EndBeforeStart() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));

        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setServiceStartDate(LocalDateTime.now().plusDays(10));
        updateDto.setServiceEndDate(LocalDateTime.now().plusDays(5));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   updateDto));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }


    @Test
    void testUpdateService_UpdateQuota_Success() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setQuota(500L);

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    void testUpdateService_UpdateBalanceQuota_Success() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setBalanceQuota(200L);

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    void testUpdateService_BalanceQuotaExceedsLimit() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.of(bucketInstance));

        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setBalanceQuota(600L);

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   updateDto));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }


    @Test
    void testUpdateService_UnexpectedException() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenThrow(new RuntimeException("DB Error"));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123",   new UpdateRequestDTO()));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    // ========== DELETE SERVICE TESTS ==========

    @Test
    void testDeleteService_Success() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong())).thenReturn(List.of(bucketInstance));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());

        DeleteResponseDTO response = service.deleteService("user123", "plan123", "req-1");

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        assertEquals("plan123", response.getPlanId());
    }

    @Test
    void testDeleteService_ServiceNotFound() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.empty());

        AAAException ex = assertThrows(AAAException.class,
                () -> service.deleteService("user123", "plan123", "req-1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void testDeleteService_KafkaPublishFailure() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong())).thenReturn(List.of(bucketInstance));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.deleteService("user123", "plan123", "req-1"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    @Test
    void testDeleteService_UnexpectedException() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenThrow(new RuntimeException("DB Error"));

        AAAException ex = assertThrows(AAAException.class,
                () -> service.deleteService("user123", "plan123", "req-1"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    // ========== PRIVATE METHOD TESTS (via reflection) ==========

    @Test
    void testSetDefaultExpiry() throws Exception {
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 0, 0);
        Method method = ServiceProvisioningService.class.getDeclaredMethod("setDefaultExpiry", LocalDateTime.class);
        method.setAccessible(true);
        LocalDateTime result = (LocalDateTime) method.invoke(null, start);
        assertEquals(start.plusYears(100), result);
    }

    @Test
    void testMapStatus_AllValues() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("mapStatus", Integer.class);
        method.setAccessible(true);

        assertEquals("Active", method.invoke(null, 1));
        assertEquals("Suspended", method.invoke(null, 2));
        assertEquals("Inactive", method.invoke(null, 3));
    }

    @Test
    void testMapStatus_InvalidValue() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("mapStatus", Integer.class);
        method.setAccessible(true);

        try {
            method.invoke(null, 99);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    @Test
    void testGetNumberOfValidityDays_Daily() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        Integer days = (Integer) method.invoke(service, "DAILY", "3", LocalDateTime.now());
        assertEquals(1, days);
    }

    @Test
    void testGetNumberOfValidityDays_Weekly() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        Integer days = (Integer) method.invoke(service, "WEEKLY", "3", LocalDateTime.now());
        assertEquals(7, days);
    }

    @Test
    void testGetNumberOfValidityDays_Monthly_BillingType1() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        LocalDateTime date = LocalDateTime.of(2024, 1, 15, 0, 0);
        Integer days = (Integer) method.invoke(service, "MONTHLY", "1", date);
        assertEquals(31, days);
    }

    @Test
    void testGetNumberOfValidityDays_Monthly_BillingType2() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        LocalDateTime date = LocalDateTime.of(2024, 4, 1, 0, 0);
        Integer days = (Integer) method.invoke(service, "MONTHLY", "2", date);
        assertEquals(30, days);
    }

    @Test
    void testGetNumberOfValidityDays_Monthly_BillingType3() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        LocalDateTime date = LocalDateTime.of(2024, 3, 15, 0, 0);
        Integer days = (Integer) method.invoke(service, "MONTHLY", "3", date);
        assertNotNull(days);
        assertTrue(days > 0);
    }

    @Test
    void testGetProrationFactor_Success() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.now().minusDays(5));
        si.setServiceCycleStartDate(LocalDateTime.now().minusDays(10));
        si.setServiceCycleEndDate(LocalDateTime.now().plusDays(10));

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getProrationFactor", ServiceInstance.class);
        method.setAccessible(true);

        Double factor = (Double) method.invoke(service, si);
        assertNotNull(factor);
        assertTrue(factor > 0 && factor <= 1);
    }

    @Test
    void testGetProrationFactor_NullDates() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(null);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getProrationFactor", ServiceInstance.class);
        method.setAccessible(true);

        try {
            method.invoke(service, si);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    @Test
    void testGetProrationFactor_InvalidCycleDates() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.now());
        si.setServiceCycleStartDate(LocalDateTime.now());
        si.setServiceCycleEndDate(LocalDateTime.now());

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getProrationFactor", ServiceInstance.class);
        method.setAccessible(true);

        try {
            method.invoke(service, si);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    @Test
    void testSetCycleManagementProperties_BillingType1() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.now());
        user.setBilling("1");

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setCycleManagementProperties",
                ServiceInstance.class, Plan.class, UserEntity.class);
        method.setAccessible(true);

        method.invoke(service, si, plan, user);

        assertNotNull(si.getServiceCycleStartDate());
        assertNotNull(si.getServiceCycleEndDate());
    }

    @Test
    void testSetCycleManagementProperties_BillingType2() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.of(2024, 3, 15, 10, 0));
        user.setBilling("2");

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setCycleManagementProperties",
                ServiceInstance.class, Plan.class, UserEntity.class);
        method.setAccessible(true);

        method.invoke(service, si, plan, user);

        assertEquals(1, si.getServiceCycleStartDate().getDayOfMonth());
    }

    @Test
    void testSetCycleManagementProperties_BillingType3_AfterCycleDate() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.of(2024, 3, 20, 10, 0));
        user.setBilling("3");
        user.setCycleDate(15);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setCycleManagementProperties",
                ServiceInstance.class, Plan.class, UserEntity.class);
        method.setAccessible(true);

        method.invoke(service, si, plan, user);

        assertEquals(15, si.getServiceCycleStartDate().getDayOfMonth());
    }

    @Test
    void testSetCycleManagementProperties_BillingType3_BeforeCycleDate() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.of(2024, 3, 10, 10, 0));
        user.setBilling("3");
        user.setCycleDate(15);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setCycleManagementProperties",
                ServiceInstance.class, Plan.class, UserEntity.class);
        method.setAccessible(true);

        method.invoke(service, si, plan, user);

        assertEquals(2, si.getServiceCycleStartDate().getMonthValue());
        assertEquals(15, si.getServiceCycleStartDate().getDayOfMonth());
    }

    @Test
    void testSetCycleManagementProperties_NonRecurring_NoNextCycle() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setServiceStartDate(LocalDateTime.now());
        si.setExpiryDate(LocalDateTime.now().plusDays(30));
        plan.setRecurringFlag(false);
        user.setBilling("1");

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setCycleManagementProperties",
                ServiceInstance.class, Plan.class, UserEntity.class);
        method.setAccessible(true);

        method.invoke(service, si, plan, user);

        assertNull(si.getNextCycleStartDate());
    }

    @Test
    void testAdjustCycleStartForServiceStart_Weekly() throws Exception {
        LocalDateTime initialCycleStart = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime serviceStartDate = LocalDateTime.of(2024, 1, 10, 0, 0);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("adjustCycleStartForServiceStart",
                LocalDateTime.class, LocalDateTime.class, long.class, String.class);
        method.setAccessible(true);

        LocalDateTime adjusted = (LocalDateTime) method.invoke(service, initialCycleStart, serviceStartDate, 7L, "WEEKLY");

        assertNotNull(adjusted);
        assertTrue(!adjusted.toLocalDate().isAfter(serviceStartDate.toLocalDate()));
    }

    @Test
    void testAdjustCycleStartForServiceStart_Daily() throws Exception {
        LocalDateTime initialCycleStart = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime serviceStartDate = LocalDateTime.of(2024, 1, 5, 0, 0);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("adjustCycleStartForServiceStart",
                LocalDateTime.class, LocalDateTime.class, long.class, String.class);
        method.setAccessible(true);

        LocalDateTime adjusted = (LocalDateTime) method.invoke(service, initialCycleStart, serviceStartDate, 1L, "DAILY");

        assertEquals(serviceStartDate.toLocalDate(), adjusted.toLocalDate());
    }

    @Test
    void testAdjustCycleStartForServiceStart_Monthly_NoAdjustment() throws Exception {
        LocalDateTime initialCycleStart = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime serviceStartDate = LocalDateTime.of(2024, 1, 10, 0, 0);

        Method method = ServiceProvisioningService.class.getDeclaredMethod("adjustCycleStartForServiceStart",
                LocalDateTime.class, LocalDateTime.class, long.class, String.class);
        method.setAccessible(true);

        LocalDateTime adjusted = (LocalDateTime) method.invoke(service, initialCycleStart, serviceStartDate, 30L, "MONTHLY");

        assertEquals(initialCycleStart, adjusted);
    }

    @Test
    void testGetCycleStartDate_AfterCycleDate() throws Exception {
        LocalDateTime serviceStartDate = LocalDateTime.of(2024, 3, 20, 10, 0);
        Integer cycleDate = 15;

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getCycleStartDate",
                LocalDateTime.class, Integer.class);
        method.setAccessible(true);

        LocalDateTime cycleStart = (LocalDateTime) method.invoke(service, serviceStartDate, cycleDate);

        assertEquals(3, cycleStart.getMonthValue());
        assertEquals(15, cycleStart.getDayOfMonth());
    }

    @Test
    void testGetCycleStartDate_BeforeCycleDate() throws Exception {
        LocalDateTime serviceStartDate = LocalDateTime.of(2024, 3, 10, 10, 0);
        Integer cycleDate = 15;

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getCycleStartDate",
                LocalDateTime.class, Integer.class);
        method.setAccessible(true);

        LocalDateTime cycleStart = (LocalDateTime) method.invoke(service, serviceStartDate, cycleDate);

        assertEquals(2, cycleStart.getMonthValue());
        assertEquals(15, cycleStart.getDayOfMonth());
    }

    @Test
    void testSetBucketDetails_Success() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setId(100L);
        si.setRecurringFlag(true);
        si.setServiceCycleEndDate(LocalDateTime.now().plusDays(30));
        si.setExpiryDate(LocalDateTime.now().plusDays(60));

        BucketInstance bi = new BucketInstance();

        when(bucketRepository.findByBucketId("bucket123")).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(10L)).thenReturn(Optional.of(qos));

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setBucketDetails",
                String.class, BucketInstance.class, ServiceInstance.class, PlanToBucket.class);
        method.setAccessible(true);

        method.invoke(service, "bucket123", bi, si, ptb);

        assertEquals("bucket123", bi.getBucketId());
        assertEquals("BNG-1", bi.getRule());
        assertEquals(100L, bi.getServiceId());
    }

    @Test
    void testSetBucketDetails_BucketNotFound() throws Exception {
        ServiceInstance si = new ServiceInstance();
        BucketInstance bi = new BucketInstance();

        when(bucketRepository.findByBucketId("bucket123")).thenReturn(Optional.empty());

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setBucketDetails",
                String.class, BucketInstance.class, ServiceInstance.class, PlanToBucket.class);
        method.setAccessible(true);

        try {
            method.invoke(service, "bucket123", bi, si, ptb);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    @Test
    void testSetBucketDetails_QOSNotFound() throws Exception {
        ServiceInstance si = new ServiceInstance();
        BucketInstance bi = new BucketInstance();

        when(bucketRepository.findByBucketId("bucket123")).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(10L)).thenReturn(Optional.empty());

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setBucketDetails",
                String.class, BucketInstance.class, ServiceInstance.class, PlanToBucket.class);
        method.setAccessible(true);

        try {
            method.invoke(service, "bucket123", bi, si, ptb);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    @Test
    void testSetBucketDetails_NonRecurringService() throws Exception {
        ServiceInstance si = new ServiceInstance();
        si.setId(100L);
        si.setRecurringFlag(false);
        si.setExpiryDate(LocalDateTime.now().plusDays(60));

        BucketInstance bi = new BucketInstance();

        when(bucketRepository.findByBucketId("bucket123")).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(10L)).thenReturn(Optional.of(qos));

        Method method = ServiceProvisioningService.class.getDeclaredMethod("setBucketDetails",
                String.class, BucketInstance.class, ServiceInstance.class, PlanToBucket.class);
        method.setAccessible(true);

        method.invoke(service, "bucket123", bi, si, ptb);

        assertEquals(si.getExpiryDate(), bi.getExpiration());
    }

    @Test
    void testGetBNGCodeByRuleId_Success() throws Exception {
        when(qosProfileRepository.findById(10L)).thenReturn(Optional.of(qos));

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getBNGCodeByRuleId", Long.class);
        method.setAccessible(true);

        String bngCode = (String) method.invoke(service, 10L);
        assertEquals("BNG-1", bngCode);
    }

    @Test
    void testGetBNGCodeByRuleId_NotFound() throws Exception {
        when(qosProfileRepository.findById(10L)).thenReturn(Optional.empty());

        Method method = ServiceProvisioningService.class.getDeclaredMethod("getBNGCodeByRuleId", Long.class);
        method.setAccessible(true);

        try {
            method.invoke(service, 10L);
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AAAException);
        }
    }

    // ========== HELPER METHODS ==========

    private void setupSuccessfulActivation() {
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findByUserName(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString())).thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
    }

    @Test
    void testActivateService_SuccessfulIndividualRecurring() {
        setupSuccessfulActivation();
        plan.setQuotaProrationFlag(false);

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        assertEquals("plan123", response.getPlanId());
        verify(kafkaEventPublisher, times(2)).publishDBWriteEvent(any());
    }
    @Test
    void testActivateService_SuccessfulIndividualWithProration() {
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        verify(kafkaEventPublisher, atLeast(1)).publishDBWriteEvent(any());
    }
    @Test
    void testActivateService_NullStartDate_ShouldThrowException() {
        req.setServiceStartDate(null);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
    @Test
    void testActivateService_StartDateInPast_ShouldThrowException() {
        req.setServiceStartDate(LocalDateTime.now().minusDays(5));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
    @Test
    void testActivateService_EndDateBeforeStartDate_ShouldThrowException() {
        req.setServiceStartDate(LocalDateTime.now().plusDays(5));
        req.setServiceEndDate(LocalDateTime.now().plusDays(2));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
    @Test
    void testActivateService_EndDateEqualsStartDate_ShouldThrowException() {
        LocalDateTime date = LocalDateTime.now().plusDays(1);
        req.setServiceStartDate(date);
        req.setServiceEndDate(date);

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
    @Test
    void testActivateService_EndDateInPast_ShouldThrowException() {
        req.setServiceStartDate(LocalDateTime.now());
        req.setServiceEndDate(LocalDateTime.now().minusDays(1));

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
    @Test
    void testActivateService_BucketPublishFailure_CompensatingDelete() {
        setupSuccessfulActivation();

        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build())
                .thenReturn(PublishResult.builder().dcSuccess(false).drSuccess(false).build());

        AAAException ex = assertThrows(AAAException.class, () -> service.activateService(req));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    @Test
    void testUpdateService_UpdateServiceStartDate() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setServiceStartDate(LocalDateTime.now().plusDays(1));

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeast(1)).publishDBWriteEvent(any());
    }

    @Test
    void testUpdateService_UpdateServiceEndDate() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setServiceEndDate(LocalDateTime.now().plusDays(60));

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        assertNotNull(response.getServiceEndDate());
        verify(kafkaEventPublisher, atLeast(1)).publishDBWriteEvent(any());
    }

    @Test
    void testUpdateService_UpdateStatusToSuspended() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setStatus(2); // Suspended

        UpdateResponseDTO response = service.updateService("user123", "plan123",   updateDto);

        assertNotNull(response);
        assertEquals("Suspended", savedServiceInstance.getStatus());
        verify(kafkaEventPublisher, atLeast(1)).publishDBWriteEvent(any());
    }

    @Test
    void testDeleteService_WithMultipleBuckets() {
        BucketInstance bucket2 = new BucketInstance();
        bucket2.setId(2L);
        bucket2.setBucketId("bucket456");

        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong()))
                .thenReturn(List.of(bucketInstance, bucket2));
//        when(kafkaEventPublisher.publishServiceEvent(anyString(), any()))
//                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
//        when(eventMapper.toServiceEvent(any())).thenReturn(new com.axonect.aee.template.baseapp.domain.events.ServiceEvent());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());

        DeleteResponseDTO response = service.deleteService("user123", "plan123", "req-1");

        assertNotNull(response);
        verify(kafkaEventPublisher, times(3)).publishDBWriteEvent(any()); // 1 service + 2 buckets
    }

    @Test
    void testActivateService_WithCarryForwardSettings() {
        ptb.setCarryForward(true);
        ptb.setMaxCarryForward(500L);
        ptb.setTotalCarryForward(100L);
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        verify(kafkaEventPublisher, atLeastOnce()).publishDBWriteEvent(any());
    }

    @Test
    void testActivateService_WithConsumptionLimits() {
        ptb.setConsumptionLimit(100L);
        ptb.setConsumptionLimitWindow("DAILY");
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        verify(bucketRepository).findByBucketId(anyString());
    }

    @Test
    void testUpdateService_MultipleFieldsUpdate() {
        setupSuccessfulUpdate();
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setStatus(2); // Suspended
        updateDto.setQuota(300L);
        updateDto.setServiceStartDate(LocalDateTime.now().plusDays(1));

        UpdateResponseDTO response = service.updateService("user123", "plan123", updateDto);

        assertNotNull(response);
        assertEquals("Suspended", savedServiceInstance.getStatus());
        verify(kafkaEventPublisher, atLeast(2)).publishDBWriteEvent(any());
    }

    // Fix for testGetNumberOfValidityDays_Yearly - Remove this test as YEARLY is not supported
// Instead, keep only DAILY, WEEKLY, MONTHLY tests


    // Fix unnecessary stubbing in setupSuccessfulUpdate - add lenient()
    private void setupSuccessfulUpdate() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findFirstByServiceIdOrderByPriorityAsc(anyLong()))
                .thenReturn(Optional.of(bucketInstance));
        lenient().when(kafkaEventPublisher.publishServiceEvent(anyString(), any()))  // Add lenient()
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        lenient().when(kafkaEventPublisher.publishDBWriteEvent(any()))  // Add lenient()
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        lenient().when(eventMapper.toServiceEvent(any())).thenReturn(new com.axonect.aee.template.baseapp.domain.events.ServiceEvent());
        lenient().when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
        lenient().when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric());
    }
    @Test
    void testActivateService_GroupService_Success() {
        req.setIsGroup(true);
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(userRepository.findFirstByGroupId(anyString())).thenReturn(Optional.of(user));
        when(planRepository.findByPlanId(anyString())).thenReturn(Optional.of(plan));
        when(serviceInstanceRepository.existsByUsernameAndPlanId(anyString(), anyString())).thenReturn(false);
        when(planToBucketRepository.findByPlanId(anyString())).thenReturn(List.of(ptb));
        when(bucketRepository.findByBucketId(anyString())).thenReturn(Optional.of(bucket));
        when(qosProfileRepository.findById(anyLong())).thenReturn(Optional.of(qos));
////        when(kafkaEventPublisher.publishServiceEvent(anyString(), any()))
//                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
//        when(eventMapper.toServiceEvent(any())).thenReturn(new ServiceEvent());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals(user.getGroupId(), response.getUserId());
    }

    @Test
    void testUpdateService_DuplicateRequestId() {
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setRequestId("duplicate-req-123");
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(true);

        AAAException ex = assertThrows(AAAException.class,
                () -> service.updateService("user123", "plan123", updateDto));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

   */
/* @Test
    void testUpdateService_KafkaPartialFailure_ServiceEvent() {
        setupSuccessfulUpdate();
        when(kafkaEventPublisher.publishServiceEvent(anyString(), any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setStatus(2);

        UpdateResponseDTO response = service.updateService("user123", "plan123", updateDto);

        assertNotNull(response);
        verify(kafkaEventPublisher).publishServiceEvent(anyString(), any());
    }*//*


    @Test
    void testUpdateService_KafkaPartialFailure_DBWrite() {
        setupSuccessfulUpdate();
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(false).drSuccess(true).build());
        UpdateRequestDTO updateDto = new UpdateRequestDTO();
        updateDto.setServiceStartDate(LocalDateTime.now().plusDays(1));

        UpdateResponseDTO response = service.updateService("user123", "plan123", updateDto);

        assertNotNull(response);
    }

    @Test
    void testDeleteService_KafkaPartialFailure() {
        when(serviceInstanceRepository.findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(anyString(), anyString()))
                .thenReturn(Optional.of(savedServiceInstance));
        when(bucketInstanceRepository.findByServiceId(anyLong())).thenReturn(List.of(bucketInstance));
////        when(kafkaEventPublisher.publishServiceEvent(anyString(), any()))
//                .thenReturn(PublishResult.builder().dcSuccess(false).drSuccess(true).build());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());
//        when(eventMapper.toServiceEvent(any())).thenReturn(new ServiceEvent());
        when(eventMapper.toServiceDBWriteEvent(anyString(), any()))
                .thenReturn(new DBWriteRequestGeneric());
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        DeleteResponseDTO response = service.deleteService("user123", "plan123", "req-1");

        assertNotNull(response);
    }

    @Test
    void testGetNumberOfValidityDays_Monthly_BillingType1_LeapYear() throws Exception {
        Method method = ServiceProvisioningService.class.getDeclaredMethod("getNumberOfValidityDays",
                String.class, String.class, LocalDateTime.class);
        method.setAccessible(true);

        LocalDateTime date = LocalDateTime.of(2024, 2, 15, 0, 0); // Leap year February
        Integer days = (Integer) method.invoke(service, "MONTHLY", "1", date);
        assertEquals(29, days);
    }

    @Test
    void testActivateService_BillingType2_Success() {
        user.setBilling("2");
        setupSuccessfulActivation();

        ActiveServiceResponseDTO response = service.activateService(req);

        assertNotNull(response);
        assertEquals("user123", response.getUserId());
        verify(serviceInstanceRepository).existsByRequestId(anyString());
    }


}*/
