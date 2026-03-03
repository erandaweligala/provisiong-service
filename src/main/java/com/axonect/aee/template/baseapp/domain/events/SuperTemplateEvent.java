package com.axonect.aee.template.baseapp.domain.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuperTemplateEvent {
    private Long superTemplateId;
    private String templateName;
    private String status;           // "ACTIVE", "INACTIVE", "DRAFT"
    private Boolean isDefault;
    private String createdBy;
    private String createdAt;        // Format: "yyyy-MM-dd'T'HH:mm:ss.SSS"
    private String updatedBy;
    private String updatedAt;        // Format: "yyyy-MM-dd'T'HH:mm:ss.SSS"
}