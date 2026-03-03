package com.axonect.aee.template.baseapp.domain.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BucketEvent {
    private Long bucketInstanceId;
    private String bucketId;
    private Long serviceId;
    private String bucketType;
    private String rule;
    private Long priority;
    private Long initialBalance;
    private Long currentBalance;
    private Long usage;
    private Boolean carryForward;
    private Long maxCarryForward;
    private Long totalCarryForward;
    private Integer carryForwardValidity;
    private String timeWindow;
    private Long consumptionLimit;
    private String consumptionLimitWindow;
    private String expiration;
    private Boolean isUnlimited;
    private String updatedAt;
}