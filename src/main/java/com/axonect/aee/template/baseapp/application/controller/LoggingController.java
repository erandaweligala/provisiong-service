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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * REST controller for fetching Action Logs, Message Logs & Error Logs.
 */

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Slf4j
public class LoggingController {

    private final ActionLogService actionLogService;

    /** Ordered list of formats to try when parsing a date/time string. */
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,          // 2024-06-01T10:30:00
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),  // 2024-06-01 10:30:00
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),     // 2024-06-01 10:30
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),  // 01-06-2024 10:30:00
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),  // 01/06/2024 10:30:00
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")   // 06/01/2024 10:30:00
    );

    /** Date-only formats — time defaults to 00:00:00. */
    private static final List<DateTimeFormatter> DATE_ONLY_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,               // 2024-06-01
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),      // 01-06-2024
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),      // 01/06/2024
            DateTimeFormatter.ofPattern("MM/dd/yyyy")       // 06/01/2024
    );

    /**
     * Parses a flexible date/time string into a {@link LocalDateTime}.
     * Tries common date-time patterns first, then date-only patterns
     * (midnight). Returns {@code null} if the value is null/blank or
     * no pattern matches (and logs a warning so clients know what failed).
     */
    private LocalDateTime parseDateTime(String value, String paramName) {
        if (value == null || value.isBlank()) return null;

        String trimmed = value.trim();

        for (DateTimeFormatter fmt : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }

        for (DateTimeFormatter fmt : DATE_ONLY_FORMATTERS) {
            try {
                return LocalDate.parse(trimmed, fmt).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }

        log.warn("Could not parse '{}' for param '{}' — ignoring filter", trimmed, paramName);
        return null;
    }

    @GetMapping("/action-logs")
    public ResponseEntity<BngController.ApiResponse> getActionLogs(
            @RequestParam(required = false) String action,
            @RequestParam(name = "group_id",    required = false) String groupId,
            @RequestParam(name = "request_id",  required = false) String requestId,
            @RequestParam(name = "user_name",   required = false) String username,
            @RequestParam(name = "result_code", required = false) String resultCode,
            @RequestParam(name = "http_status", required = false) String httpStatus,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String channel,
            @RequestParam(name = "start_time",  required = false) String startTimeRaw,
            @RequestParam(name = "end_time",    required = false) String endTimeRaw,
            @RequestParam(value = "page",      required = false, defaultValue = "1")  Integer page,
            @RequestParam(value = "page_size", required = false, defaultValue = "10") Integer pageSize
    ) {
        log.info("Received ActionLog fetch request. action={}, groupId={}, requestId={}",
                action, groupId, requestId);

        LocalDateTime startTime = parseDateTime(startTimeRaw, "start_time");
        LocalDateTime endTime   = parseDateTime(endTimeRaw,   "end_time");

        PagedActionLogResponse data = actionLogService.getActionLogs(
                action, groupId, requestId, username, resultCode,
                httpStatus, description, success, startTime, endTime, channel,
                page, pageSize
        );

        return ResponseEntity.ok(
                new BngController.ApiResponse(true, "Action logs retrieved successfully", data)
        );
    }

}