package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.service.EntityMetadataService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/entity-metadata")
@RequiredArgsConstructor
public class EntityMetadataController {

    private final EntityMetadataService entityMetadataService;

    /**
     * Get all column names for a specific entity/table
     *
     * @param entityName - The entity/table name (case-insensitive)
     *                    Supported: AAA_USER, SERVICE_INSTANCE, BUCKET_INSTANCE
     * @return List of column names for the specified entity
     *
     * Example: GET /api/entity-metadata/AAA_USER
     *          GET /api/entity-metadata/aaa_user
     *          GET /api/entity-metadata/SERVICE_INSTANCE
     *          GET /api/entity-metadata/BUCKET_INSTANCE
     */
    @GetMapping("/{entityName}")
    public ResponseEntity<ApiResponse> getEntityColumns(@PathVariable String entityName) {
        List<String> columns = entityMetadataService.getEntityColumns(entityName);
        return ResponseEntity.ok(new ApiResponse(true, "Columns retrieved successfully", columns));
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
    }
}