package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateTemplateRequest {

    @NotBlank(message = "Template name is required")
    private String templateName;

    @NotBlank(message = "Status is required")
    private String status;

    @NotNull(message = "isDefault flag is required")
    private Boolean isDefault;

    @NotBlank(message = "Created by is required")
    private String createdBy;

    @NotNull(message = "Templates list is required")
    @Valid
    private List<TemplateMessageRequest> templates;
}
