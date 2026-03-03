package com.axonect.aee.template.baseapp.application.transport.request.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateFilterRequest {

    private String templateName;
    private String status;
    private Boolean isDefault;

    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer size = 20;

    @Builder.Default
    private String sortBy = "createdAt";

    @Builder.Default
    private String sortDirection = "DESC";
}