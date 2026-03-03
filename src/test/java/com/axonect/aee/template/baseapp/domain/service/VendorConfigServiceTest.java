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
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendorConfigServiceTest {

    @Mock
    private VendorConfigRepository repository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @InjectMocks
    private VendorConfigService vendorConfigService;

    private VendorConfigRequest createRequest;
    private VendorConfigUpdateRequest updateRequest;
    private VendorConfig vendorConfig;
    private DBWriteRequestGeneric dbWriteEvent;
    private PublishResult successPublishResult;
    private PublishResult failurePublishResult;
    private PublishResult partialFailurePublishResult;

    @BeforeEach
    void setUp() {
        // Clear MDC before each test
        MDC.clear();

        // Setup create request
        createRequest = new VendorConfigRequest();
        createRequest.setVendorId("VENDOR001");
        createRequest.setVendorName("Test Vendor");
        createRequest.setAttributeName("TestAttribute");
        createRequest.setAttributeId("100");
        createRequest.setValuePath("/test/path");
        createRequest.setEntity("TestEntity");
        createRequest.setDataType("String");
        createRequest.setParameter("param1");
        createRequest.setIsActive(true);
        createRequest.setAttributePrefix("TEST");
        createRequest.setCreatedBy("testUser");

        // Setup update request
        updateRequest = new VendorConfigUpdateRequest();
        updateRequest.setId(1L);
        updateRequest.setVendorId("VENDOR001");
        updateRequest.setVendorName("Updated Vendor");
        updateRequest.setAttributeName("UpdatedAttribute");
        updateRequest.setAttributeId("200");
        updateRequest.setValuePath("/updated/path");
        updateRequest.setEntity("UpdatedEntity");
        updateRequest.setDataType("Integer");
        updateRequest.setParameter("param2");
        updateRequest.setIsActive(false);
        updateRequest.setAttributePrefix("UPD");
        updateRequest.setUpdatedBy("updateUser");

        // Setup vendor config entity
        vendorConfig = new VendorConfig();
        vendorConfig.setId(1L);
        vendorConfig.setVendorId("VENDOR001");
        vendorConfig.setVendorName("Test Vendor");
        vendorConfig.setAttributeName("TestAttribute");
        vendorConfig.setAttributeId("100");
        vendorConfig.setValuePath("/test/path");
        vendorConfig.setEntity("TestEntity");
        vendorConfig.setDataType("String");
        vendorConfig.setParameter("param1");
        vendorConfig.setIsActive(true);
        vendorConfig.setAttributePrefix("TEST");
        vendorConfig.setCreatedDate(LocalDateTime.now());
        vendorConfig.setCreatedBy("testUser");
        vendorConfig.setLastUpdatedDate(LocalDateTime.now());
        vendorConfig.setLastUpdatedBy("testUser");

        // Setup DB write event
        dbWriteEvent = DBWriteRequestGeneric.builder()
                .eventType("CREATE")
                .tableName("VENDOR_CONFIG")
                .userName("testUser")
                .build();

        // Setup publish results
        successPublishResult = PublishResult.builder()
                .dcSuccess(true)
                .drSuccess(false)
                .build();

        failurePublishResult = PublishResult.builder()
                .dcSuccess(false)
                .drSuccess(false)
                .build();

        partialFailurePublishResult = PublishResult.builder()
                .dcSuccess(false)
                .drSuccess(false)
                .build();

        // Inject EntityManager manually
        vendorConfigService.entityManager = entityManager;
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ==================== CREATE TESTS ====================

    @Test
    void create_Success() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId("VENDOR001", "100"))
                .thenReturn(false);

        when(repository.existsByVendorIdAndAttributeName("VENDOR001", "TestAttribute"))
                .thenReturn(false);

        when(entityManager.createNativeQuery(anyString()))
                .thenReturn(query);

        when(query.getSingleResult())
                .thenReturn(1L);

        when(eventMapper.toVendorConfigDBWriteEvent(
                eq("CREATE"),
                any(VendorConfig.class),
                eq("testUser")))
                .thenReturn(dbWriteEvent);

        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(
                any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        VendorConfigResponse response = vendorConfigService.create(createRequest);

        // Assert
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("VENDOR001", response.getVendorId());
        assertEquals("Test Vendor", response.getVendorName());
        assertEquals("TestAttribute", response.getAttributeName());
        assertEquals("100", response.getAttributeId());

        verify(repository).existsByVendorIdAndAttributeId("VENDOR001", "100");
        verify(repository).existsByVendorIdAndAttributeName("VENDOR001", "TestAttribute");
        verify(entityManager).createNativeQuery(anyString());
        verify(eventMapper).toVendorConfigDBWriteEvent(
                eq("CREATE"),
                any(VendorConfig.class),
                eq("testUser"));
        verify(kafkaEventPublisher)
                .publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    @Test
    void create_DuplicateAttributeName_ThrowsException() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(false);
        when(repository.existsByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(true);

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.create(createRequest);
        });

        assertEquals("VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_NAME_CODE", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("already exists"));

        verify(repository).existsByVendorIdAndAttributeName("VENDOR001", "TestAttribute");
    }

    @Test
    void create_SequenceGenerationFails_ThrowsException() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(false);
        when(repository.existsByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenThrow(new RuntimeException("Sequence error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.create(createRequest);
        });

        assertEquals("VENDOR_CONFIG_CREATION_ERROR_CODE", exception.getCode());
        assertTrue(exception.getMessage().contains("Failed to generate VendorConfig ID"));
    }

    @Test
    void create_KafkaPublishCompleteFailure_ThrowsException() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(false);
        when(repository.existsByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(failurePublishResult);

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.create(createRequest);
        });

        assertEquals("VENDOR_CONFIG_CREATION_ERROR_CODE", exception.getCode());
        assertTrue(exception.getMessage().contains("Failed to publish vendor config created events"));
    }

    @Test
    void create_KafkaPublishException_ThrowsException() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(false);
        when(repository.existsByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenThrow(new RuntimeException("Kafka error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.create(createRequest);
        });

        assertEquals("VENDOR_CONFIG_CREATION_ERROR_CODE", exception.getCode());
        assertTrue(exception.getMessage().contains("Failed to publish vendor config created events"));
    }

    @Test
    void create_PartialKafkaFailure_LogsWarning() {
        // Arrange
        PublishResult partialSuccess = PublishResult.builder()
                .dcSuccess(true)
                .drSuccess(false)
                .build();

        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(false);
        when(repository.existsByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(partialSuccess);

        // Act
        VendorConfigResponse response = vendorConfigService.create(createRequest);

        // Assert
        assertNotNull(response);
        verify(kafkaEventPublisher).publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    @Test
    void create_UnexpectedException_ThrowsWrappedException() {
        // Arrange
        when(repository.existsByVendorIdAndAttributeId(anyString(), anyString()))
                .thenThrow(new RuntimeException("Unexpected error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.create(createRequest);
        });

        assertEquals("VENDOR_CONFIG_CREATION_ERROR_CODE", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    // ==================== UPDATE TESTS ====================

    @Test
    void update_Success() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(repository.findByVendorIdAndAttributeId(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.findByVendorIdAndAttributeName(anyString(), anyString())).thenReturn(Optional.empty());
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        VendorConfigResponse response = vendorConfigService.update(updateRequest);

        // Assert
        assertNotNull(response);
        assertEquals("Updated Vendor", response.getVendorName());
        assertEquals("UpdatedAttribute", response.getAttributeName());
        assertEquals("200", response.getAttributeId());

        verify(repository).findById(1L);
        verify(eventMapper).toVendorConfigDBWriteEvent(eq("UPDATE"), any(VendorConfig.class), eq("updateUser"));
        verify(kafkaEventPublisher).publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    @Test
    void update_NotFound_ThrowsException() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.update(updateRequest);
        });

        assertEquals("VENDOR_CONFIG_NOT_FOUND_CODE", exception.getCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void update_VendorIdChanged_ThrowsException() {
        // Arrange
        updateRequest.setVendorId("VENDOR002");
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.update(updateRequest);
        });

        assertEquals("VENDOR_CONFIG_UPDATE_MISMATCH_CODE", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("cannot be changed"));
    }
    @Test
    void update_DuplicateAttributeName_ThrowsException() {
        // Arrange
        VendorConfig duplicateConfig = new VendorConfig();
        duplicateConfig.setId(2L);
        duplicateConfig.setVendorId("VENDOR001");
        duplicateConfig.setAttributeName("UpdatedAttribute");

        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(repository.findByVendorIdAndAttributeId("VENDOR001", "200")).thenReturn(Optional.empty());
        when(repository.findByVendorIdAndAttributeName("VENDOR001", "UpdatedAttribute"))
                .thenReturn(Optional.of(duplicateConfig));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.update(updateRequest);
        });

        assertEquals("VENDOR_CONFIG_DUPLICATE_ATTRIBUTE_NAME_CODE", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void update_SameAttributeIdAndName_Success() {
        // Arrange
        updateRequest.setAttributeId("100"); // Same as existing
        updateRequest.setAttributeName("TestAttribute"); // Same as existing

        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        VendorConfigResponse response = vendorConfigService.update(updateRequest);

        // Assert
        assertNotNull(response);
        verify(repository, never()).findByVendorIdAndAttributeId(anyString(), anyString());
        verify(repository, never()).findByVendorIdAndAttributeName(anyString(), anyString());
    }

    @Test
    void update_UnexpectedException_ThrowsWrappedException() {
        // Arrange
        when(repository.findById(1L)).thenThrow(new RuntimeException("Unexpected error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.update(updateRequest);
        });

        assertEquals("VENDOR_CONFIG_UPDATE_ERROR_CODE", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    // ==================== DELETE TESTS ====================

    @Test
    void delete_Success() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        String result = vendorConfigService.delete(1L);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("deleted successfully"));

        verify(repository).findById(1L);
        verify(eventMapper).toVendorConfigDBWriteEvent(eq("DELETE"), any(VendorConfig.class), anyString());
        verify(kafkaEventPublisher).publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    @Test
    void delete_NotFound_ThrowsException() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.delete(1L);
        });

        assertEquals("VENDOR_CONFIG_NOT_FOUND_CODE", exception.getCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void delete_UnexpectedException_ThrowsWrappedException() {
        // Arrange
        when(repository.findById(1L)).thenThrow(new RuntimeException("Unexpected error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.delete(1L);
        });

        assertEquals("VENDOR_CONFIG_DELETION_ERROR_CODE", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    // ==================== SEARCH TESTS ====================

    @Test
    void searchVendorConfig_Success() {
        // Arrange
        VendorConfigFilterRequest filterRequest = new VendorConfigFilterRequest();
        filterRequest.setPage(1);
        filterRequest.setSize(10);
        filterRequest.setSortBy("createdDate");
        filterRequest.setSortDirection("DESC");

        List<VendorConfig> configs = Arrays.asList(vendorConfig);
        Page<VendorConfig> page = new PageImpl<>(configs, PageRequest.of(0, 10), 1);

        MockedStatic<VendorConfigSpecification> mockedSpec = mockStatic(VendorConfigSpecification.class);
        Specification<VendorConfig> spec = mock(Specification.class);
        mockedSpec.when(() -> VendorConfigSpecification.filterVendorConfig(any())).thenReturn(spec);

        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        // Act
        PaginatedResponse<VendorConfigResponse> response = vendorConfigService.searchVendorConfig(filterRequest);

        // Assert
        assertNotNull(response);
        assertEquals(1, response.getVendorConfigData().size());
        assertEquals(1L, response.getPageDetails().getTotalRecords());
        assertEquals(1, response.getPageDetails().getTotalRecords());

        mockedSpec.close();
    }

    @Test
    void searchVendorConfig_WithNullSortBy_UsesDefaultSort() {
        // Arrange
        VendorConfigFilterRequest filterRequest = new VendorConfigFilterRequest();
        filterRequest.setPage(1);
        filterRequest.setSize(10);
        filterRequest.setSortBy(null);
        filterRequest.setSortDirection("ASC");

        List<VendorConfig> configs = Arrays.asList(vendorConfig);
        Page<VendorConfig> page = new PageImpl<>(configs, PageRequest.of(0, 10), 1);

        MockedStatic<VendorConfigSpecification> mockedSpec = mockStatic(VendorConfigSpecification.class);
        Specification<VendorConfig> spec = mock(Specification.class);
        mockedSpec.when(() -> VendorConfigSpecification.filterVendorConfig(any())).thenReturn(spec);

        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        // Act
        PaginatedResponse<VendorConfigResponse> response = vendorConfigService.searchVendorConfig(filterRequest);

        // Assert
        assertNotNull(response);
        mockedSpec.close();
    }

    @Test
    void searchVendorConfig_WithPageZero_ConvertsToZeroIndex() {
        // Arrange
        VendorConfigFilterRequest filterRequest = new VendorConfigFilterRequest();
        filterRequest.setPage(0);
        filterRequest.setSize(10);
        filterRequest.setSortBy("vendorId");
        filterRequest.setSortDirection("ASC");

        List<VendorConfig> configs = Arrays.asList(vendorConfig);
        Page<VendorConfig> page = new PageImpl<>(configs, PageRequest.of(0, 10), 1);

        MockedStatic<VendorConfigSpecification> mockedSpec = mockStatic(VendorConfigSpecification.class);
        Specification<VendorConfig> spec = mock(Specification.class);
        mockedSpec.when(() -> VendorConfigSpecification.filterVendorConfig(any())).thenReturn(spec);

        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        // Act
        PaginatedResponse<VendorConfigResponse> response = vendorConfigService.searchVendorConfig(filterRequest);

        // Assert
        assertNotNull(response);
        assertEquals(0, response.getPageDetails().getPageNumber());

        mockedSpec.close();
    }

    @Test
    void searchVendorConfig_Exception_ThrowsWrappedException() {
        // Arrange
        VendorConfigFilterRequest filterRequest = new VendorConfigFilterRequest();
        filterRequest.setPage(1);
        filterRequest.setSize(10);

        MockedStatic<VendorConfigSpecification> mockedSpec = mockStatic(VendorConfigSpecification.class);
        mockedSpec.when(() -> VendorConfigSpecification.filterVendorConfig(any()))
                .thenThrow(new RuntimeException("Search error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.searchVendorConfig(filterRequest);
        });

        assertEquals("VENDOR_CONFIG_SEARCH_ERROR", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());

        mockedSpec.close();
    }

    // ==================== GET BY ID TESTS ====================

    @Test
    void getById_Success() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));

        // Act
        VendorConfigResponse response = vendorConfigService.getById(1L);

        // Assert
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("VENDOR001", response.getVendorId());
        assertEquals("TestAttribute", response.getAttributeName());

        verify(repository).findById(1L);
    }

    @Test
    void getById_NotFound_ThrowsException() {
        // Arrange
        when(repository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.getById(1L);
        });

        assertEquals("VENDOR_CONFIG_NOT_FOUND_CODE", exception.getCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void getById_UnexpectedException_ThrowsWrappedException() {
        // Arrange
        when(repository.findById(1L)).thenThrow(new RuntimeException("Database error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.getById(1L);
        });

        assertEquals("VENDOR_CONFIG_FETCH_ERROR", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    // ==================== GET BY VENDOR ID TESTS ====================

    @Test
    void getByVendorId_Success() {
        // Arrange
        List<VendorConfig> configs = Arrays.asList(vendorConfig);
        when(repository.findByVendorId(eq("VENDOR001"), any(Sort.class))).thenReturn(configs);

        // Act
        List<VendorConfigResponse> responses = vendorConfigService.getByVendorId("VENDOR001");

        // Assert
        assertNotNull(responses);
        assertEquals(1, responses.size());
        assertEquals("VENDOR001", responses.get(0).getVendorId());

        verify(repository).findByVendorId(eq("VENDOR001"), any(Sort.class));
    }

    @Test
    void getByVendorId_NoResults_ThrowsException() {
        // Arrange
        when(repository.findByVendorId(eq("VENDOR001"), any(Sort.class))).thenReturn(Arrays.asList());

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.getByVendorId("VENDOR001");
        });

        assertEquals("VENDOR_CONFIG_NOT_FOUND_CODE", exception.getCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertTrue(exception.getMessage().contains("No vendor configs found"));
    }

    @Test
    void getByVendorId_UnexpectedException_ThrowsWrappedException() {
        // Arrange
        when(repository.findByVendorId(eq("VENDOR001"), any(Sort.class)))
                .thenThrow(new RuntimeException("Database error"));

        // Act & Assert
        AAAException exception = assertThrows(AAAException.class, () -> {
            vendorConfigService.getByVendorId("VENDOR001");
        });

        assertEquals("VENDOR_CONFIG_FETCH_ERROR", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    // ==================== ADDITIONAL EDGE CASE TESTS ====================

    @Test
    void update_SameIdInDuplicateCheck_Success() {
        // Arrange
        VendorConfig sameConfig = new VendorConfig();
        sameConfig.setId(1L); // Same ID as the one being updated
        sameConfig.setVendorId("VENDOR001");
        sameConfig.setAttributeId("200");

        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(repository.findByVendorIdAndAttributeId("VENDOR001", "200"))
                .thenReturn(Optional.of(sameConfig));
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        VendorConfigResponse response = vendorConfigService.update(updateRequest);

        // Assert
        assertNotNull(response);
    }

    @Test
    void delete_UsesLastUpdatedBy_WhenAvailable() {
        // Arrange
        vendorConfig.setLastUpdatedBy("lastUpdater");
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        String result = vendorConfigService.delete(1L);

        // Assert
        assertNotNull(result);
        verify(eventMapper).toVendorConfigDBWriteEvent(eq("DELETE"), any(VendorConfig.class), eq("lastUpdater"));
    }

    @Test
    void delete_UsesCreatedBy_WhenLastUpdatedByIsNull() {
        // Arrange
        vendorConfig.setLastUpdatedBy(null);
        vendorConfig.setCreatedBy("creator");
        when(repository.findById(1L)).thenReturn(Optional.of(vendorConfig));
        when(eventMapper.toVendorConfigDBWriteEvent(anyString(), any(VendorConfig.class), anyString()))
                .thenReturn(dbWriteEvent);
        when(kafkaEventPublisher.publishVendorConfigDBWriteEvent(any(DBWriteRequestGeneric.class)))
                .thenReturn(successPublishResult);

        // Act
        String result = vendorConfigService.delete(1L);

        // Assert
        assertNotNull(result);
        verify(eventMapper).toVendorConfigDBWriteEvent(eq("DELETE"), any(VendorConfig.class), eq("creator"));
    }
}