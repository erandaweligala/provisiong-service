package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Plan {

    @JsonProperty("plan_name")
    private String planName;

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("status")
    private String status;

    @JsonProperty("plan_type")
    private String planType;

    @JsonProperty("recurring_mode")
    private String recurringMode;

    @JsonProperty("is_group")
    private Boolean isGroup;

    @JsonProperty("group_id")
    private String groupId;

    @JsonProperty("quota")
    private Quota quota;

    @JsonProperty("allocated_bandwidth")
    private AllocatedBandwidth allocatedBandwidth;
}
