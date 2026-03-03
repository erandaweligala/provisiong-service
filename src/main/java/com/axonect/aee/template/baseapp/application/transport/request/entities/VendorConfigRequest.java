package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorConfigRequest {

    @NotBlank(message = "Vendor ID is required")
    @Size(max = 100, message = "Vendor ID must not exceed 100 characters")
    private String vendorId;

    @NotBlank(message = "Vendor name is required")
    @Size(max = 255, message = "Vendor name must not exceed 255 characters")
    private String vendorName;

    @NotBlank(message = "Attribute name is required")
    @Size(max = 255, message = "Attribute name must not exceed 255 characters")
    private String attributeName;

    @NotNull(message = "Attribute ID is required")
    private String attributeId;

    @Size(max = 500, message = "Value path must not exceed 500 characters")
    private String valuePath;

    @Size(max = 255, message = "Entity must not exceed 255 characters")
    private String entity;

    @Size(max = 100, message = "Data type must not exceed 100 characters")
    private String dataType;

    @Size(max = 255, message = "Parameter must not exceed 255 characters")
    private String parameter;

    @NotNull(message = "isActive field is required")
    private Boolean isActive;

    @Size(max = 100, message = "Attribute prefix must not exceed 100 characters")
    private String attributePrefix;

    @NotBlank(message = "Created by is required")
    @Size(max = 100, message = "Created by must not exceed 100 characters")
    private String createdBy;
}