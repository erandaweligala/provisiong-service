package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    @JsonProperty("request_id")
    @NotBlank(message = "request_id is mandatory")
    @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "request_id must be a valid UUID format (e.g. 550e8400-e29b-41d4-a716-446655440000)"
    )
    private String requestId;

    @NotBlank(message = "Plan ID is mandatory")
    @JsonProperty("plan_id")
    private String planId;

    @NotNull(message = "Service start date is mandatory")
    @JsonProperty("service_start_date")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime serviceStartDate;

    @JsonProperty("service_end_date")
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
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