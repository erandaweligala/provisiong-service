package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BngUpdateRequest {

    // bngId and bngName cannot be updated, so they're not in the request

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
}