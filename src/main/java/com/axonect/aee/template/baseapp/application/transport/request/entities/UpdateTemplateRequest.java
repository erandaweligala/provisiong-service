package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UpdateTemplateRequest {

    @NotBlank(message = "Status is required")
    private String status;

    @NotBlank(message = "Updated by is required")
    private String updatedBy;

    @NotNull(message = "Templates list is required")
    @Valid
    private List<TemplateMessageUpdateRequest> templates;
}
