package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ActionLogRepository;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.actionlogs.PagedActionLogResponse;
import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionLogServiceTest {
    @Mock
    private ActionLogRepository actionLogRepository;

    @InjectMocks
    private ActionLogService actionLogService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getActionLogs_returnsPagedResponse_whenRepositoryReturnsPage() {
        // Arrange
        int page = 1;
        int limit = 2;
        ActionLog row1 = new ActionLog();
        row1.setId(1L);
        row1.setAction("ACT1");

        ActionLog row2 = new ActionLog();
        row2.setId(2L);
        row2.setAction("ACT2");

        Page<ActionLog> pageResult = new PageImpl<>(List.of(row1, row2), PageRequest.of(0, limit, Sort.by("id").ascending()), 10);

        when(actionLogRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)
        )).thenReturn(pageResult);

        // Act
        PagedActionLogResponse resp = actionLogService.getActionLogs(
                null, null, null, null, null, null, null, null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), page, limit
        );

        // Assert
        assertNotNull(resp);
        assertEquals(page, resp.getPage());
        assertEquals(limit, resp.getPageSize());
        assertEquals(10L, resp.getTotalRecords());
        assertEquals(2, resp.getLogs().size());
        verify(actionLogRepository, times(1)).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class));
    }

    @Test
    void getActionLogs_returnsEmpty_whenRepositoryReturnsEmptyPage() {
        // Arrange
        int page = 2;
        int limit = 5;
        Page<ActionLog> emptyPage = new PageImpl<>(List.of(), PageRequest.of(page - 1, limit), 0);

        when(actionLogRepository.findAll(any((org.springframework.data.jpa.domain.Specification.class)), any(Pageable.class))).thenReturn(emptyPage);

        // Act
        PagedActionLogResponse resp = actionLogService.getActionLogs(
                "ACTION", "GID", "REQ", "user", "RC", "200", "desc", true,
                null, null, page, limit
        );

        // Assert
        assertNotNull(resp);
        assertEquals(0L, resp.getTotalRecords());
        assertEquals(0, resp.getLogs().size());
        verify(actionLogRepository, times(1)).findAll(any((org.springframework.data.jpa.domain.Specification.class)), any(Pageable.class));
    }

    @Test
    void getActionLogs_throwsAAAException_whenRepositoryThrows() {
        // Arrange
        int page = 1;
        int limit = 10;

        when(actionLogRepository.findAll(any((org.springframework.data.jpa.domain.Specification.class)), any(Pageable.class))).thenThrow(new RuntimeException("db down"));

        // Act & Assert
        AAAException ex = assertThrows(AAAException.class, () ->
                actionLogService.getActionLogs(null, null, null, null, null, null, null, null, null, null, page, limit)
        );

        assertNotNull(ex);
        verify(actionLogRepository, times(1)).findAll(any((org.springframework.data.jpa.domain.Specification.class)), any(Pageable.class));
    }
}
