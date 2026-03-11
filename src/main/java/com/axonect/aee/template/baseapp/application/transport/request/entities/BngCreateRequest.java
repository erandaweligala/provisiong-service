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

    @NotBlank(message = "bngIp is mandatory")
    private String bngIp;

    @NotBlank(message = "bngTypeVendor is mandatory")
    private String bngTypeVendor;

    @NotBlank(message = "modelVersion is mandatory")
    private String modelVersion;

    @NotBlank(message = "nasIpAddress is mandatory")
    private String nasIpAddress;

    @NotBlank(message = "nasIdentifier is mandatory")
    private String nasIdentifier;

    @NotBlank(message = "coaIp is mandatory")
    private String coaIp;

    @NotNull(message = "coaPort is mandatory")
    private Integer coaPort;

    @NotBlank(message = "sharedSecret is mandatory")
    private String sharedSecret;

    @NotBlank(message = "location is mandatory")
    private String location;

    @NotBlank(message = "status is mandatory")
    private String status;
}