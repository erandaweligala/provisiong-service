package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import jakarta.validation.constraints.FutureOrPresent;
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
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime serviceStartDate;

    @JsonProperty("service_end_date")
    @FutureOrPresent(message = "Service end date must be in the future or present")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime serviceEndDate;

    @JsonProperty("status")
    private Integer status;
}
