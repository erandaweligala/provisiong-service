package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BngEvent {
    private String bngId;
    private String bngName;
    private String bngIp;
    private String bngTypeVendor;
    private String modelVersion;
    private String nasIpAddress;
    private String nasIdentifier;
    private String coaIp;
    private Integer coaPort;
    private String sharedSecret;
    private String location;
    private String status;
    private String createdBy;
    private String updatedBy;
    private String createdDate;
    private String updatedDate;
}