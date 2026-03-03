package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaginatedResponse<T> {

    @JsonProperty("TemplateData")
    private List<T> templateData;

    @JsonProperty("BNGData")
    private List<T> bngData;

    @JsonProperty("VendorConfigData")
    private List<T>vendorConfigData;

    private PageDetails pageDetails;
}