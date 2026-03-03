package com.axonect.aee.template.baseapp.domain.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceEvent {
    private Long serviceId;
    private String planId;
    private String planName;
    private String planType;
    private Boolean recurringFlag;
    private String username;
    private String serviceCycleStartDate;
    private String serviceCycleEndDate;
    private String nextCycleStartDate;
    private String serviceStartDate;
    private String expiryDate;
    private String status;
    private String requestId;
    private Boolean isGroup;
    private Integer cycleDate;
    private String billing;
    private String createdAt;
    private String updatedAt;
}