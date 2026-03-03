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
public class VendorConfigUpdateRequest {

    @NotNull(message = "ID is required")
    private Long id;

    @NotBlank(message = "Vendor ID is required")
    @Size(max = 100, message = "Vendor ID must not exceed 100 characters")
    private String vendorId;

    @Size(max = 255, message = "Vendor name must not exceed 255 characters")
    private String vendorName;

    @Size(max = 255, message = "Attribute name must not exceed 255 characters")
    private String attributeName;

    private String attributeId;

    @Size(max = 500, message = "Value path must not exceed 500 characters")
    private String valuePath;

    @Size(max = 255, message = "Entity must not exceed 255 characters")
    private String entity;

    @Size(max = 100, message = "Data type must not exceed 100 characters")
    private String dataType;

    @Size(max = 255, message = "Parameter must not exceed 255 characters")
    private String parameter;

    private Boolean isActive;

    @Size(max = 100, message = "Attribute prefix must not exceed 100 characters")
    private String attributePrefix;

    @NotBlank(message = "Updated by is required")
    @Size(max = 100, message = "Updated by must not exceed 100 characters")
    private String updatedBy;
}