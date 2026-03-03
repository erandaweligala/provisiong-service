package com.axonect.aee.template.baseapp.application.transport.response.transformers.vendorconfig;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VendorConfigResponse {
    private Long id;
    private String vendorId;
    private String vendorName;
    private String attributeName;
    private String attributeId;
    private String valuePath;
    private String entity;
    private String dataType;
    private String parameter;
    private Boolean isActive;
    private String attributePrefix;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdDate;

    private String createdBy;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdatedDate;

    private String lastUpdatedBy;
}