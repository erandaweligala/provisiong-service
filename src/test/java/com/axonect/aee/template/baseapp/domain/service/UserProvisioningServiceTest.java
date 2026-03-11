package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateUserRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateUserRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.Plan;
import com.axonect.aee.template.baseapp.domain.adapter.AsyncAdaptorInterface;
import com.axonect.aee.template.baseapp.domain.algorithm.EncryptionPlugin;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.enums.Subscription;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.events.UserEvent;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static cn.dev33.satoken.SaManager.log;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * COMPREHENSIVE FIXED TEST SUITE - UserProvisioningService
 * All tests properly mocked and validated
 *
 * Key Fixes:
 * 1. Proper template repository mocking (existsById vs findById)
 * 2. Complete async validation setup with proper array returns
 * 3. Removed unnecessary stubbing
 * 4. Fixed update test async validation
 * 5. Proper validation error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserProvisioningService - Comprehensive Test Suite")
class UserProvisioningServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserToMacRepository userToMacRepository;
    @Mock private EncryptionPlugin encryptionPlugin;
    @Mock private AsyncAdaptorInterface asyncAdaptor;
    @Mock private Executor validationExecutor;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private BucketInstanceRepository bucketInstanceRepository;
    @Mock private SuperTemplateRepository superTemplateRepository;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private EventMapper eventMapper;
    @Mock private CoAManagementService coaManagementService;

    @InjectMocks
    private UserProvisioningService userProvisioningService;

    private CreateUserRequest validCreateRequest;
    private UpdateUserRequest validUpdateRequest;
    private UserEntity testUser;
    private SuperTemplate testTemplate;

    @BeforeEach
    void setUp() {
        // Set service properties
        ReflectionTestUtils.setField(userProvisioningService, "defaultGroupId", "1");
        ReflectionTestUtils.setField(userProvisioningService, "encryptionAlgorithm", "AES");
        ReflectionTestUtils.setField(userProvisioningService, "encryptionSecretKey", "test-secret-key");

        // Setup test template
        testTemplate = SuperTemplate.builder()
                .id(1L)
                .templateName("Default Template")
                .isDefault(true)
                .build();

        // Setup valid create request
        validCreateRequest = CreateUserRequest.builder()
                .userName("testuser")
                .password("password123")
                .encryptionMethod(1)
                .nasPortType("PPPoE")
                .groupId("2")
                .bandwidth("100Mbps")
                .vlanId("100")
                .status(1)
                .subscription(0)
                .concurrency(1)
                .billing("2")
                .requestId("REQ-001")
                .templateId(1L)
                .build();

        // Setup valid update request
        validUpdateRequest = UpdateUserRequest.builder()
                .nasPortType("PPPoE")
                .password("newpassword")
                .encryptionMethod(1)
                .status(1)
                .requestId("REQ-UPDATE-001")
                .build();

        // Setup test user entity
        testUser = UserEntity.builder()
                .userId("USR001")
                .userName("testuser")
                .password("hashedpassword")
                .encryptionMethod(1)
                .nasPortType("PPPoE")
                .groupId("2")
                .bandwidth("100Mbps")
                .status(UserStatus.ACTIVE)
                .subscription(Subscription.PREPAID)
                .concurrency(1)
                .billing("2")
                .requestId("REQ-001")
                .templateId(1L)
                .createdDate(LocalDateTime.now())
                .build();
    }

    // ==================== CREATE USER TESTS ====================

    @Test
    @DisplayName("Create User - Success with all valid fields")
    void createUser_Success() throws Exception {
        // Setup async validation
        setupAsyncValidation(false, false);

        // Setup billing validation - first user in group
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());

        // Setup template validation - CRITICAL: use existsById for validation
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        // Setup encryption
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hashedPassword");

        // Setup Kafka mocks
        setupKafkaMocks();

        // Execute
        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        // Verify
        assertNotNull(response);
        assertEquals("testuser", response.getUserName());
        assertEquals("2", response.getGroupId());
    }

    @Test
    @DisplayName("Create User - Uses default groupId when not provided")
    void createUser_DefaultGroupId() throws Exception {
        validCreateRequest.setGroupId(null);
        validCreateRequest.setBandwidth(null);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("1", response.getGroupId());
    }

    @Test
    @DisplayName("Create User - Throws exception for duplicate userName")
    void createUser_DuplicateUserName() throws Exception {
        setupAsyncValidation(true, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Throws exception for duplicate requestId")
    void createUser_DuplicateRequestId() throws Exception {
        setupAsyncValidation(false, true);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - IPoE with MAC address")
    void createUser_IPoEWithMac() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName("pool1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("IPoE", response.getNasPortType());
        // MAC address might be stored but not returned in CreateUserResponse
        assertNotNull(response.getUserName());
    }

    @Test
    @DisplayName("Create User - Invalid MAC address format")
    void createUser_InvalidMac() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("INVALID");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName("pool1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("Invalid MAC address format"));
    }

    @Test
    @DisplayName("Create User - Duplicate MAC in request")
    void createUser_DuplicateMacInRequest() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E,00:1a:2b:3c:4d:5e");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName("pool1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("Duplicate MAC address"));
    }

    @Test
    @DisplayName("Create User - MAC already exists in database")
    void createUser_MacExistsInDb() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName("pool1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userToMacRepository.findByMacAddressIn(anyList()))
                .thenReturn(Arrays.asList(UserToMac.builder().macAddress("001a2b3c4d5e").build()));

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("Create User - PLAIN encryption (no hashing)")
    void createUser_PlainEncryption() throws Exception {
        validCreateRequest.setEncryptionMethod(0);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        userProvisioningService.createUser(validCreateRequest);

        verify(encryptionPlugin, never()).hashMd5(anyString());
    }

    @Test
    @DisplayName("Create User - CSG_ADL encryption")
    void createUser_CsgAdlEncryption() throws Exception {
        validCreateRequest.setEncryptionMethod(2);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.encrypt(anyString(), anyString(), anyString())).thenReturn("encrypted");
        setupKafkaMocks();

        userProvisioningService.createUser(validCreateRequest);

        verify(encryptionPlugin).encrypt(anyString(), eq("AES"), eq("test-secret-key"));
    }

    @Test
    @DisplayName("Create User - Template not found")
    void createUser_TemplateNotFound() throws Exception {
        validCreateRequest.setTemplateId(999L);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(999L)).thenReturn(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("Template with ID 999 not found"));
    }

    @Test
    @DisplayName("Create User - No default template configured")
    void createUser_NoDefaultTemplate() throws Exception {
        validCreateRequest.setTemplateId(null);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.findByIsDefault(true)).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("No default template"));
    }

    @Test
    @DisplayName("Create User - Uses default template when templateId is null")
    void createUser_DefaultTemplate() throws Exception {
        validCreateRequest.setTemplateId(null);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.findByIsDefault(true)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
        verify(superTemplateRepository).findByIsDefault(true);
    }

    @Test
    @DisplayName("Create User - Kafka complete failure")
    void createUser_KafkaFailure() throws Exception {
        // Arrange - async validation passes (no duplicates)
        setupAsyncValidation(false, false);

        // Template validation passes
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        // Password hashing passes
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hashedPassword");

        // EventMapper returns a non-null event
        DBWriteRequestGeneric mockEvent = DBWriteRequestGeneric.builder()
                .eventType("CREATE")
                .tableName("AAA_USER")
                .build();
        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(mockEvent);

        // *** THE FIX: dcError must be non-null so AAAException.getMessage() isn't null ***
        PublishResult completeFailure = PublishResult.builder()
                .dcSuccess(false)
                .drSuccess(false)
                .dcError("Failed to publish user creation event to Kafka")
                .build();
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(completeFailure);

        // Act
        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        // Assert
        assertNotNull(exception.getMessage(), "Exception message must not be null");
        assertTrue(
                exception.getMessage().contains("Failed to publish"),
                "Expected message to contain 'Failed to publish' but was: " + exception.getMessage()
        );
    }

    @Test
    @DisplayName("Create User - Only DC cluster succeeds")
    void createUser_OnlyDcCluster() throws Exception {
        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");

        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder().dcSuccess(true).drSuccess(false).build());

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Billing - Skip validation for default group")
    void billing_SkipDefaultGroup() throws Exception {
        validCreateRequest.setGroupId("1");
        validCreateRequest.setBandwidth(null);

        setupAsyncValidation(false, false);
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        userProvisioningService.createUser(validCreateRequest);

        verify(userRepository, never()).findFirstByGroupId(anyString());
    }

    @Test
    @DisplayName("Billing - First user in group")
    void billing_FirstUser() throws Exception {
        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Billing - Billing value mismatch")
    void billing_BillingMismatch() throws Exception {
        validCreateRequest.setBilling("1");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2"))
                .thenReturn(Optional.of(UserEntity.builder().billing("2").build()));

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("Billing value mismatch"));
    }

    @Test
    @DisplayName("Billing - Cycle date mismatch")
    void billing_CycleDateMismatch() throws Exception {
        validCreateRequest.setBilling("3");
        validCreateRequest.setCycleDate(15);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2"))
                .thenReturn(Optional.of(UserEntity.builder().billing("3").cycleDate(20).build()));

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));

        assertTrue(exception.getMessage().contains("Cycle date mismatch"));
    }

    // ==================== GET USER TESTS ====================

    @Test
    @DisplayName("Get User - Success")
    void getUser_Success() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        GetUserResponse response = userProvisioningService.getUserByUserName("testuser");

        assertEquals("testuser", response.getUserName());
        assertEquals("Default Template", response.getTemplateName());
    }

    @Test
    @DisplayName("Get User - With MAC addresses")
    void getUser_WithMac() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser"))
                .thenReturn(Arrays.asList(
                        UserToMac.builder().originalMacAddress("00:1A:2B:3C:4D:5E").build()
                ));
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        GetUserResponse response = userProvisioningService.getUserByUserName("testuser");

        assertTrue(response.getMacAddress().contains("00:1A:2B:3C:4D:5E"));
    }

    @Test
    @DisplayName("Get User - User not found")
    void getUser_NotFound() {
        when(userRepository.findByUserName("nonexistent")).thenReturn(Optional.empty());

        assertThrows(AAAException.class, () ->
                userProvisioningService.getUserByUserName("nonexistent"));
    }

    // ==================== GET ALL USERS TESTS ====================

    @Test
    @DisplayName("Get All Users - Success with pagination")
    void getAllUsers_Success() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, null, null, null, null);

        assertEquals(1, response.getTotalRecords());
        assertEquals(1, response.getUsers().size());
    }

    @Test
    @DisplayName("Get All Users - Filter by status")
    void getAllUsers_StatusFilter() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, 1, null, null, null);

        assertEquals(1, response.getUsers().size());
    }

    @Test
    @DisplayName("Get All Users - Filter by userName")
    void getAllUsers_UserNameFilter() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, null, "test", null, null);

        assertEquals(1, response.getUsers().size());
    }

    @Test
    @DisplayName("Get All Users - Filter by groupId")
    void getAllUsers_GroupIdFilter() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, null, null, null, "2");

        assertEquals(1, response.getUsers().size());
    }

    @Test
    @DisplayName("Get All Users - Filter by subscription")
    void getAllUsers_SubscriptionFilter() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, null, null, 0, null);

        assertEquals(1, response.getUsers().size());
    }

    // ==================== UPDATE USER TESTS ====================



    @Test
    @DisplayName("Update User - User not found")
    void updateUser_NotFound() {
        when(userRepository.findByUserName("nonexistent")).thenReturn(Optional.empty());

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("nonexistent", validUpdateRequest));
    }


    @Test
    @DisplayName("Update User - Duplicate MAC address")
    void updateUser_DuplicateMac() throws Exception {
        validUpdateRequest.setMacAddress("00:1A:2B:3C:4D:5E");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByMacAddressInAndUserNameNot(anyList(), anyString()))
                .thenReturn(Arrays.asList(UserToMac.builder().macAddress("001a2b3c4d5e").build()));

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("Update User - Duplicate requestId")
    void updateUser_DuplicateRequestId() throws Exception {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(true);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("Update User - Same requestId as existing")
    void updateUser_SameRequestId() {
        testUser.setRequestId("REQ-UPDATE-001");
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertNotNull(exception);
        // Exception is thrown for duplicate request
    }

    @Test
    @DisplayName("Update User - Success with template change")
    void updateUser_WithTemplate() throws Exception {
        validUpdateRequest.setTemplateId(2L);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(superTemplateRepository.existsById(2L)).thenReturn(true);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(2L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser(
                "testuser", validUpdateRequest);

        assertNotNull(response);
        verify(superTemplateRepository).existsById(2L);
    }

    // ==================== DELETE USER TESTS ====================

    @Test
    @DisplayName("Delete User - Success")
    void deleteUser_Success() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        DeleteUserResponse response = userProvisioningService.deleteUser(
                "testuser", "REQ-DELETE");

        assertNotNull(response);
        assertEquals("success", response.getStatus());
    }

    @Test
    @DisplayName("Delete User - User not found")
    void deleteUser_NotFound() {
        when(userRepository.findByUserName("nonexistent")).thenReturn(Optional.empty());

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.deleteUser("nonexistent", "REQ-DELETE"));

        assertTrue(exception.getMessage().contains("not found"));
    }

    // ==================== GET BY GROUP TESTS ====================

    @Test
    @DisplayName("Get Users By GroupId - Success")
    void getByGroupId_Success() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findByGroupId(eq("2"), any(Pageable.class))).thenReturn(page);

        PagedGroupUsersResponse response = userProvisioningService.getUsersByGroupId(
                "2", 1, 20, null);

        assertEquals("2", response.getGroupId());
        assertEquals(1, response.getTotalUsers());
        assertEquals(1, response.getUsers().size());
    }

    @Test
    @DisplayName("Get Users By GroupId - With status filter")
    void getByGroupId_StatusFilter() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findByGroupIdAndStatus(eq("2"), eq(UserStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(page);

        PagedGroupUsersResponse response = userProvisioningService.getUsersByGroupId(
                "2", 1, 20, 1);

        assertEquals(1, response.getTotalUsers());
    }

    // ==================== GET USER LIST TESTS ====================

    @Test
    @DisplayName("Get User List - Returns all usernames")
    void getUserList_Success() {
        when(userRepository.findAll()).thenReturn(Arrays.asList(testUser));

        List<UserListResponse> response = userProvisioningService.getUserList();

        assertEquals(1, response.size());
        assertEquals("testuser", response.get(0).getUserName());
    }

    @Test
    @DisplayName("Get User List - Empty result")
    void getUserList_Empty() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        List<UserListResponse> response = userProvisioningService.getUserList();

        assertTrue(response.isEmpty());
    }

    // ==================== UTILITY METHOD TESTS ====================

    @Test
    @DisplayName("Normalize MAC - Colons separator")
    void normalizeMac_Colons() {
        String result = userProvisioningService.normalizeMacAddress("00:1A:2B:3C:4D:5E");
        assertEquals("001a2b3c4d5e", result);
    }

    @Test
    @DisplayName("Normalize MAC - Hyphens separator")
    void normalizeMac_Hyphens() {
        String result = userProvisioningService.normalizeMacAddress("00-1A-2B-3C-4D-5E");
        assertEquals("001a2b3c4d5e", result);
    }

    @Test
    @DisplayName("Normalize MAC - Dots separator")
    void normalizeMac_Dots() {
        String result = userProvisioningService.normalizeMacAddress("001A.2B3C.4D5E");
        assertEquals("001a2b3c4d5e", result);
    }

    @Test
    @DisplayName("Normalize MAC - No separator")
    void normalizeMac_NoSeparator() {
        String result = userProvisioningService.normalizeMacAddress("001A2B3C4D5E");
        assertEquals("001a2b3c4d5e", result);
    }

    @Test
    @DisplayName("Parse Status - Valid values")
    void parseStatus_Success() {
        assertEquals(UserStatus.ACTIVE, userProvisioningService.parseStatus(1));
        assertEquals(UserStatus.BARRED, userProvisioningService.parseStatus(2));
        assertEquals(UserStatus.INACTIVE, userProvisioningService.parseStatus(3));
    }

    @Test
    @DisplayName("Parse Status - Invalid value")
    void parseStatus_Fail() {
        assertThrows(AAAException.class, () ->
                userProvisioningService.parseStatus(99));
    }

    @Test
    @DisplayName("Parse Subscription - Valid values")
    void parseSubscription_Success() {
        assertEquals(Subscription.PREPAID, userProvisioningService.parseSubscription(0));
        assertEquals(Subscription.POSTPAID, userProvisioningService.parseSubscription(1));
        assertEquals(Subscription.HYBRID, userProvisioningService.parseSubscription(2));
    }

    @Test
    @DisplayName("Parse Subscription - Invalid value")
    void parseSubscription_Fail() {
        assertThrows(AAAException.class, () ->
                userProvisioningService.parseSubscription(99));
    }

    // ==================== ADDITIONAL COVERAGE TESTS ====================



    @Test
    @DisplayName("Create User - PPPoE without MAC")
    void createUser_PPPoEWithoutMac() throws Exception {
        validCreateRequest.setNasPortType("PPPoE");
        validCreateRequest.setMacAddress(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("PPPoE", response.getNasPortType());
    }

    @Test
    @DisplayName("Create User - Multiple MAC addresses")
    void createUser_MultipleMacs() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E,00:1A:2B:3C:4D:5F");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName("TEST_POOL"); //
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response =
                userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }


    @Test
    @DisplayName("Create User - Prepaid subscription")
    void createUser_PrepaidSubscription() throws Exception {
        validCreateRequest.setSubscription(0); // PREPAID

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Create User - Postpaid subscription")
    void createUser_PostpaidSubscription() throws Exception {
        validCreateRequest.setSubscription(1); // POSTPAID

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Create User - Hybrid subscription")
    void createUser_HybridSubscription() throws Exception {
        validCreateRequest.setSubscription(2); // HYBRID

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Create User - Invalid subscription value")
    void createUser_InvalidSubscription() throws Exception {
        validCreateRequest.setSubscription(99);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Billing consistency check passes")
    void createUser_BillingConsistencyPass() throws Exception {
        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2"))
                .thenReturn(Optional.of(UserEntity.builder().billing("2").build()));
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Create User - Cycle date consistency check passes")
    void createUser_CycleDateConsistencyPass() throws Exception {
        validCreateRequest.setBilling("3");
        validCreateRequest.setCycleDate(15);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2"))
                .thenReturn(Optional.of(UserEntity.builder().billing("3").cycleDate(15).build()));
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }




    @Test
    @DisplayName("Update User - Update concurrency")
    void updateUser_Concurrency() throws Exception {
        validUpdateRequest.setConcurrency(5);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Update User - PLAIN encryption")
    void updateUser_PlainEncryption() throws Exception {
        validUpdateRequest.setPassword("plainpass");
        validUpdateRequest.setEncryptionMethod(0); // PLAIN

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        verify(encryptionPlugin, never()).hashMd5(anyString());
    }

    @Test
    @DisplayName("Update User - CSG_ADL encryption")
    void updateUser_CsgAdlEncryption() throws Exception {
        validUpdateRequest.setPassword("encrypted");
        validUpdateRequest.setEncryptionMethod(2); // CSG_ADL

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(encryptionPlugin.encrypt(anyString(), anyString(), anyString())).thenReturn("enc");
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        verify(encryptionPlugin).encrypt(anyString(), eq("AES"), eq("test-secret-key"));
    }

    @Test
    @DisplayName("Get User - Without template")
    void getUser_WithoutTemplate() {
        testUser.setTemplateId(null);
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());

        GetUserResponse response = userProvisioningService.getUserByUserName("testuser");

        assertEquals("testuser", response.getUserName());
        assertNull(response.getTemplateName());
    }

    @Test
    @DisplayName("Get User - Template not found")
    void getUser_TemplateNotFound() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.empty());

        GetUserResponse response = userProvisioningService.getUserByUserName("testuser");

        assertEquals("testuser", response.getUserName());
    }

    @Test
    @DisplayName("Get All Users - Empty result")
    void getAllUsers_EmptyResult() {
        Page<UserEntity> emptyPage = new PageImpl<>(Collections.emptyList());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, null, null, null, null);

        assertEquals(0, response.getTotalRecords());
        assertTrue(response.getUsers().isEmpty());
    }

    @Test
    @DisplayName("Get All Users - Multiple filters")
    void getAllUsers_MultipleFilters() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 10, 1, "test", 0, "2");

        assertEquals(1, response.getTotalRecords());
    }

    @Test
    @DisplayName("Get All Users - Large page size")
    void getAllUsers_LargePageSize() {
        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        PagedUserResponse response = userProvisioningService.getAllUsers(
                1, 100, null, null, null, null);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Delete User - With MAC addresses")
    void deleteUser_WithMac() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser"))
                .thenReturn(Arrays.asList(
                        UserToMac.builder().macAddress("001a2b3c4d5e").build(),
                        UserToMac.builder().macAddress("001a2b3c4d5f").build()
                ));
        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());

        DeleteUserResponse response = userProvisioningService.deleteUser("testuser", "REQ-DELETE");

        assertEquals("success", response.getStatus());
        verify(userToMacRepository).findByUserName("testuser");
    }

    @Test
    @DisplayName("Get By GroupId - Empty result")
    void getByGroupId_EmptyResult() {
        Page<UserEntity> emptyPage = new PageImpl<>(Collections.emptyList());
        when(userRepository.findByGroupId(eq("999"), any(Pageable.class))).thenReturn(emptyPage);

        PagedGroupUsersResponse response = userProvisioningService.getUsersByGroupId("999", 1, 20, null);

        assertEquals(0, response.getTotalUsers());
        assertTrue(response.getUsers().isEmpty());
    }

    @Test
    @DisplayName("Get By GroupId - Multiple users")
    void getByGroupId_MultipleUsers() {
        UserEntity user2 = UserEntity.builder()
                .userId("USR002").userName("testuser2").groupId("2")
                .status(UserStatus.ACTIVE).build();

        Page<UserEntity> page = new PageImpl<>(Arrays.asList(testUser, user2));
        when(userRepository.findByGroupId(eq("2"), any(Pageable.class))).thenReturn(page);

        PagedGroupUsersResponse response = userProvisioningService.getUsersByGroupId("2", 1, 20, null);

        assertEquals(2, response.getTotalUsers());
        assertEquals(2, response.getUsers().size());
    }

    @Test
    @DisplayName("Normalize MAC - Mixed case")
    void normalizeMac_MixedCase() {
        String result = userProvisioningService.normalizeMacAddress("aA:bB:cC:dD:eE:fF");
        assertEquals("aabbccddeeff", result);
    }

    @Test
    @DisplayName("Normalize MAC - Already normalized")
    void normalizeMac_AlreadyNormalized() {
        String result = userProvisioningService.normalizeMacAddress("001a2b3c4d5e");
        assertEquals("001a2b3c4d5e", result);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Setup async validation for create operations
     * @param userExists whether userName already exists
     * @param requestIdExists whether requestId already exists
     */
    @SuppressWarnings("unchecked")
    private void setupAsyncValidation(boolean userExists, boolean requestIdExists) {
        CompletableFuture<Object>[] futures = new CompletableFuture[2];
        futures[0] = CompletableFuture.completedFuture(userExists);
        futures[1] = CompletableFuture.completedFuture(requestIdExists);

        when(asyncAdaptor.supplyAll(anyLong(), any(), any())).thenReturn(futures);
    }

    /**
     * Setup async validation for update operations
     * @param requestIdExists whether requestId already exists for another user
     */
    @SuppressWarnings("unchecked")
    private void setupUpdateAsyncValidation(boolean requestIdExists) {
        CompletableFuture<Object>[] futures = new CompletableFuture[1];
        futures[0] = CompletableFuture.completedFuture(requestIdExists);

        when(asyncAdaptor.supplyAll(anyLong(), any())).thenReturn(futures);
    }

    /**
     * Setup common Kafka mocks for create operations
     */
    private void setupKafkaMocks() {
        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenReturn(PublishResult.builder()
                        .dcSuccess(true)
                        .drSuccess(true)
                        .build());
    }

    /**
     * Setup Kafka mocks for update operations
     */
    private void setupUpdateKafka() {
        when(eventMapper.toDBWriteEvent(anyString(), any(), anyString()))
                .thenReturn(new DBWriteRequestGeneric());
    }
    // Add these tests to the existing UserProvisioningServiceTest class

// ==================== CRITICAL VALIDATION TESTS (Missing Coverage) ====================



    @Test
    @DisplayName("Create User - Invalid NAS port type value")
    void createUser_InvalidNasPortType() throws Exception {
        validCreateRequest.setNasPortType("INVALID");

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }



    @Test
    @DisplayName("Create User - IPoE without IP allocation")
    void createUser_IPoENoIpAllocation() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation(null);
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }



    @Test
    @DisplayName("Create User - Static IP without IPv4 or IPv6")
    void createUser_StaticNoIp() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("static");
        validCreateRequest.setIpv4(null);
        validCreateRequest.setIpv6(null);
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Dynamic without IP pool")
    void createUser_DynamicNoPool() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("Dynamic");
        validCreateRequest.setIpPoolName(null);
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Invalid billing value")
    void createUser_InvalidBillingValue() throws Exception {
        validCreateRequest.setBilling("99");

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }


    @Test
    @DisplayName("Create User - Concurrency less than 1")
    void createUser_ConcurrencyLessThanOne() throws Exception {
        validCreateRequest.setConcurrency(0);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Null concurrency")
    void createUser_NullConcurrency() throws Exception {
        validCreateRequest.setConcurrency(null);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Null status")
    void createUser_NullStatusValue() throws Exception {
        validCreateRequest.setStatus(null);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Null requestId")
    void createUser_NullRequestIdValue() throws Exception {
        validCreateRequest.setRequestId(null);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - Blank requestId")
    void createUser_BlankRequestId() throws Exception {
        validCreateRequest.setRequestId("");

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }



    @Test
    @DisplayName("Create User - NAS port type is null")
    void createUser_NullNasPortType() throws Exception {
        validCreateRequest.setNasPortType(null);

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

    @Test
    @DisplayName("Create User - NAS port type is blank")
    void createUser_BlankNasPortType() throws Exception {
        validCreateRequest.setNasPortType("");

        setupAsyncValidation(false, false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.createUser(validCreateRequest));
    }

// ==================== UPDATE USER VALIDATION TESTS ====================

    @Test
    @DisplayName("Update User - Change to PPPoE without password")
    void updateUser_ChangeToPPPoEWithoutPassword() throws Exception {
        testUser.setPassword(null);
        testUser.setNasPortType("IPoE");

        validUpdateRequest.setNasPortType("PPPoE");
        validUpdateRequest.setPassword(null);
        validUpdateRequest.setEncryptionMethod(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }

    @Test
    @DisplayName("Update User - Change to IPoE without MAC")
    void updateUser_ChangeToIPoEWithoutMac() throws Exception {
        testUser.setMacAddress(null);
        testUser.setNasPortType("PPPoE");

        validUpdateRequest.setNasPortType("IPoE");
        validUpdateRequest.setMacAddress(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }

    @Test
    @DisplayName("Update User - Change to IPoE without IP allocation")
    void updateUser_ChangeToIPoEWithoutIpAllocation() throws Exception {
        testUser.setIpAllocation(null);
        testUser.setNasPortType("PPPoE");

        validUpdateRequest.setNasPortType("IPoE");
        validUpdateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validUpdateRequest.setIpAllocation(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByMacAddressInAndUserNameNot(anyList(), anyString()))
                .thenReturn(Collections.emptyList());

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }

    @Test
    @DisplayName("Update User - Invalid IP allocation value")
    void updateUser_InvalidIpAllocationValue() throws Exception {
        validUpdateRequest.setIpAllocation("INVALID");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("ip_allocation"));
    }

    @Test
    @DisplayName("Update User - Change to static without IP")
    void updateUser_ChangeToStaticWithoutIp() throws Exception {
        testUser.setIpv4(null);
        testUser.setIpv6(null);

        validUpdateRequest.setIpAllocation("static");
        validUpdateRequest.setIpv4(null);
        validUpdateRequest.setIpv6(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }

    @Test
    @DisplayName("Update User - Change to Dynamic without pool")
    void updateUser_ChangeToDynamicWithoutPool() throws Exception {
        testUser.setIpPoolName(null);

        validUpdateRequest.setIpAllocation("Dynamic");
        validUpdateRequest.setIpPoolName(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }

    @Test
    @DisplayName("Update User - Billing cycle without cycle date")
    void updateUser_BillingCycleWithoutCycleDate() throws Exception {
        validUpdateRequest.setBilling("3");
        validUpdateRequest.setCycleDate(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("cycle_date"));
    }

    @Test
    @DisplayName("Update User - Invalid cycle date (out of range)")
    void updateUser_InvalidCycleDateRange() throws Exception {
        validUpdateRequest.setCycleDate(30);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("between 1 and 28"));
    }

    @Test
    @DisplayName("Update User - Concurrency less than 1")
    void updateUser_ConcurrencyLessThanOne() throws Exception {
        validUpdateRequest.setConcurrency(0);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));
    }


    @Test
    @DisplayName("Update User - Duplicate contact numbers in request")
    void updateUser_DuplicateContactNumbersInRequest() throws Exception {
        validUpdateRequest.setContactNumber("1234567890,1234567890");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("Duplicate contact number"));
    }

    @Test
    @DisplayName("Update User - Duplicate contact emails in request (case insensitive)")
    void updateUser_DuplicateContactEmailsInRequest() throws Exception {
        validUpdateRequest.setContactEmail("test@example.com,TEST@EXAMPLE.COM");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("Duplicate contact email"));
    }

// ==================== EDGE CASE AND UTILITY TESTS ====================

    @Test
    @DisplayName("Normalize MAC - Space separator")
    void normalizeMac_WithSpaceSeparator() {
        String result = userProvisioningService.normalizeMacAddress("00 1A 2B 3C 4D 5E");
        assertEquals("001a2b3c4d5e", result);
    }

    @Test
    @DisplayName("Normalize MAC - Null input")
    void normalizeMac_NullInput() {
        String result = userProvisioningService.normalizeMacAddress(null);
        assertNull(result);
    }

    @Test
    @DisplayName("Normalize MAC - Blank input")
    void normalizeMac_BlankInput() {
        String result = userProvisioningService.normalizeMacAddress("  ");
        assertNotNull(result);
    }

    @Test
    @DisplayName("Parse Status - Code 2 (BARRED)")
    void parseStatus_CodeTwo() {
        UserStatus result = userProvisioningService.parseStatus(2);
        assertEquals(UserStatus.BARRED, result);
    }

    @Test
    @DisplayName("Parse Status - Code 3 (INACTIVE)")
    void parseStatus_CodeThree() {
        UserStatus result = userProvisioningService.parseStatus(3);
        assertEquals(UserStatus.INACTIVE, result);
    }

    @Test
    @DisplayName("Parse Status - Null input")
    void parseStatus_NullInput() {
        UserStatus result = userProvisioningService.parseStatus(null);
        assertNull(result);
    }

    @Test
    @DisplayName("Parse Subscription - Code 1 (POSTPAID)")
    void parseSubscription_CodeOne() {
        Subscription result = userProvisioningService.parseSubscription(1);
        assertEquals(Subscription.POSTPAID, result);
    }

    @Test
    @DisplayName("Parse Subscription - Code 2 (HYBRID)")
    void parseSubscription_CodeTwo() {
        Subscription result = userProvisioningService.parseSubscription(2);
        assertEquals(Subscription.HYBRID, result);
    }

    @Test
    @DisplayName("Parse Subscription - Null input")
    void parseSubscription_NullInput() {
        Subscription result = userProvisioningService.parseSubscription(null);
        assertNull(result);
    }

    // ==================== HELPER/UTILITY METHOD COVERAGE ====================

    @Test
    @DisplayName("Validate Contact Numbers - Success with multiple valid numbers")
    void validateContactNumbers_Success() throws Exception {
        validCreateRequest.setContactNumber("1234567890,9876543210,5555555555");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Validate Contact Emails - Success with multiple valid emails")
    void validateContactEmails_Success() throws Exception {
        validCreateRequest.setContactEmail("test1@example.com,test2@example.com");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("IsBlank - Null string")
    void isBlank_NullString() {
        // Call through update to test isBlank method
        validUpdateRequest.setContactName(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Update - With all optional fields")
    void updateUser_WithAllOptionalFields() throws Exception {
        validUpdateRequest.setVlanId("200");
        validUpdateRequest.setCircuitId("CID123");
        validUpdateRequest.setRemoteId("RID456");
        validUpdateRequest.setIpPoolName("newpool");
        validUpdateRequest.setIpv4("192.168.1.100");
        validUpdateRequest.setIpv6("2001:db8::1");
        validUpdateRequest.setContactName("John Doe");
        validUpdateRequest.setContactEmail("john@example.com");
        validUpdateRequest.setContactNumber("1234567890");
        validUpdateRequest.setSessionTimeout("3600");
        validUpdateRequest.setBillingAccountRef("ACC123");
        validUpdateRequest.setSubscription(1);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals("200", response.getVlanId());
    }

    @Test
    @DisplayName("Update - Change groupId and bandwidth together")
    void updateUser_ChangeGroupAndBandwidth() throws Exception {
        validUpdateRequest.setGroupId("3");
        validUpdateRequest.setBandwidth("200Mbps");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.findAllByGroupId("3")).thenReturn(Collections.emptyList());
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals("3", response.getGroupId());
        assertEquals("200Mbps", response.getBandwidth());
    }

    @Test
    @DisplayName("Update - Only bandwidth without groupId")
    void updateUser_OnlyBandwidth() throws Exception {
        validUpdateRequest.setGroupId(null);
        validUpdateRequest.setBandwidth("150Mbps");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals("150Mbps", response.getBandwidth());
    }


    @Test
    @DisplayName("Create User - Valid cycle date at lower boundary (1)")
    void createUser_CycleDateLowerBoundary() throws Exception {
        validCreateRequest.setCycleDate(1);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals(1, response.getCycleDate());
    }

    @Test
    @DisplayName("Create User - Valid cycle date at upper boundary (28)")
    void createUser_CycleDateUpperBoundary() throws Exception {
        validCreateRequest.setCycleDate(28);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals(28, response.getCycleDate());
    }

    @Test
    @DisplayName("Create User - Static with IPv4 only")
    void createUser_StaticWithIpv4Only() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("static");
        validCreateRequest.setIpv4("192.168.1.100");
        validCreateRequest.setIpv6(null);
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("192.168.1.100", response.getIpv4());
    }

    @Test
    @DisplayName("Create User - Static with IPv6 only")
    void createUser_StaticWithIpv6Only() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("static");
        validCreateRequest.setIpv4(null);
        validCreateRequest.setIpv6("2001:db8::1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("2001:db8::1", response.getIpv6());
    }

    @Test
    @DisplayName("Create User - Static with both IPv4 and IPv6")
    void createUser_StaticWithBothIps() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("static");
        validCreateRequest.setIpv4("192.168.1.100");
        validCreateRequest.setIpv6("2001:db8::1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("192.168.1.100", response.getIpv4());
        assertEquals("2001:db8::1", response.getIpv6());
    }

    @Test
    @DisplayName("Update - Static with IPv4 from request")
    void updateUser_StaticWithIpv4FromRequest() throws Exception {
        testUser.setIpv4(null);
        testUser.setIpv6(null);

        validUpdateRequest.setIpAllocation("static");
        validUpdateRequest.setIpv4("10.0.0.1");
        validUpdateRequest.setIpv6(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals("10.0.0.1", response.getIpv4());
    }

    @Test
    @DisplayName("Update - Dynamic with pool from request")
    void updateUser_DynamicWithPoolFromRequest() throws Exception {
        testUser.setIpPoolName(null);

        validUpdateRequest.setIpAllocation("Dynamic");
        validUpdateRequest.setIpPoolName("newpool");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals("newpool", response.getIpPoolName());
    }


    @Test
    @DisplayName("Update - Update miscellaneous fields")
    void updateUser_UpdateMiscFields() throws Exception {
        validUpdateRequest.setBillingAccountRef("NEW-ACC-456");

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals("NEW-ACC-456", response.getBillingAccountRef());
    }

    @Test
    @DisplayName("Create User - With all network fields")
    void createUser_WithAllNetworkFields() throws Exception {
        validCreateRequest.setVlanId("100");
        validCreateRequest.setCircuitId("CIRCUIT-001");
        validCreateRequest.setRemoteId("REMOTE-001");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("100", response.getVlanId());
        assertEquals("CIRCUIT-001", response.getCircuitId());
        assertEquals("REMOTE-001", response.getRemoteId());
    }

    @Test
    @DisplayName("Create User - With billing account reference")
    void createUser_WithBillingAccountRef() throws Exception {
        validCreateRequest.setBillingAccountRef("ACC-12345");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("ACC-12345", response.getBillingAccountRef());
    }

    @Test
    @DisplayName("Create User - Billing Daily (1)")
    void createUser_BillingDaily() throws Exception {
        validCreateRequest.setBilling("1");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("1", response.getBilling());
    }

    @Test
    @DisplayName("Create User - Billing Monthly (2)")
    void createUser_BillingMonthly() throws Exception {
        validCreateRequest.setBilling("2");

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("hash");
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertEquals("2", response.getBilling());
    }

    @Test
    @DisplayName("Get User - Fetch MAC addresses from repository")
    void getUser_FetchMacAddresses() {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        when(userToMacRepository.findByUserName("testuser"))
                .thenReturn(Arrays.asList(
                        UserToMac.builder().originalMacAddress("00:1A:2B:3C:4D:5E").build(),
                        UserToMac.builder().originalMacAddress("00:1A:2B:3C:4D:5F").build()
                ));
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));

        GetUserResponse response = userProvisioningService.getUserByUserName("testuser");

        assertTrue(response.getMacAddress().contains("00:1A:2B:3C:4D:5E"));
        assertTrue(response.getMacAddress().contains("00:1A:2B:3C:4D:5F"));
    }

    @Test
    @DisplayName("Update User - Success with status change")
    void updateUser_WithStatus() throws Exception {
        validUpdateRequest.setStatus(2);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        // Mock CoA service call
        doNothing().when(coaManagementService).sendCoARequest(anyString(), any(UserStatus.class), any(UserStatus.class));

        UpdateUserResponse response = userProvisioningService.updateUser(
                "testuser", validUpdateRequest);

        assertNotNull(response);
        verify(coaManagementService).sendCoARequest(eq("testuser"), eq(UserStatus.ACTIVE), eq(UserStatus.BARRED));
    }

    @Test
    @DisplayName("Update User - Invalid status value")
    void updateUser_InvalidStatus() {
        validUpdateRequest.setStatus(99);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);

        AAAException exception = assertThrows(AAAException.class, () ->
                userProvisioningService.updateUser("testuser", validUpdateRequest));

        assertTrue(exception.getMessage().contains("Invalid status"));
    }

    @Test
    @DisplayName("Update User - Only status change")
    void updateUser_OnlyStatus() throws Exception {
        UpdateUserRequest statusOnlyRequest = UpdateUserRequest.builder()
                .status(3) // INACTIVE
                .requestId("REQ-STATUS-001")
                .build();

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        // Mock CoA service call
        doNothing().when(coaManagementService).sendCoARequest(anyString(), any(UserStatus.class), any(UserStatus.class));

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", statusOnlyRequest);

        assertNotNull(response);
        verify(coaManagementService).sendCoARequest(eq("testuser"), eq(UserStatus.ACTIVE), eq(UserStatus.INACTIVE));
    }

    @Test
    @DisplayName("Update User - Change subscription type")
    void updateUser_ChangeSubscription() throws Exception {
        testUser.setSubscription(Subscription.PREPAID);

        validUpdateRequest.setSubscription(2); // HYBRID

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();
        // DO NOT mock coaManagementService here - it won't be called

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertEquals(2, response.getSubscription());
        // Verify CoA was NOT called since status didn't change
        verify(coaManagementService, never()).sendCoARequest(anyString(), any(UserStatus.class), any(UserStatus.class));
    }

    @Test
    @DisplayName("Update User - Success with password change")
    void updateUser_WithPassword() throws Exception {
        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("newhash");
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();
        // DO NOT mock coaManagementService here

        UpdateUserResponse response = userProvisioningService.updateUser(
                "testuser", validUpdateRequest);

        assertEquals("testuser", response.getUserName());
        verify(encryptionPlugin).hashMd5("newpassword");
        // Verify CoA was NOT called since status didn't change
        verify(coaManagementService, never()).sendCoARequest(anyString(), any(UserStatus.class), any(UserStatus.class));
    }
    @Test
    @DisplayName("Create User - Static with both IPv4 and IPv6 from existing user values")
    void createUser_StaticWithBothIpsFromExisting() throws Exception {
        validCreateRequest.setNasPortType("IPoE");
        validCreateRequest.setMacAddress("00:1A:2B:3C:4D:5E");
        validCreateRequest.setIpAllocation("static");
        validCreateRequest.setIpv4("10.0.0.1");
        validCreateRequest.setIpv6("2001:db8::1");
        validCreateRequest.setPassword(null);
        validCreateRequest.setEncryptionMethod(null);

        setupAsyncValidation(false, false);
        when(userRepository.findFirstByGroupId("2")).thenReturn(Optional.empty());
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(Collections.emptyList());
        when(superTemplateRepository.existsById(1L)).thenReturn(true);
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupKafkaMocks();

        CreateUserResponse response = userProvisioningService.createUser(validCreateRequest);

        assertNotNull(response);
        assertEquals("10.0.0.1", response.getIpv4());
        assertEquals("2001:db8::1", response.getIpv6());
    }

    @Test
    @DisplayName("Update User - Change to static with existing IPv4")
    void updateUser_ChangeToStaticWithExistingIpv4() throws Exception {
        testUser.setIpv4("192.168.1.1");
        testUser.setIpv6(null);
        testUser.setIpAllocation("Dynamic");

        validUpdateRequest.setIpAllocation("static");
        validUpdateRequest.setIpv4(null);
        validUpdateRequest.setIpv6(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals("static", response.getIpAllocation());
    }

    @Test
    @DisplayName("Update User - Change to static with existing IPv6")
    void updateUser_ChangeToStaticWithExistingIpv6() throws Exception {
        testUser.setIpv4(null);
        testUser.setIpv6("2001:db8::1");
        testUser.setIpAllocation("Dynamic");

        validUpdateRequest.setIpAllocation("static");
        validUpdateRequest.setIpv4(null);
        validUpdateRequest.setIpv6(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals("static", response.getIpAllocation());
    }

    @Test
    @DisplayName("Update User - Change to Dynamic with existing pool")
    void updateUser_ChangeToDynamicWithExistingPool() throws Exception {
        testUser.setIpPoolName("existingpool");
        testUser.setIpAllocation("static");

        validUpdateRequest.setIpAllocation("Dynamic");
        validUpdateRequest.setIpPoolName(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals("Dynamic", response.getIpAllocation());
        assertEquals("existingpool", response.getIpPoolName());
    }

    @Test
    @DisplayName("Update User - Update password with only encryption method from request")
    void updateUser_PasswordWithEncryptionFromRequest() throws Exception {
        testUser.setEncryptionMethod(null);

        validUpdateRequest.setPassword("newpassword123");
        validUpdateRequest.setEncryptionMethod(1);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("newhash");
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        verify(encryptionPlugin).hashMd5("newpassword123");
    }

    @Test
    @DisplayName("Update User - Update password with encryption method from database")
    void updateUser_PasswordWithEncryptionFromDb() throws Exception {
        testUser.setEncryptionMethod(1);

        validUpdateRequest.setPassword("newpassword123");
        validUpdateRequest.setEncryptionMethod(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(encryptionPlugin.hashMd5(anyString())).thenReturn("newhash");
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        verify(encryptionPlugin).hashMd5("newpassword123");
    }

    @Test
    @DisplayName("Update User - Update only encryption method without password")
    void updateUser_OnlyEncryptionMethodNoPassword() throws Exception {
        testUser.setPassword("existingpassword");
        testUser.setEncryptionMethod(1);

        validUpdateRequest.setPassword(null);
        validUpdateRequest.setEncryptionMethod(2);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals(2, response.getEncryptionMethod());
    }


    @Test
    @DisplayName("Update User - Update billing without cycle date when not billing cycle")
    void updateUser_BillingWithoutCycleDateNonCycle() throws Exception {
        validUpdateRequest.setBilling("2");
        validUpdateRequest.setCycleDate(null);

        when(userRepository.findByUserName("testuser")).thenReturn(Optional.of(testUser));
        setupUpdateAsyncValidation(false);
        when(userToMacRepository.findByUserName("testuser")).thenReturn(Collections.emptyList());
        when(superTemplateRepository.findById(1L)).thenReturn(Optional.of(testTemplate));
        setupUpdateKafka();

        UpdateUserResponse response = userProvisioningService.updateUser("testuser", validUpdateRequest);

        assertNotNull(response);
        assertEquals("2", response.getBilling());
    }

    @Test
    void getServiceDetailsByUsername_success_groupIdNotOne() {

        String username = "testUser";

        UserEntity user = new UserEntity();
        user.setUserName(username);
        user.setSubscription(Subscription.POSTPAID);
        user.setStatus(UserStatus.ACTIVE);
        user.setGroupId("999");

        when(userRepository.findByUserName(username))
                .thenReturn(Optional.of(user));

        BucketFlatProjection bucket = mock(BucketFlatProjection.class);

        when(bucket.getPlanName()).thenReturn("Unlimited Plan");
        when(bucket.getPriority()).thenReturn(1L);
        when(bucket.getServiceStatus()).thenReturn("ACTIVE");
        when(bucket.getPlanType()).thenReturn("DATA");
        when(bucket.getRecurringPeriod()).thenReturn("MONTHLY");
        when(bucket.getIsGroup()).thenReturn(true);
        when(bucket.getUsername()).thenReturn("999");
        when(bucket.getInitialBalance()).thenReturn(1000L);
        when(bucket.getUsage()).thenReturn(200L);
        when(bucket.getCurrentBalance()).thenReturn(800L);
        when(bucket.getDownLink()).thenReturn(String.valueOf(50L));
        when(bucket.getUpLink()).thenReturn("20");

        when(bucketInstanceRepository.findFlatBucketDetailsByUsernames(anyList()))
                .thenReturn(List.of(bucket));

        ServiceLineResponse response = userProvisioningService.getServiceDetailsByUsername(username);

        assertNotNull(response);
        assertEquals(username, response.getServiceLineNumber());

        assertEquals(1, response.getPlans().size());

        Plan plan = response.getPlans().get(0);
        assertEquals("Unlimited Plan", plan.getPlanName());
        assertEquals(1, plan.getPriority());
        assertEquals(1, plan.getStatus());
        assertEquals("DATA", plan.getPlanType());
        assertEquals("MONTHLY", plan.getRecurringMode());
        assertTrue(plan.getIsGroup());
        assertEquals("999", plan.getGroupId());

        assertEquals(1000L, plan.getQuota().getTotalQuota());
        assertEquals(200L, plan.getQuota().getUtilizedQuota());
        assertEquals(800L, plan.getQuota().getRemainingQuota());


        verify(bucketInstanceRepository)
                .findFlatBucketDetailsByUsernames(argThat(list ->
                        list.contains(username) && list.contains("999")
                ));
    }


    @Test
    void getServiceDetailsByUsername_userNotFound() {

        when(userRepository.findByUserName("unknown"))
                .thenReturn(Optional.empty());

        AAAException exception = assertThrows(
                AAAException.class,
                () -> userProvisioningService.getServiceDetailsByUsername("unknown")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void getServiceDetailsByUsername_internalServerError() {

        when(userRepository.findByUserName("errorUser"))
                .thenThrow(new RuntimeException("DB error"));

        AAAException exception = assertThrows(
                AAAException.class,
                () -> userProvisioningService.getServiceDetailsByUsername("errorUser")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertEquals("DB error", exception.getMessage());
    }
}

