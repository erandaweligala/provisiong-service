package com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ServiceInfo {

    private String serviceId;
    private String username;
    private String status;
    private String planId;
    private String planType;
    private Boolean recurringFlag;
    private LocalDateTime nextCycleStartDate;
    private LocalDateTime expiryDate;
    private LocalDateTime serviceStartDate;
    private Integer cycleDate;
    private LocalDateTime currentCycleStartDate;
    private LocalDateTime currentCycleEndDate;
    private Boolean isGroup;

}
