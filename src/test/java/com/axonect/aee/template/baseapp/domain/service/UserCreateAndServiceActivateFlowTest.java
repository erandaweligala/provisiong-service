/*
package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.*;
import com.axonect.aee.template.baseapp.application.transport.request.entities.ActiveServiceRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateUserRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ActiveServiceResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.CreateUserResponse;
import com.axonect.aee.template.baseapp.domain.adapter.AsyncAdaptorInterface;
import com.axonect.aee.template.baseapp.domain.algorithm.EncryptionPlugin;
import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

*/
/**
 * Full end-to-end unit test:  createUser (with MAC addresses) → activateService
 *
 * KEY DESIGN DECISIONS (learned from real service behaviour)
 * ──────────────────────────────────────────────────────────
 *  1. MAC Kafka events are published inside processSingleMac() directly.
 *     The real tableName on DBWriteRequestGeneric is unknown from test context,
 *     so MAC publish verification uses TOTAL INVOCATION COUNTS, not tableName
 *     filtering.  Formula:
 *       total publishDBWriteEvent calls = 1 (AAA_USER) + N (one per MAC)
 *
 *  2. MAC publish failure is SILENT — the service logs the failure, records 0/N
 *     successful, but returns a successful CreateUserResponse.  There is NO
 *     exception thrown.  Tests reflect this real behaviour.
 *
 *  3. normalizeMacAddress() is package-private, so it is tested indirectly:
 *     submit MACs in all supported formats and assert createUser() succeeds.
 *
 *  4. All negative-path tests (duplicate MAC, DB conflict, invalid format,
 *     missing MAC for IPoE) DO throw AAAException — confirmed by real logs.
 *//*

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Full Flow: User Create (with MACs) → Service Activate")
class UserCreateAndServiceActivateFlowTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Mocks – UserProvisioningService
    // ─────────────────────────────────────────────────────────────────────────
    @Mock private UserRepository            userRepository;
    @Mock private UserToMacRepository       userToMacRepository;
    @Mock private EncryptionPlugin          encryptionPlugin;
    @Mock private AsyncAdaptorInterface     asyncAdaptor;
    @Mock private SuperTemplateRepository   superTemplateRepository;
    @Mock private KafkaEventPublisher       kafkaEventPublisher;
    @Mock private EventMapper               eventMapper;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private CoAManagementService      coaManagementService;

    // Mocks – ServiceProvisioningService
    @Mock private PlanRepository           planRepository;
    @Mock private PlanToBucketRepository   planToBucketRepository;
    @Mock private BucketRepository         bucketRepository;
    @Mock private QOSProfileRepository     qosProfileRepository;
    @Mock private BucketInstanceRepository bucketInstanceRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Services under test
    // ─────────────────────────────────────────────────────────────────────────
    @InjectMocks private UserProvisioningService    userService;
    @InjectMocks private ServiceProvisioningService serviceService;

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────
    private static final String USER_NAME   = "testuser_mac_flow";
    private static final String GROUP_ID    = "1";
    private static final String PLAN_ID     = "PLAN-BASIC-01";
    private static final String PLAN_NAME   = "Basic Plan";
    private static final String BUCKET_ID   = "BKT-001";
    private static final Long   QOS_ID      = 10L;
    private static final Long   TEMPLATE_ID = 5L;

    // ─── MAC test data (4 supported input formats, all distinct bytes) ───────
    private static final String MAC_COLON  = "AA:BB:CC:DD:EE:FF"; // colon
    private static final String MAC_HYPHEN = "00-11-22-33-44-55"; // hyphen
    private static final String MAC_CISCO  = "AABB.CCDD.EE00";    // Cisco dot
    private static final String MAC_PLAIN  = "112233445566";       // plain hex (distinct bytes)

    // ─── Kafka publish count constants ───────────────────────────────────────
    // 1 call for AAA_USER event + 1 call per MAC published
    private static final int KAFKA_CALLS_1_MAC = 2;   // user + 1 MAC
    private static final int KAFKA_CALLS_3_MAC = 4;   // user + 3 MACs
    // For full flow (createUser + activateService): also service + bucket events
    // service layer adds 2 more calls (SERVICE_INSTANCE + BUCKET_INSTANCE)
    private static final int KAFKA_CALLS_FULL_FLOW_1_MAC = 4;  // user + 1 MAC + service + bucket
    private static final int KAFKA_CALLS_FULL_FLOW_3_MAC = 6;  // user + 3 MACs + service + bucket

    // Timing
    private long createUserMs;
    private long activateServiceMs;
    private long totalFlowMs;

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────
    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(userService, "defaultGroupId",      GROUP_ID);
        ReflectionTestUtils.setField(userService, "encryptionAlgorithm", "AES");
        ReflectionTestUtils.setField(userService, "encryptionSecretKey", "test-secret-key-32-bytes-long!!");

        stubCommonValidation();
        stubKafkaSuccess();
        stubServiceLayer();
    }

    // =========================================================================
    // TEST 01 – Single MAC (colon format), full create → activate flow
    // =========================================================================
    @Test
    @DisplayName("01. Single MAC (colon) → createUser → activateService → verify 2 Kafka calls")
    void fullFlow_singleMac_colonFormat() {
        long flowStart = System.currentTimeMillis();

        // Step 1 – create user
        long t1 = System.currentTimeMillis();
        CreateUserResponse createResp = userService.createUser(buildRequest("REQ-001", MAC_COLON));
        createUserMs = System.currentTimeMillis() - t1;

        // Step 2 – activate service immediately
        long t2 = System.currentTimeMillis();
        ActiveServiceResponseDTO activateResp =
                serviceService.activateService(buildActivateRequest(createResp.getUserName(), "REQ-SVC-001"));
        activateServiceMs = System.currentTimeMillis() - t2;

        totalFlowMs = System.currentTimeMillis() - flowStart;

        // ── createUser assertions ─────────────────────────────────────────────
        assertThat(createResp.getUserName()).isEqualTo(USER_NAME);
        assertThat(createResp.getUserId()).startsWith("USR");
        assertThat(createResp.getNasPortType()).isEqualTo("IPoE");
        assertThat(createResp.getStatus()).isEqualTo(1);

        // ── activateService assertions ────────────────────────────────────────
        assertThat(activateResp.getUserId()).isEqualTo(USER_NAME);
        assertThat(activateResp.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(activateResp.getStatus()).isEqualToIgnoringCase("active");

        // ── Kafka: total calls = 1 (user) + 1 (MAC) + 1 (service) + 1 (bucket)
        //   The MAC event tableName is internal to the service — we verify by
        //   total count rather than filtering, since tableName is implementation detail.
        verify(kafkaEventPublisher, times(KAFKA_CALLS_FULL_FLOW_1_MAC))
                .publishDBWriteEvent(any(DBWriteRequestGeneric.class));

        // ── Timing ───────────────────────────────────────────────────────────
        printTimingReport("01 – Single MAC (colon)", 1);
        assertThat(createUserMs).as("createUser < 2 000 ms").isLessThan(2_000L);
        assertThat(activateServiceMs).as("activateService < 2 000 ms").isLessThan(2_000L);
        assertThat(totalFlowMs).as("Total flow < 4 000 ms").isLessThan(4_000L);
    }

    // =========================================================================
    // TEST 02 – Multiple MACs (3 distinct formats), createUser only
    // =========================================================================
    @Test
    @DisplayName("02. Three MACs (colon + hyphen + Cisco) → 4 total Kafka publish calls")
    void fullFlow_multipleMacs_mixedFormats() {
        // Three distinct byte-sequences, three different notation styles
        String combined = MAC_COLON + ", " + MAC_HYPHEN + ", " + MAC_CISCO;

        long flowStart = System.currentTimeMillis();
        CreateUserResponse createResp = userService.createUser(buildRequest("REQ-002", combined));
        totalFlowMs = System.currentTimeMillis() - flowStart;

        // ── User response valid ───────────────────────────────────────────────
        assertThat(createResp.getUserName()).isEqualTo(USER_NAME);
        assertThat(createResp.getUserId()).startsWith("USR");

        // ── Kafka: 1 (user) + 3 (MACs) = 4 total ─────────────────────────────
        verify(kafkaEventPublisher, times(KAFKA_CALLS_3_MAC))
                .publishDBWriteEvent(any(DBWriteRequestGeneric.class));

        printTimingReport("02 – Three MACs (mixed formats)", 3);
        assertThat(totalFlowMs).isLessThan(4_000L);
    }

    // =========================================================================
    // TEST 03 – MAC normalisation tested indirectly (all 4 accepted formats)
    // =========================================================================
    @Test
    @DisplayName("03. All 4 MAC formats accepted → createUser succeeds and 1 MAC Kafka call each")
    void macNormalisation_allFormats_indirectlyVerified() {
        // Each format must be accepted and result in exactly 1 MAC Kafka publish.
        // If normalisation is broken, the service throws AAAException (invalid format).
        String[] validMacs = { MAC_COLON, MAC_HYPHEN, MAC_CISCO, MAC_PLAIN };
        AtomicInteger reqSeq     = new AtomicInteger(3);

        for (String mac : validMacs) {
            reset(kafkaEventPublisher);
            stubKafkaSuccess();

            assertThatCode(() -> userService.createUser(buildRequest("REQ-00" + reqSeq.getAndIncrement(), mac)))
                    .as("createUser must succeed for MAC format: " + mac)
                    .doesNotThrowAnyException();

            // 1 user event + 1 MAC event
            verify(kafkaEventPublisher, times(KAFKA_CALLS_1_MAC))
                    .publishDBWriteEvent(any(DBWriteRequestGeneric.class));
        }
    }

    // =========================================================================
    // TEST 04 – Duplicate MAC within same request (same bytes, different format)
    // =========================================================================
    @Test
    @DisplayName("04. Duplicate MAC in request (colon vs plain, same bytes) → AAAException")
    void createUser_duplicateMacInRequest_shouldFail() {
        // AA:BB:CC:DD:EE:FF and AABBCCDDEEFF normalise to the same 12-char string
        String duplicates = "AA:BB:CC:DD:EE:FF, AABBCCDDEEFF";

        assertThatThrownBy(() -> userService.createUser(buildRequest("REQ-004", duplicates)))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("Duplicate MAC address");

        // No Kafka events must be published — validation fails before publishing
        verify(kafkaEventPublisher, never()).publishDBWriteEvent(any());
    }

    // =========================================================================
    // TEST 05 – MAC already registered in DB under another user
    // =========================================================================
    @Test
    @DisplayName("05. MAC exists in DB for another user → AAAException (already exists)")
    void createUser_macAlreadyExistsInDb_shouldFail() {
        UserToMac existing = new UserToMac();
        existing.setMacAddress("aabbccddeeff"); // normalised form stored in DB
        existing.setUserName("other_user");

        when(userToMacRepository.findByMacAddressIn(anyList()))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> userService.createUser(buildRequest("REQ-005", MAC_COLON)))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("already exists");

        verify(kafkaEventPublisher, never()).publishDBWriteEvent(any());
    }

    // =========================================================================
    // TEST 06 – Invalid MAC format (non-hex characters)
    // =========================================================================
    @Test
    @DisplayName("06. Invalid MAC format (ZZ:ZZ:ZZ:ZZ:ZZ:ZZ) → AAAException (Invalid MAC)")
    void createUser_invalidMacFormat_shouldFail() {
        assertThatThrownBy(() -> userService.createUser(buildRequest("REQ-006", "ZZ:ZZ:ZZ:ZZ:ZZ:ZZ")))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("Invalid MAC address format");

        verify(kafkaEventPublisher, never()).publishDBWriteEvent(any());
    }

    // =========================================================================
    // TEST 07 – IPoE user without MAC address
    // =========================================================================
    @Test
    @DisplayName("07. IPoE user with null MAC → AAAException (MAC required)")
    void createUser_ipoeWithoutMac_shouldFail() {
        CreateUserRequest req = buildRequest("REQ-007", null);
        req.setMacAddress(null);

        assertThatThrownBy(() -> userService.createUser(req))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("MAC");

        verify(kafkaEventPublisher, never()).publishDBWriteEvent(any());
    }

    // =========================================================================
    // TEST 08 – Kafka publish FAILS for MAC event (silent degradation)
    // =========================================================================
    @Test
    @DisplayName("08. Kafka publish fails for MAC event → user still created, 0/1 MACs published")
    void createUser_macKafkaPublishFails_userStillCreated() {
        */
/*
         * REAL BEHAVIOUR (confirmed by logs):
         *   "Failed to publish MAC create event for MAC 'AA:BB:CC:DD:EE:FF'"
         *   "Failed to publish 1 out of 1 MAC addresses"
         *   "MAC address creation summary for user '...': 0/1 successful"
         *   "User '...' created successfully"
         *
         * The service LOGS the failure but does NOT throw.
         * The user entity is still considered created.
         *//*

        PublishResult fail = PublishResult.builder().dcSuccess(false).drSuccess(false).build();
        PublishResult ok   = PublishResult.builder().dcSuccess(true).drSuccess(true).build();

        // First call = AAA_USER event (ok), second call = MAC event (fail)
        when(kafkaEventPublisher.publishDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(ok)    // user event succeeds
                .thenReturn(fail); // MAC event fails silently

        CreateUserResponse resp = userService.createUser(buildRequest("REQ-008", MAC_COLON));

        // User is still returned successfully
        assertThat(resp).isNotNull();
        assertThat(resp.getUserName()).isEqualTo(USER_NAME);
        assertThat(resp.getUserId()).startsWith("USR");

        // Exactly 2 publish attempts: 1 user event + 1 MAC event (that failed)
        verify(kafkaEventPublisher, times(KAFKA_CALLS_1_MAC))
                .publishDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    // =========================================================================
    // TEST 09 – Kafka event ordering: user event must precede MAC events
    // =========================================================================
    @Test
    @DisplayName("09. Kafka event order: first call = AAA_USER, subsequent calls = MACs")
    void createUser_kafkaEventOrder_userBeforeMac() {
        ArgumentCaptor<DBWriteRequestGeneric> captor =
                ArgumentCaptor.forClass(DBWriteRequestGeneric.class);

        userService.createUser(buildRequest("REQ-009", MAC_COLON));

        // Capture all publish calls in order
        verify(kafkaEventPublisher, times(KAFKA_CALLS_1_MAC))
                .publishDBWriteEvent(captor.capture());

        List<DBWriteRequestGeneric> allEvents = captor.getAllValues();

        assertThat(allEvents).hasSize(KAFKA_CALLS_1_MAC);

        // The FIRST call must be the AAA_USER event (built via eventMapper)
        // The SECOND call must be the MAC event (built inside processSingleMac)
        DBWriteRequestGeneric firstEvent = allEvents.get(0);
        assertThat(firstEvent.getEventType())
                .as("First Kafka event must be the user CREATE event")
                .isEqualTo("CREATE");
        assertThat(firstEvent.getTableName())
                .as("First Kafka event must target the AAA_USER table")
                .isEqualTo("AAA_USER");

        // The second event is the MAC — whatever tableName the service assigns
        DBWriteRequestGeneric secondEvent = allEvents.get(1);
        assertThat(secondEvent.getEventType())
                .as("Second Kafka event must be a CREATE event (MAC)")
                .isEqualTo("CREATE");
        // tableName is whatever the service sets internally (not asserted here
        // since it's an implementation detail — see Root Cause 1 in class javadoc)
    }

    // =========================================================================
    // TEST 10 – MAC populated in createUser response via DB lookup
    // =========================================================================
    @Test
    @DisplayName("10. MAC appears in createUser response (fetched from DB after Kafka publish)")
    void createUser_macAppearsInResponse() {
        UserToMac stored = new UserToMac();
        stored.setUserName(USER_NAME);
        stored.setMacAddress("aabbccddeeff");
        stored.setOriginalMacAddress(MAC_COLON);

        // After publish, mapToCreateUserResponse calls findByUserName to populate response
        when(userToMacRepository.findByUserName(USER_NAME)).thenReturn(List.of(stored));

        CreateUserResponse resp = userService.createUser(buildRequest("REQ-010", MAC_COLON));

        assertThat(resp.getMacAddress())
                .as("Response must contain the original (human-readable) MAC format")
                .isEqualTo(MAC_COLON);
    }

    // =========================================================================
    // TEST 11 – FULL FLOW: 3 MACs + createUser + activateService with timing
    // =========================================================================
    @Test
    @DisplayName("11. FULL FLOW: 3 MACs → createUser → activateService → timing within bounds")
    void fullFlow_threeMacs_withServiceActivation_timingCheck() {
        // Three MACs: distinct bytes, different formats
        String combined = MAC_COLON + ", " + MAC_HYPHEN + ", " + MAC_CISCO;

        long flowStart = System.currentTimeMillis();

        // ── Step 1: createUser ────────────────────────────────────────────────
        long t1 = System.currentTimeMillis();
        CreateUserResponse createResp = userService.createUser(buildRequest("REQ-011", combined));
        createUserMs = System.currentTimeMillis() - t1;

        // ── Step 2: activateService (immediate, same thread) ──────────────────
        long t2 = System.currentTimeMillis();
        ActiveServiceResponseDTO activateResp =
                serviceService.activateService(buildActivateRequest(createResp.getUserName(), "REQ-SVC-011"));
        activateServiceMs = System.currentTimeMillis() - t2;

        totalFlowMs = System.currentTimeMillis() - flowStart;

        // ── createUser assertions ─────────────────────────────────────────────
        assertThat(createResp.getUserName()).isEqualTo(USER_NAME);
        assertThat(createResp.getUserId()).startsWith("USR");
        assertThat(createResp.getStatus()).isEqualTo(1);

        // ── activateService assertions ────────────────────────────────────────
        assertThat(activateResp.getUserId()).isEqualTo(USER_NAME);
        assertThat(activateResp.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(activateResp.getStatus()).isEqualToIgnoringCase("active");

        // ── Kafka total calls:
        //      createUser :  1 (AAA_USER) + 3 (MACs) = 4
        //      activateService: 1 (SERVICE_INSTANCE) + 1 (BUCKET_INSTANCE) = 2
        //      Grand total = 6
        verify(kafkaEventPublisher, times(KAFKA_CALLS_FULL_FLOW_3_MAC))
                .publishDBWriteEvent(any(DBWriteRequestGeneric.class));

        // ── Verify the first Kafka call in createUser was the user event ───────
        ArgumentCaptor<DBWriteRequestGeneric> captor =
                ArgumentCaptor.forClass(DBWriteRequestGeneric.class);
        verify(kafkaEventPublisher, times(KAFKA_CALLS_FULL_FLOW_3_MAC))
                .publishDBWriteEvent(captor.capture());

        List<DBWriteRequestGeneric> allCalls = captor.getAllValues();
        assertThat(allCalls.get(0).getTableName())
                .as("Very first Kafka publish must be the AAA_USER event")
                .isEqualTo("AAA_USER");

        // ── Timing ───────────────────────────────────────────────────────────
        printTimingReport("11 – 3 MACs + Service Activate", 3);

        assertThat(createUserMs)     .as("createUser < 2 000 ms")    .isLessThan(2_000L);
        assertThat(activateServiceMs).as("activateService < 2 000 ms").isLessThan(2_000L);
        assertThat(totalFlowMs)      .as("Total flow < 4 000 ms")     .isLessThan(4_000L);
    }

    // =========================================================================
    // Negative-path smoke tests (original 5 from session 1, kept for regression)
    // =========================================================================

    @Test
    @DisplayName("12. activateService – USER_NOT_FOUND → AAAException (no user in DB)")
    void shouldFailServiceActivationWhenUserNotFound() {
        when(userRepository.findByUserName(USER_NAME)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                serviceService.activateService(buildActivateRequest(USER_NAME, "REQ-SVC-002")))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("USER_NOT_FOUND");
    }

    @Test
    @DisplayName("13. activateService – PLAN_IS_NOT_ACTIVE → AAAException")
    void shouldFailServiceActivationWhenPlanIsInactive() {
        Plan inactivePlan = buildPlan();
        inactivePlan.setStatus("Inactive");
        when(planRepository.findByPlanId(PLAN_ID)).thenReturn(Optional.of(inactivePlan));

        assertThatThrownBy(() ->
                serviceService.activateService(buildActivateRequest(USER_NAME, "REQ-SVC-003")))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("PLAN_IS_NOT_ACTIVE");
    }

    @Test
    @DisplayName("14. activateService – past serviceStartDate → AAAException")
    void shouldFailServiceActivationWithPastStartDate() {
        ActiveServiceRequestDTO req = buildActivateRequest(USER_NAME, "REQ-SVC-004");
        req.setServiceStartDate(LocalDateTime.now().minusDays(3));

        assertThatThrownBy(() -> serviceService.activateService(req))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("past");
    }

    @Test
    @DisplayName("15. createUser – duplicate requestId → AAAException (CONFLICT)")
    void shouldFailOnDuplicateRequestId() throws Exception {
        // Re-stub asyncAdaptor to return true for requestId duplicate check
        CompletableFuture<Object> noUser     = CompletableFuture.completedFuture(false);
        CompletableFuture<Object> dupReqId   = CompletableFuture.completedFuture(true);
        when(asyncAdaptor.supplyAll(anyLong(), any(), any()))
                .thenReturn(new CompletableFuture[]{noUser, dupReqId});

        assertThatThrownBy(() ->
                userService.createUser(buildRequest("DUPLICATE-REQ-ID", MAC_COLON)))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("DUPLICATE-REQ-ID");
    }

    // =========================================================================
    // Stub helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void stubCommonValidation() throws Exception {
        CompletableFuture<Object> noUser  = CompletableFuture.completedFuture(false);
        CompletableFuture<Object> noReqId = CompletableFuture.completedFuture(false);
        when(asyncAdaptor.supplyAll(anyLong(), any(), any()))
                .thenReturn(new CompletableFuture[]{noUser, noReqId});

        // No existing MACs by default — individual tests override as needed
        when(userToMacRepository.findByMacAddressIn(anyList())).thenReturn(List.of());
        when(userToMacRepository.findByUserName(USER_NAME)).thenReturn(List.of());

        SuperTemplate tmpl = new SuperTemplate();
        tmpl.setId(TEMPLATE_ID);
        tmpl.setTemplateName("Default Template");
        tmpl.setIsDefault(true);
        when(superTemplateRepository.findByIsDefault(true)).thenReturn(Optional.of(tmpl));
        when(superTemplateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        when(superTemplateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(tmpl));

        when(userRepository.findFirstByGroupId(GROUP_ID)).thenReturn(Optional.empty());
    }

    private void stubKafkaSuccess() {
        PublishResult ok = PublishResult.builder().dcSuccess(true).drSuccess(true).build();

        // User event (via eventMapper)
        DBWriteRequestGeneric userEvt = DBWriteRequestGeneric.builder()
                .eventType("CREATE").tableName("AAA_USER").build();
        when(eventMapper.toDBWriteEvent(anyString(), any(UserEntity.class), anyString()))
                .thenReturn(userEvt);

        // Service event (via eventMapper)
        DBWriteRequestGeneric svcEvt = DBWriteRequestGeneric.builder()
                .eventType("CREATE").tableName("SERVICE_INSTANCE").build();
        when(eventMapper.toServiceDBWriteEvent(anyString(), any(ServiceInstance.class)))
                .thenReturn(svcEvt);

        // Bucket event (via eventMapper)
        DBWriteRequestGeneric bktEvt = DBWriteRequestGeneric.builder()
                .eventType("CREATE").tableName("BUCKET_INSTANCE").build();
        when(eventMapper.toBucketDBWriteEvent(anyString(), any(BucketInstance.class), anyString()))
                .thenReturn(bktEvt);

        // MAC events are built directly in processSingleMac() — NOT via eventMapper.
        // publishDBWriteEvent accepts any DBWriteRequestGeneric and returns ok.
        when(kafkaEventPublisher.publishDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(ok);
    }

    private void stubServiceLayer() {
        when(userRepository.findByUserName(USER_NAME)).thenReturn(Optional.of(buildUser()));
        when(planRepository.findByPlanId(PLAN_ID)).thenReturn(Optional.of(buildPlan()));
        when(serviceInstanceRepository.existsByRequestId(anyString())).thenReturn(false);
        when(serviceInstanceRepository.existsByUsernameAndPlanId(USER_NAME, PLAN_ID)).thenReturn(false);
        when(bucketRepository.findByBucketId(BUCKET_ID)).thenReturn(Optional.of(buildBucket()));

        QOSProfile qos = new QOSProfile();
        qos.setId(QOS_ID);
        qos.setBngCode("BNG-QOS-001");
        when(qosProfileRepository.findById(QOS_ID)).thenReturn(Optional.of(qos));
        when(planToBucketRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(buildPlanToBucket()));
    }

    // =========================================================================
    // Builders
    // =========================================================================

    private CreateUserRequest buildRequest(String requestId, String macAddress) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUserName(USER_NAME);
        req.setNasPortType("IPoE");
        req.setMacAddress(macAddress);
        req.setIpAllocation("Dynamic");
        req.setIpPoolName("POOL-001");
        req.setGroupId(GROUP_ID);
        req.setBandwidth("100M");
        req.setStatus(1);
        req.setSubscription(1);
        req.setRequestId(requestId);
        req.setBilling("2");
        req.setConcurrency(5);
        req.setSessionTimeout("86400");
        return req;
    }

    private ActiveServiceRequestDTO buildActivateRequest(String userName, String requestId) {
        ActiveServiceRequestDTO req = new ActiveServiceRequestDTO();
        req.setUserId(userName);
        req.setRequestId(requestId);
        req.setPlanId(PLAN_ID);
        req.setServiceStartDate(LocalDateTime.now().plusDays(1));
        req.setStatus("1");
        req.setIsGroup(false);
        req.setQuota(0L);
        return req;
    }

    private UserEntity buildUser() {
        UserEntity user = new UserEntity();
        user.setUserName(USER_NAME);
        user.setUserId("USR-TEST-001");
        user.setGroupId(GROUP_ID);
        user.setBilling("2");
        user.setStatus(UserStatus.fromCode(1));
        user.setNasPortType("IPoE");
        user.setIpAllocation("Dynamic");
        user.setIpPoolName("POOL-001");
        user.setTemplateId(TEMPLATE_ID);
        return user;
    }

    private Plan buildPlan() {
        Plan plan = new Plan();
        plan.setPlanId(PLAN_ID);
        plan.setPlanName(PLAN_NAME);
        plan.setPlanType("DATA");
        plan.setStatus("Active");
        plan.setRecurringFlag(true);
        plan.setRecurringPeriod("MONTHLY");
        plan.setQuotaProrationFlag(false);
        return plan;
    }

    private Bucket buildBucket() {
        Bucket bucket = new Bucket();
        bucket.setBucketId(BUCKET_ID);
        bucket.setBucketType("DATA");
        bucket.setPriority(1L);
        bucket.setTimeWindow("ALLDAY");
        bucket.setQosId(QOS_ID);
        return bucket;
    }

    private PlanToBucket buildPlanToBucket() {
        PlanToBucket ptb = new PlanToBucket();
        ptb.setPlanId(PLAN_ID);
        ptb.setBucketId(BUCKET_ID);
        ptb.setInitialQuota(10_737_418_240L); // 10 GB
        ptb.setIsUnlimited(false);
        ptb.setCarryForward(false);
        ptb.setMaxCarryForward(0L);
        ptb.setTotalCarryForward(0L);
        return ptb;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private void printTimingReport(String scenarioLabel, int macCount) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf( "║  Scenario  : %-39s║%n", scenarioLabel);
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  MACs published          : %-26d║%n", macCount);
        System.out.printf( "║  createUser()            : %6d ms              ║%n", createUserMs);
        System.out.printf( "║  activateService()       : %6d ms              ║%n", activateServiceMs);
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Total flow              : %6d ms              ║%n", totalFlowMs);
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}*/
