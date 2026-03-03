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
public class ServiceLineResponse {

    @JsonProperty("service_line_number")
    private String serviceLineNumber;

    @JsonProperty("category")
    private String category;

    @JsonProperty("current_status")
    private String currentStatus;

    @JsonProperty("last_latch_timestamp")
    private String lastLatchTimestamp;

    @JsonProperty("plans")
    private List<Plan> plans;
}