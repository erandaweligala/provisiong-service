package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.BngRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.*;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import com.axonect.aee.template.baseapp.domain.events.BngEvent;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BngServiceTest {

    @Mock
    private BngRepository bngRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private BngService bngService;

    private BngCreateRequest createRequest;
    private BngUpdateRequest updateRequest;
    private BngEntity mockEntity;
    private BngEvent mockBngEvent;
    private DBWriteRequestGeneric mockDbEvent;
    private PublishResult successfulPublishResult;
    private PublishResult failedPublishResult;

    // VAPT FIX: AAA-WEB-2026-06 - These come from request headers, not user input
    private String createdBy;
    private String updatedBy;

    @BeforeEach
    void setup() {
        createdBy = "admin";
        updatedBy = "admin";

        createRequest = new BngCreateRequest();
        createRequest.setBngId("BNG001");
        createRequest.setBngName("TEST_BNG");
        createRequest.setBngIp("10.10.10.1");
        createRequest.setBngTypeVendor("Huawei");
        createRequest.setModelVersion("V1");
        createRequest.setNasIpAddress("10.10.10.2");
        createRequest.setNasIdentifier("NAS1");
        createRequest.setCoaIp("10.10.10.3");
        createRequest.setCoaPort(3799);
        createRequest.setSharedSecret("secret");
        createRequest.setLocation("Colombo");
        createRequest.setStatus("Active");
        // REMOVED: createRequest.setCreatedBy("admin");

        updateRequest = new BngUpdateRequest();
        updateRequest.setBngIp("20.20.20.1");
        updateRequest.setBngTypeVendor("ZTE");
        updateRequest.setModelVersion("V2");
        updateRequest.setNasIpAddress("20.20.20.2");
        updateRequest.setNasIdentifier("NAS2");
        updateRequest.setCoaIp("20.20.20.3");
        updateRequest.setCoaPort(4800);
        updateRequest.setSharedSecret("updated");
        updateRequest.setLocation("Kandy");
        updateRequest.setStatus("Inactive");
        // REMOVED: updateRequest.setUpdatedBy("admin");

        mockEntity = BngEntity.builder()
                .bngId("BNG001")
                .bngName("TEST_BNG")
                .bngIp("10.10.10.1")
                .bngTypeVendor("Huawei")
                .modelVersion("V1")
                .nasIpAddress("10.10.10.2")
                .nasIdentifier("NAS1")
                .coaIp("10.10.10.3")
                .coaPort(3799)
                .sharedSecret("secret")
                .location("Colombo")
                .status("Active")
                .createdBy("admin")
                .createdDate(LocalDateTime.now())
                .build();

        mockBngEvent = new BngEvent();
        mockDbEvent = new DBWriteRequestGeneric();

        successfulPublishResult = PublishResult.builder()
                .dcSuccess(true)
                .drSuccess(false)
                .build();

        failedPublishResult = PublishResult.builder()
                .dcSuccess(false)
                .drSuccess(false)
                .build();
    }

    // ==================== CREATE BNG TESTS ====================

    @Test
    @DisplayName("Test 1: Create BNG - Success")
    void testCreateBng_Success() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);
        when(eventMapper.toBngDBWriteEvent(eq("CREATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        CreateBngResponse response = bngService.createBng(createRequest, createdBy);

        assertThat(response).isNotNull();
        assertThat(response.getBngId()).isEqualTo("BNG001");
        assertThat(response.getBngName()).isEqualTo("TEST_BNG");
        assertThat(response.getCreatedBy()).isEqualTo("admin");
        verify(kafkaEventPublisher, times(1)).publishBngDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    @Test
    @DisplayName("Test 2: Create BNG - Duplicate ID")
    void testCreateBng_DuplicateId() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(true);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("already exists");

        verify(kafkaEventPublisher, never()).publishBngEvent(anyString(), any());
    }

    @Test
    @DisplayName("Test 3: Create BNG - Duplicate Name")
    void testCreateBng_DuplicateName() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(true);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Test 4: Create BNG - Invalid Status")
    void testCreateBng_InvalidStatus() {
        createRequest.setStatus("WrongStatus");
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class);
    }

    @Test
    @DisplayName("Test 5: Create BNG - Null Status")
    void testCreateBng_NullStatus() {
        createRequest.setStatus(null);
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class);
    }

    @Test
    @DisplayName("Test 6: Create BNG - Blank Status")
    void testCreateBng_BlankStatus() {
        createRequest.setStatus("   ");
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class);
    }

    @Test
    @DisplayName("Test 7: Create BNG - Kafka Event Publish Failure")
    void testCreateBng_KafkaEventPublishFailure() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);
        when(eventMapper.toBngDBWriteEvent(eq("CREATE"), any(BngEntity.class))).thenReturn(mockDbEvent);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("Failed to publish BNG created events");
    }

    @Test
    @DisplayName("Test 8: Create BNG - Kafka DB Write Event Publish Failure")
    void testCreateBng_KafkaDbWritePublishFailure() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);
        when(eventMapper.toBngDBWriteEvent(eq("CREATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(failedPublishResult);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("Failed to publish BNG created events");
    }

    @Test
    @DisplayName("Test 9: Create BNG - Kafka Exception")
    void testCreateBng_KafkaException() {
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);

        assertThatThrownBy(() -> bngService.createBng(createRequest, createdBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("Failed to publish BNG created events");
    }

    @Test
    @DisplayName("Test 10: Create BNG - Status Case Insensitive Active")
    void testCreateBng_StatusCaseInsensitiveActive() {
        createRequest.setStatus("active");
        when(bngRepository.existsByBngId("BNG001")).thenReturn(false);
        when(bngRepository.existsByBngName("TEST_BNG")).thenReturn(false);
        when(eventMapper.toBngDBWriteEvent(eq("CREATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        CreateBngResponse response = bngService.createBng(createRequest, createdBy);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("active");
    }

    // ==================== UPDATE BNG TESTS ====================

    @Test
    @DisplayName("Test 11: Update BNG - Success")
    void testUpdateBng_Success() {
        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));
        when(eventMapper.toBngDBWriteEvent(eq("UPDATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        UpdateBngResponse response = bngService.updateBng("BNG001", updateRequest, updatedBy);

        assertThat(response).isNotNull();
        assertThat(response.getBngId()).isEqualTo("BNG001");
        assertThat(response.getBngIp()).isEqualTo("20.20.20.1");
        assertThat(response.getLocation()).isEqualTo("Kandy");
        assertThat(response.getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Test 12: Update BNG - Not Found")
    void testUpdateBng_NotFound() {
        when(bngRepository.findById("BNG999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bngService.updateBng("BNG999", updateRequest, updatedBy))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Test 13: Update BNG - Invalid Status")
    void testUpdateBng_InvalidStatus() {
        updateRequest.setStatus("stupid");
        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));

        assertThatThrownBy(() -> bngService.updateBng("BNG001", updateRequest, updatedBy))
                .isInstanceOf(AAAException.class);
    }

    @Test
    @DisplayName("Test 14: Update BNG - Partial Update (Only IP)")
    void testUpdateBng_PartialUpdateOnlyIp() {
        BngUpdateRequest partialRequest = new BngUpdateRequest();
        partialRequest.setBngIp("30.30.30.1");
        // REMOVED: partialRequest.setUpdatedBy("admin");

        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));
        when(eventMapper.toBngDBWriteEvent(eq("UPDATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        UpdateBngResponse response = bngService.updateBng("BNG001", partialRequest, updatedBy);

        assertThat(response.getBngIp()).isEqualTo("30.30.30.1");
        assertThat(response.getBngTypeVendor()).isEqualTo("Huawei");
    }

    @Test
    @DisplayName("Test 15: Update BNG - Null Values Ignored")
    void testUpdateBng_NullValuesIgnored() {
        BngUpdateRequest nullRequest = new BngUpdateRequest();
        // REMOVED: nullRequest.setUpdatedBy("admin");

        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));
        when(eventMapper.toBngDBWriteEvent(eq("UPDATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        UpdateBngResponse response = bngService.updateBng("BNG001", nullRequest, updatedBy);

        assertThat(response.getBngIp()).isEqualTo("10.10.10.1");
        assertThat(response.getLocation()).isEqualTo("Colombo");
    }

    @Test
    @DisplayName("Test 16: Update BNG - Blank Values Ignored")
    void testUpdateBng_BlankValuesIgnored() {
        BngUpdateRequest blankRequest = new BngUpdateRequest();
        blankRequest.setBngIp("   ");
        blankRequest.setLocation("");
        // REMOVED: blankRequest.setUpdatedBy("admin");

        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));
        when(eventMapper.toBngDBWriteEvent(eq("UPDATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        UpdateBngResponse response = bngService.updateBng("BNG001", blankRequest, updatedBy);

        assertThat(response.getBngIp()).isEqualTo("10.10.10.1");
        assertThat(response.getLocation()).isEqualTo("Colombo");
    }

    @Test
    @DisplayName("Test 18: Update BNG - Update CoaPort")
    void testUpdateBng_UpdateCoaPort() {
        BngUpdateRequest portRequest = new BngUpdateRequest();
        portRequest.setCoaPort(5000);
        // REMOVED: portRequest.setUpdatedBy("admin");

        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));
        when(eventMapper.toBngDBWriteEvent(eq("UPDATE"), any(BngEntity.class))).thenReturn(mockDbEvent);
        when(kafkaEventPublisher.publishBngDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successfulPublishResult);

        UpdateBngResponse response = bngService.updateBng("BNG001", portRequest, updatedBy);

        assertThat(response.getCoaPort()).isEqualTo(5000);
    }

    // ==================== SEARCH, GET BY ID/NAME, GET LIST TESTS ====================
    // Tests 19-30 remain unchanged — no createdBy/updatedBy involved

    @Test
    @DisplayName("Test 19: Search BNG - Success")
    void testSearchBng_Success() {
        BngFilterRequest filter = new BngFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("bngName");
        filter.setSortDirection("ASC");

        Page<BngEntity> page = new PageImpl<>(List.of(mockEntity));

        when(bngRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        PaginatedResponse<BngFilterResponse> response = bngService.searchBng(filter);

        assertThat(response.getBngData()).hasSize(1);
        assertThat(response.getPageDetails().getTotalRecords()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test 20: Search BNG - Empty Results")
    void testSearchBng_EmptyResults() {
        BngFilterRequest filter = new BngFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("bngName");
        filter.setSortDirection("ASC");

        Page<BngEntity> emptyPage = new PageImpl<>(Collections.emptyList());

        when(bngRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        PaginatedResponse<BngFilterResponse> response = bngService.searchBng(filter);

        assertThat(response.getBngData()).isEmpty();
        assertThat(response.getPageDetails().getTotalRecords()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test 21: Search BNG - Exception")
    void testSearchBng_Exception() {
        BngFilterRequest filter = new BngFilterRequest();
        filter.setPage(1);
        filter.setSize(10);

        when(bngRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> bngService.searchBng(filter))
                .isInstanceOf(AAAException.class);
    }

    @Test
    @DisplayName("Test 22: Search BNG - Invalid Sort Field Defaults to createdDate")
    void testSearchBng_InvalidSortFieldDefaultsToCreatedDate() {
        BngFilterRequest filter = new BngFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("invalidField");
        filter.setSortDirection("ASC");

        Page<BngEntity> page = new PageImpl<>(List.of(mockEntity));

        when(bngRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        PaginatedResponse<BngFilterResponse> response = bngService.searchBng(filter);

        assertThat(response).isNotNull();
        verify(bngRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Test 23: Search BNG - DESC Sort Direction")
    void testSearchBng_DescSortDirection() {
        BngFilterRequest filter = new BngFilterRequest();
        filter.setPage(1);
        filter.setSize(10);
        filter.setSortBy("bngId");
        filter.setSortDirection("DESC");

        Page<BngEntity> page = new PageImpl<>(List.of(mockEntity));

        when(bngRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        PaginatedResponse<BngFilterResponse> response = bngService.searchBng(filter);

        assertThat(response.getBngData()).hasSize(1);
    }

    @Test
    @DisplayName("Test 24: Get BNG By ID - Success")
    void testGetBngById_Success() {
        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));

        GetBngResponse response = bngService.getBngById("BNG001");

        assertThat(response.getBngId()).isEqualTo("BNG001");
        assertThat(response.getBngName()).isEqualTo("TEST_BNG");
    }

    @Test
    @DisplayName("Test 25: Get BNG By ID - Not Found")
    void testGetBngById_NotFound() {
        when(bngRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bngService.getBngById("BNG404"))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Test 26: Get BNG By Name - Success")
    void testGetBngByName_Success() {
        when(bngRepository.findByBngName("TEST_BNG"))
                .thenReturn(Optional.of(mockEntity));

        GetBngResponse response = bngService.getBngByName("TEST_BNG");

        assertThat(response.getBngName()).isEqualTo("TEST_BNG");
        assertThat(response.getBngId()).isEqualTo("BNG001");
    }

    @Test
    @DisplayName("Test 27: Get BNG By Name - Not Found")
    void testGetBngByName_NotFound() {
        when(bngRepository.findByBngName(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bngService.getBngByName("Unknown"))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Test 28: Get BNG By ID and Name - Success")
    void testGetBngByIdAndName_Success() {
        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));

        GetBngResponse response = bngService.getBngByIdAndName("BNG001", "TEST_BNG");

        assertThat(response.getBngId()).isEqualTo("BNG001");
        assertThat(response.getBngName()).isEqualTo("TEST_BNG");
    }

    @Test
    @DisplayName("Test 29: Get BNG By ID and Name - Name Mismatch")
    void testGetBngByIdAndName_NameMismatch() {
        when(bngRepository.findById("BNG001")).thenReturn(Optional.of(mockEntity));

        assertThatThrownBy(() -> bngService.getBngByIdAndName("BNG001", "WRONG_NAME"))
                .isInstanceOf(AAAException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Test 30: Get BNG List - Success")
    void testGetBngList_Success() {
        BngEntity entity2 = BngEntity.builder()
                .bngId("BNG002")
                .bngName("BNG_TWO")
                .bngIp("20.20.20.1")
                .status("Active")
                .build();

        List<BngEntity> entities = Arrays.asList(mockEntity, entity2);

        when(bngRepository.findAll(any(Sort.class))).thenReturn(entities);

        List<BngListResponse> response = bngService.getBngList();

        assertThat(response).hasSize(2);
        assertThat(response.get(0).getBngName()).isEqualTo("TEST_BNG");
        assertThat(response.get(0).getBngIp()).isEqualTo("10.10.10.1");
        assertThat(response.get(1).getBngName()).isEqualTo("BNG_TWO");
        assertThat(response.get(1).getBngIp()).isEqualTo("20.20.20.1");
    }
}