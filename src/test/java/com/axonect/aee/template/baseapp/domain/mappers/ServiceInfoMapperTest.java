package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfo;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfoProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceInfoMapperTest {

    private ServiceInfoMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new ServiceInfoMapper();
    }

    @Test
    void testMapIntoServiceInfo_AllFieldsMapped_Correctly() {
        // Arrange mock projection
        ServiceInfoProjection p = mock(ServiceInfoProjection.class);

        when(p.getServiceId()).thenReturn("SVC123");
        when(p.getUsername()).thenReturn("userA");
        when(p.getStatus()).thenReturn("ACTIVE");
        when(p.getPlanId()).thenReturn("PLAN01");
        when(p.getPlanType()).thenReturn("MONTHLY");
        when(p.getRecurringFlag()).thenReturn(1);

        LocalDateTime now = LocalDateTime.now();
        when(p.getNextCycleStartDate()).thenReturn(now.plusDays(1));
        when(p.getExpiryDate()).thenReturn(now.plusDays(30));
        when(p.getServiceStartDate()).thenReturn(now.minusDays(5));
        when(p.getCurrentCycleStartDate()).thenReturn(now.minusDays(1));
        when(p.getCurrentCycleEndDate()).thenReturn(now.plusDays(29));
        when(p.getIsGroup()).thenReturn(0);

        // Act
        ServiceInfo result = mapper.mapIntoServiceInfo(p);

        // Assert
        assertNotNull(result);
        assertEquals("SVC123", result.getServiceId());
        assertEquals("userA", result.getUsername());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals("PLAN01", result.getPlanId());
        assertEquals("MONTHLY", result.getPlanType());
        assertTrue(result.getRecurringFlag());
        assertEquals(now.plusDays(1), result.getNextCycleStartDate());
        assertEquals(now.plusDays(30), result.getExpiryDate());
        assertEquals(now.minusDays(5), result.getServiceStartDate());
        assertEquals(now.minusDays(1), result.getCurrentCycleStartDate());
        assertEquals(now.plusDays(29), result.getCurrentCycleEndDate());

        // isGroup should be FALSE because username ≠ groupId
        assertFalse(result.getIsGroup());
    }

    @Test
    void testMapIntoServiceInfo_IsGroupTrue_WhenUsernameMatchesGroupId() {
        ServiceInfoProjection p = mock(ServiceInfoProjection.class);
        when(p.getUsername()).thenReturn("groupA");
        when(p.getIsGroup()).thenReturn(1);

        // Act
        ServiceInfo result = mapper.mapIntoServiceInfo(p);

        // Assert
        assertTrue(result.getIsGroup());
    }

    @Test
    void testMapIntoServiceInfo_IsGroupFalse_WhenGroupIdNull() {
        ServiceInfoProjection p = mock(ServiceInfoProjection.class);
        when(p.getUsername()).thenReturn("userA");

        // Act
        ServiceInfo result = mapper.mapIntoServiceInfo(p);

        // Assert
        assertFalse(result.getIsGroup());
    }

    @Test
    void testMapIntoServiceInfo_IsGroupFalse_WhenUsernameDifferentFromGroupId() {
        ServiceInfoProjection p = mock(ServiceInfoProjection.class);
        when(p.getUsername()).thenReturn("userX");

        // Act
        ServiceInfo result = mapper.mapIntoServiceInfo(p);

        // Assert
        assertFalse(result.getIsGroup());
    }
}
