package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UpdateResponseDTO {

    @JsonProperty("request_id")
    private String requestId;
    
    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("plan_id")
    private String planId;

    @JsonProperty("plan_name")
    private String planName;

    @JsonProperty("status")
    private Integer status;

    @JsonProperty("final_quota")
    private Long finalQuota;

    @JsonProperty("balance_quota")
    private Long balanceQuota;

    @JsonProperty("service_start_date")
    private LocalDateTime serviceStartDate;

    @JsonProperty("service_end_date")
    private LocalDateTime serviceEndDate;

    @JsonProperty("next_cycle_start_date")
    private LocalDateTime nextCycleStartDate;

    @JsonProperty("updated_date")
    private LocalDateTime updatedDate;
}
