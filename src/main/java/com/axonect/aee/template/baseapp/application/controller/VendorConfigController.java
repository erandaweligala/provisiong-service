package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigUpdateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PaginatedResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.vendorconfig.VendorConfigResponse;
import com.axonect.aee.template.baseapp.domain.service.VendorConfigService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendor-configs")
@RequiredArgsConstructor
public class VendorConfigController {

    private final VendorConfigService service;

    /**
     * Create a new Vendor Config
     */
    @PostMapping
    public ResponseEntity<ApiResponse> create(@Valid @RequestBody VendorConfigRequest request) {
        VendorConfigResponse response = service.create(request);
        return ResponseEntity.ok(new ApiResponse(true, "Vendor config created successfully", response));
    }

    /**
     * Update an existing Vendor Config
     * VendorId cannot be updated
     * ID and vendorId must match the existing record
     * AttributeId and attributeName must be unique for the vendor
     */
    @PutMapping
    public ResponseEntity<ApiResponse> update(@Valid @RequestBody VendorConfigUpdateRequest request) {
        VendorConfigResponse response = service.update(request);
        return ResponseEntity.ok(new ApiResponse(true, "Vendor config updated successfully", response));
    }

    /**
     * Delete a Vendor Config by ID
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(@PathVariable Long id) {
        String message = service.delete(id);
        return ResponseEntity.ok(new ApiResponse(true, message, null));
    }

    /**
     * Endpoint to search/filter VendorConfig records.
     *
     * @param vendorId Filter by Vendor ID (partial match, case-insensitive)
     * @param vendorName Filter by Vendor Name (partial match, case-insensitive)
     * @param attributeId Filter by Attribute ID (exact match)
     * @param attributeName Filter by Attribute Name (partial match, case-insensitive)
     * @param page Page number (default: 1)
     * @param size Page size (default: 20, max: 100)
     * @param sortBy Sort field (default: createdDate)
     * @param sortDirection Sort direction ASC/DESC (default: DESC)
     * @return ResponseEntity containing paginated VendorConfig records
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse> searchVendorConfig(
            @RequestParam(required = false) String vendorId,
            @RequestParam(required = false) String vendorName,
            @RequestParam(required = false) String attributeId,
            @RequestParam(required = false) String attributeName,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "createdDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        VendorConfigFilterRequest filter = VendorConfigFilterRequest.builder()
                .vendorId(vendorId)
                .vendorName(vendorName)
                .attributeId(attributeId)
                .attributeName(attributeName)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        PaginatedResponse<VendorConfigResponse> response = service.searchVendorConfig(filter);

        return ResponseEntity.ok(
                new ApiResponse(true, "Vendor config records retrieved successfully", response)
        );
    }

    /**
     * Get a specific vendor config by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> getById(@PathVariable Long id) {
        VendorConfigResponse response = service.getById(id);
        return ResponseEntity.ok(new ApiResponse(true, "Vendor config retrieved successfully", response));
    }

    /**
     * Get all configurations for a specific vendor by vendor ID
     */
    @GetMapping("/vendor/{vendorId}")
    public ResponseEntity<ApiResponse> getByVendorId(@PathVariable String vendorId) {
        List<VendorConfigResponse> responses = service.getByVendorId(vendorId);
        return ResponseEntity.ok(
                new ApiResponse(
                        true,
                        String.format("Found %d configurations for vendor '%s'", responses.size(), vendorId),
                        responses
                )
        );
    }

    /**
     * Get distinct list of vendor IDs and names
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> getVendorList() {
        List<VendorConfigService.VendorSummary> vendors = service.getVendorList();
        return ResponseEntity.ok(new ApiResponse(true, "Vendor list retrieved successfully", vendors));
    }

    /**
     * Standard API response wrapper
     */
    @Getter
    @Setter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse {
        private boolean success;
        private String message;
        private Object data;

        // Optional convenience constructor
        public ApiResponse(boolean success, Object data) {
            this.success = success;
            this.data = data;
            this.message = null;
        }
    }
}