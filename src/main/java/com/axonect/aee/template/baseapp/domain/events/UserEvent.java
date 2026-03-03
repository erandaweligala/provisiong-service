package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEvent {
    private String userId;
    private String userName;
    private String groupId;
    private String nasPortType;
    private String bandwidth;
    private String vlanId;
    private String circuitId;
    private String remoteId;
    private String ipAllocation;
    private String ipPoolName;
    private String ipv4;
    private String ipv6;
    private Long templateId;
    private String templateName;
    private String status;
    private String subscription;
    private String contactName;
    private String contactEmail;
    private String contactNumber;
    private Integer concurrency;
    private String billing;
    private Integer cycleDate;
    private String billingAccountRef;
    private String sessionTimeout;
    private String requestId;
    private String createdDate;
    private String updatedDate;
}