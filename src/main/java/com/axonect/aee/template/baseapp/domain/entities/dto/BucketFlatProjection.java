package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BucketFlatProjection {

    // ServiceInstance
    private Long          serviceInstanceId;
    private String        username;
    private String        billing;
    private String        serviceStatus;
    private Boolean       isGroup;
    private String        planId;

    // Plan
    private String        planName;
    private String        planType;
    private String        recurringPeriod;

    // BucketInstance
    private String        bucketId;
    private Long          priority;
    private Long          initialBalance;
    private Long          currentBalance;
    private Long          usage;
    private LocalDateTime bucketUpdatedAt;

    // QOSProfile
    private String        downLink;
    private String        upLink;
}
