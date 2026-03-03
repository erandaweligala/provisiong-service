package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateRequestDTO {

    @PositiveOrZero(message = "Quota must be zero or a positive value")
    private Long quota;

    @JsonProperty("balance_quota")
    @PositiveOrZero(message = "Balance quota must be zero or a positive value")
    private Long balanceQuota;

    @JsonProperty("service_start_date")
    private LocalDateTime serviceStartDate;

    @JsonProperty("service_end_date")
    @FutureOrPresent(message = "Service end date must be in the future or present")
    private LocalDateTime serviceEndDate;

    @JsonProperty("status")
    private Integer status;
}
