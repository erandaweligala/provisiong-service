package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActiveServiceRequestDTO {

    @NotBlank(message = "User ID is mandatory")
    @JsonProperty("user_id")
    private String userId;

    @NotBlank(message = "Request ID is mandatory")
    @JsonProperty("request_id")
    private String requestId;

    @NotBlank(message = "Plan ID is mandatory")
    @JsonProperty("plan_id")
    private String planId;

    @NotNull(message = "Service start date is mandatory")
    @JsonProperty("service_start_date")
    private LocalDateTime serviceStartDate;

    @JsonProperty("service_end_date")
    private LocalDateTime serviceEndDate;

    @NotBlank(message = "Status is mandatory")
    @JsonProperty("status")
    private String status;

    @NotNull(message = "Is group flag is mandatory")
    @JsonProperty("is_group")
    private Boolean isGroup;

    @PositiveOrZero(message = "Quota must be zero or a positive value")
    private Long quota;
}