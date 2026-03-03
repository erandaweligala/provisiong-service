package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateFilterRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateTemplateRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PaginatedResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.CreateTemplateResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateFilterResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.TemplateListResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.notification.UpdateTemplateResponse;
import com.axonect.aee.template.baseapp.domain.service.TemplateService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification-templates")
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateController {


    private final TemplateService templateService;

    /**
     * Create a new template with child templates
     *
     * @param request CreateTemplateRequest containing template details
     * @return ApiResponse with created template data
     */
    @PostMapping
    public ResponseEntity<ApiResponse> createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        log.info("Received request to create template: {}", request.getTemplateName());

        CreateTemplateResponse response = templateService.createTemplate(request);

        log.info("Template created successfully with ID: {}", response.getSuperTemplateId());

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new ApiResponse(true, "Template created successfully", response));
    }
    /**
     * Update an existing template
     *
     * @param templateId Template internal ID
     * @param request UpdateTemplateRequest containing updated template details
     * @return ApiResponse with updated template data
     */
    @PutMapping("/{templateId}")
    public ResponseEntity<ApiResponse> updateTemplate(
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateTemplateRequest request) {

        log.info("Received request to update template with ID: {}", templateId);

        UpdateTemplateResponse response = templateService.updateTemplate(templateId, request);

        log.info("Template updated successfully with ID: {}", response.getSuperTemplateId());

        return ResponseEntity
                .ok(new ApiResponse(true, "Template updated successfully", response));
    }
    /**
     * Search/filter templates with pagination
     *
     * @param templateName Filter by template name (partial match, case-insensitive)
     * @param status Filter by status (exact match, case-insensitive)
     * @param isDefault Filter by default flag
     * @param page Page number (default: 1)
     * @param size Page size (default: 20)
     * @param sortBy Sort field (default: createdAt)
     * @param sortDirection Sort direction ASC/DESC (default: DESC)
     * @return ResponseEntity containing paginated template records
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse> searchTemplates(
            @RequestParam(required = false) String templateName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isDefault,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        log.info("Received request to search templates - templateName: {}, status: {}, isDefault: {}, page: {}, size: {}",
                templateName, status, isDefault, page, size);

        TemplateFilterRequest filter = TemplateFilterRequest.builder()
                .templateName(templateName)
                .status(status)
                .isDefault(isDefault)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        PaginatedResponse<TemplateFilterResponse> response = templateService.searchTemplates(filter);

        return ResponseEntity.ok(
                new ApiResponse(true, "Template records retrieved successfully", response)
        );
    }
    /**
     * Endpoint to retrieve all remaining quota percentage values for a specific super template.
     *
     * @param superTemplateId Super template ID
     * @return list of remaining quota percentages (0–100)
     */
    @GetMapping("/{superTemplateId}/quota-percentages")
    public ResponseEntity<ApiResponse> getRemainingQuotaPercentages(@PathVariable Long superTemplateId) {
        log.info("Fetching remaining quota percentages for super template ID: {}", superTemplateId);

        List<Integer> result = templateService.getRemainingQuotaPercentages(superTemplateId);

        return ResponseEntity.ok(
                new ApiResponse(true, "Fetched remaining quota percentages successfully", result)
        );
    }

    /**
     * Get template by ID with all child templates
     *
     * @param templateId Template ID
     * @return ApiResponse with template data including child templates
     */
    @GetMapping("/{templateId}")
    public ResponseEntity<ApiResponse> getTemplateById(@PathVariable Long templateId) {
        log.info("Received request to retrieve template with ID: {}", templateId);

        CreateTemplateResponse response = templateService.getTemplateById(templateId);

        log.info("Template retrieved successfully with ID: {}", templateId);

        return ResponseEntity.ok(
                new ApiResponse(true, "Template retrieved successfully", response)
        );
    }
    /**
     * Get all templates as a simple list (ID and name only)
     *
     * @return List of all templates with ID and name
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse> getAllTemplatesList() {
        log.info("Received request to retrieve all templates list");

        List<TemplateListResponse> response = templateService.getAllTemplatesList();

        log.info("Retrieved {} templates in list", response.size());

        return ResponseEntity.ok(
                new ApiResponse(true, "Templates list retrieved successfully", response)
        );
    }

    /**
     * Delete a template by ID
     *
     * @param templateId Template ID to delete
     * @return Success response
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<ApiResponse> deleteTemplate(@PathVariable Long templateId) {
        log.info("DELETE /api/v1/templates/{} - Delete template request", templateId);

        templateService.deleteTemplate(templateId);

        return ResponseEntity.ok(
                new ApiResponse(true, "Template deleted successfully", null)
        );
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
