package com.axonect.aee.template.baseapp.domain.service;


import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.BaseResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.BucketInfo;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.BucketInfoProjection;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfo;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfoProjection;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.mappers.ServiceInfoMapper;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BucketInfoServiceTest {

    @InjectMocks
    private BucketInfoService bucketInfoService;

    @Mock
    private BucketInstanceRepository bucketInstanceRepository;

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ServiceInfoMapper mapper;

    @Mock
    private ServiceInfoProjection serviceInfoProjection;

    @Mock
    private BucketInfoProjection bucketInfoProjection;

    @BeforeEach
    void setup() {
    }

    // ---------------------------------------------------------------------
    // getServiceInfo Tests
    // ---------------------------------------------------------------------

    @Test
    void testGetServiceInfo_Success_WithGroupId() throws AAAException {
        // Arrange
        String username = "user1";
        String groupId = "groupA";

        when(userRepository.findGroupIdByUsername(username)).thenReturn(groupId);

        Page<ServiceInfoProjection> page = new PageImpl<>(List.of(serviceInfoProjection));
        when(serviceInstanceRepository.searchServiceInfo(
                anyList(), any(), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(page);

        ServiceInfo mappedResult = new ServiceInfo();
        when(mapper.mapIntoServiceInfo(any())).thenReturn(mappedResult);

        // Act
        BaseResponse<List<ServiceInfo>> response = bucketInfoService.getServiceInfo(
                username, null, null, null, null, null, true, 1, 10
        );

        // Assert
        assertNotNull(response);
        assertEquals(1, response.getData().size());
        verify(userRepository).findGroupIdByUsername(username);
        verify(mapper).mapIntoServiceInfo(any());
    }

    @Test
    void testGetServiceInfo_NoContent_ThrowsAAAException() {
        when(userRepository.findGroupIdByUsername("user1")).thenReturn("groupA");

        Page<ServiceInfoProjection> emptyPage = Page.empty();
        when(serviceInstanceRepository.searchServiceInfo(anyList(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(emptyPage);

        // Assert
        AAAException ex = assertThrows(AAAException.class, () ->
                bucketInfoService.getServiceInfo("user1", "SVC001",
                        null, null, null, null, true, 1, 10));

        assertEquals(LogMessages.ERROR_NOT_FOUND, ex.getCode());
    }

    @Test
    void testGetServiceInfo_UnexpectedException_ThrowsInternalError() {
        when(userRepository.findGroupIdByUsername("user1"))
                .thenThrow(new RuntimeException("DB crash"));

        AAAException ex = assertThrows(AAAException.class, () ->
                bucketInfoService.getServiceInfo("user1",
                        null, null, null, null, null, true, 1, 10));

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, ex.getCode());
    }

    // ---------------------------------------------------------------------
    // getBucketInfoByServiceId Tests
    // ---------------------------------------------------------------------

    @Test
    void testGetBucketInfoByServiceId_Success() throws AAAException {
        when(bucketInfoProjection.getBucketId()).thenReturn("BKT1");
        when(bucketInfoProjection.getInitialBalance()).thenReturn(100L);
        when(bucketInfoProjection.getUsage()).thenReturn((long) 20.0);
        when(bucketInfoProjection.getCurrentBalance()).thenReturn((long) 80.0);
        when(bucketInfoProjection.getPriority()).thenReturn(1L);
        when(bucketInfoProjection.getUplinkSpeed()).thenReturn(String.valueOf(5.0));
        when(bucketInfoProjection.getDownlinkSpeed()).thenReturn(String.valueOf(10.0));

        when(bucketInstanceRepository.findBucketInfoWithSpeed(1L))
                .thenReturn(List.of(bucketInfoProjection));

        BaseResponse<List<BucketInfo>> response =
                bucketInfoService.getBucketInfoByServiceId(1L);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals("BKT1", response.getData().getFirst().getBucketId());
    }

    @Test
    void testGetBucketInfoByServiceId_NoResults_ThrowsAAAException() {
        when(bucketInstanceRepository.findBucketInfoWithSpeed(1L))
                .thenReturn(Collections.emptyList());

        AAAException ex = assertThrows(AAAException.class, () ->
                bucketInfoService.getBucketInfoByServiceId(1L));

        assertEquals(LogMessages.ERROR_NOT_FOUND, ex.getCode());
    }

    @Test
    void testGetBucketInfoByServiceId_UnexpectedException_ThrowsInternalError() {
        when(bucketInstanceRepository.findBucketInfoWithSpeed(1L))
                .thenThrow(new RuntimeException("Connection lost"));

        AAAException ex = assertThrows(AAAException.class, () ->
                bucketInfoService.getBucketInfoByServiceId(1L));

        assertEquals(LogMessages.ERROR_INTERNAL_ERROR, ex.getCode());
    }
}
