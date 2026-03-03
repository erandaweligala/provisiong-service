package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ActiveServiceResponseDTO {
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("plan_id")
        private String planId;

        @JsonProperty("plan_name")
        private String planName;

        @JsonProperty("plan_type")
        private String planType;

        @JsonProperty("status")
        private Integer status;

        @PositiveOrZero(message = "Quota must be zero or a positive value")
        @JsonProperty("final_quota")
        private Long finalQuota;

        @JsonProperty("service_start_date")
        private LocalDateTime serviceStartDate;

        @JsonProperty("service_end_date")
        private LocalDateTime serviceEndDate;
    }

