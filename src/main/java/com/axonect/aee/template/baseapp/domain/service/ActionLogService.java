package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.ActionLogRepository;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.actionlogs.PagedActionLogResponse;
import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.mappers.ActionLogSpecifications;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActionLogService {

    private final ActionLogRepository actionLogRepository;

    @Transactional(readOnly = true)
    public PagedActionLogResponse getActionLogs(
            String action,
            String groupId,
            String requestId,
            String username,
            String resultCode,
            String httpStatus,
            String description,
            Boolean success,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int page,
            int limit
    ) {
        try {
            Pageable pageable = PageRequest.of(page - 1, limit, Sort.by("id").descending());

            Page<ActionLog> result = actionLogRepository.findAll(
                    ActionLogSpecifications.filterLogs(
                            action, groupId, requestId, username, resultCode,
                            httpStatus, description, success, startTime, endTime
                    ),
                    pageable
            );
            log.debug("Fetched {} action logs", result.getTotalElements());
            return new PagedActionLogResponse(
                    page,
                    limit,
                    result.getTotalElements(),
                    result.getContent()
            );

        } catch (Exception e) {
            log.error("Error fetching action logs", e);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }


}

