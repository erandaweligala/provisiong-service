package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.response.transformers.actionlogs.PagedActionLogResponse;
import com.axonect.aee.template.baseapp.domain.service.ActionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST controller for fetching Action Logs, Message Logs & Error Logs.
 */

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Slf4j
public class LoggingController {

    private final ActionLogService actionLogService;

    @GetMapping("/action-logs")
    public ResponseEntity<BngController.ApiResponse> getActionLogs(
            @RequestParam(required = false) String action,
            @RequestParam(name = "group_id", required = false) String groupId,
            @RequestParam(name = "request_id", required = false) String requestId,
            @RequestParam(name = "user_name",required = false) String username,
            @RequestParam(name = "result_code", required = false) String resultCode,
            @RequestParam(name = "http_status", required = false) String httpStatus,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Boolean success,
            @RequestParam(name = "start_time", required = false) LocalDateTime startTime,
            @RequestParam(name = "end_time", required = false) LocalDateTime endTime,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "page_size", required = false, defaultValue = "10") Integer pageSize
    ) {

        log.info("Received ActionLog fetch request. action={}, groupId={}, requestId={}",
                action, groupId, requestId);

        PagedActionLogResponse data = actionLogService.getActionLogs(
                action, groupId, requestId, username, resultCode,
                httpStatus, description, success, startTime, endTime,
                page, pageSize
        );

        return ResponseEntity.ok(
                new BngController.ApiResponse(true, "Action logs retrieved successfully", data)
        );
    }
}
