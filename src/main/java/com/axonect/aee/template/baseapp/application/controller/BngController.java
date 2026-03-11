package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.request.entities.BngCreateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.BngFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.BngUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.PingRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
import com.axonect.aee.template.baseapp.domain.service.BngService;
import com.axonect.aee.template.baseapp.domain.service.PingService;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing BNG operations.
 */
@RestController
@RequestMapping("/api/bng")
@RequiredArgsConstructor
@Slf4j
public class BngController {

    private final BngService bngService;
    private final PingService pingService;

    private static final String BNG_RETRIEVE = "BNG retrieved successfully";

    /**
     * Endpoint to create a new BNG.
     *
     * @param request The BNG creation request payload.
     * @return ResponseEntity containing API response with created BNG.
     */
    @PostMapping
    public ResponseEntity<ApiResponse> createBng(
            @Valid @RequestBody BngCreateRequest request,
            HttpServletRequest httpServletRequest) {

        String createdBy = httpServletRequest.getHeader("userId");
        CreateBngResponse response = bngService.createBng(request, createdBy);

        return ResponseEntity.ok(
                new ApiResponse(true, "BNG created successfully", response)
        );
    }

    /**
     * Endpoint to update an existing BNG.
     * BNG ID and BNG Name cannot be updated.
     *
     * @param bngId The BNG identifier (path parameter).
     * @param request The BNG update request payload.
     * @return ResponseEntity containing API response with updated BNG.
     */
    @PutMapping("/{bng_id}")
    public ResponseEntity<ApiResponse> updateBng(
            @PathVariable("bng_id") String bngId,
            @Valid @RequestBody BngUpdateRequest request,
            HttpServletRequest httpServletRequest) {

        String updatedBy = httpServletRequest.getHeader("userId");
        UpdateBngResponse response = bngService.updateBng(bngId, request, updatedBy);

        return ResponseEntity.ok(
                new ApiResponse(true, "BNG updated successfully", response)
        );
    }

    // Add this method to your existing BngController class

    /**
     * Endpoint to search/filter BNG records.
     *
     * @param bngId Filter by BNG ID (partial match, case-insensitive)
     * @param bngName Filter by BNG Name (partial match, case-insensitive)
     * @param bngIp Filter by BNG IP (partial match, case-insensitive)
     * @param status Filter by Status (exact match, case-insensitive)
     * @param page Page number (default: 0)
     * @param size Page size (default: 10)
     * @param sortBy Sort field (default: createdDate)
     * @param sortDirection Sort direction ASC/DESC (default: DESC)
     * @return ResponseEntity containing paginated BNG records
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse> searchBng(
            @RequestParam(required = false) String bngId,
            @RequestParam(required = false) String bngName,
            @RequestParam(required = false) String bngIp,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "createdDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        BngFilterRequest filter = BngFilterRequest.builder()
                .bngId(bngId)
                .bngName(bngName)
                .bngIp(bngIp)
                .status(status)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        PaginatedResponse<BngFilterResponse> response = bngService.searchBng(filter);

        return ResponseEntity.ok(
                new ApiResponse(true, "BNG records retrieved successfully", response)
        );
    }
    /**
     * Endpoint to get a simple list of all BNG names and IPs.
     * No pagination, returns all records.
     *
     * @return ResponseEntity containing list of BNG names and IPs
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> getBngList() {
        List<BngListResponse> response = bngService.getBngList();

        return ResponseEntity.ok(
                new ApiResponse(true, "BNG list retrieved successfully", response)
        );
    }
    /**
     * Endpoint to retrieve a single BNG by ID.
     *
     * @param bngId The BNG identifier (path parameter).
     * @return ResponseEntity containing API response with BNG details.
     */
    @GetMapping("/{bng_id}")
    public ResponseEntity<ApiResponse> getBng(@PathVariable("bng_id") String bngId) {
        GetBngResponse response = bngService.getBngById(bngId);

        return ResponseEntity.ok(
                new ApiResponse(true, BNG_RETRIEVE, response)
        );
    }

    /**
     * Endpoint to retrieve a single BNG by name.
     *
     * @param bngName The BNG name (path parameter).
     * @return ResponseEntity containing API response with BNG details.
     */
    @GetMapping("/name/{bng_name}")
    public ResponseEntity<ApiResponse> getBngByName(@PathVariable("bng_name") String bngName) {
        GetBngResponse response = bngService.getBngByName(bngName);

        return ResponseEntity.ok(
                new ApiResponse(true, BNG_RETRIEVE, response)
        );
    }

    /**
     * Endpoint to retrieve a single BNG by both ID and Name.
     * Both parameters are mandatory and must match the same BNG record.
     *
     * @param bngId The BNG identifier (path parameter - mandatory).
     * @param bngName The BNG name (path parameter - mandatory).
     * @return ResponseEntity containing API response with BNG details.
     */
    @GetMapping("/{bng_id}/{bng_name}")
    public ResponseEntity<ApiResponse> getBngByIdAndName(
            @PathVariable("bng_id") String bngId,
            @PathVariable("bng_name") String bngName) {

        GetBngResponse response = bngService.getBngByIdAndName(bngId, bngName);

        return ResponseEntity.ok(
                new ApiResponse(true, BNG_RETRIEVE, response)
        );
    }

    /**
     * Ping BNG IP
     * Returns SUCCESS or FAILED
     */
    @PostMapping("/ping")
    public ResponseEntity<ApiResponse> pingBng(@RequestBody PingRequest request) {
        String status = pingService.ping(request.getBngIp());

        // Determine message based on status
        String message = "SUCCESS".equals(status) ? "Ping SUCCESS" : "Ping FAILED";

        // Create response with all three fields
        ApiResponse response = new ApiResponse(
                "SUCCESS".equals(status), // success: true if SUCCESS, false if FAILED
                message,                  // message: "Ping SUCCESS" or "Ping FAILED"
                status                    // data: "SUCCESS" or "FAILED"
        );

        return ResponseEntity.ok(response);
    }



    /**
     * Standard API response wrapper.
     */
    @Getter
    @Setter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse {
        private boolean success;
        private String message;
        private Object data;

        public ApiResponse(boolean success, Object data) {
            this.success = success;
            this.data = data;
            this.message = null;
        }
    }
}