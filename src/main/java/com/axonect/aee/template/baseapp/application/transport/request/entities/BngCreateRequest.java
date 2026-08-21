package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BngCreateRequest {

    @NotBlank(message = "bngId is mandatory")
    private String bngId;

    @NotBlank(message = "bngName is mandatory")
    private String bngName;

    private String bngIp;

    @NotBlank(message = "bngTypeVendor is mandatory")
    private String bngTypeVendor;

    @NotBlank(message = "modelVersion is mandatory")
    private String modelVersion;

    @NotBlank(message = "nasIpAddress is mandatory")
    private String nasIpAddress;

    @NotBlank(message = "nasIdentifier is mandatory")
    private String nasIdentifier;

    private String coaIp;

    private Integer coaPort;

    private String sharedSecret;

    @NotBlank(message = "location is mandatory")
    private String location;

    @NotBlank(message = "status is mandatory")
    private String status;
}