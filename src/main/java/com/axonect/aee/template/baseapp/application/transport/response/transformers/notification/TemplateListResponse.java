package com.axonect.aee.template.baseapp.application.transport.response.transformers.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateListResponse {

    @JsonProperty("super_template_id")
    private Long superTemplateId;

    @JsonProperty("template_name")
    private String templateName;
}