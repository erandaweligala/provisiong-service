package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorConfigEvent {
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
    private String createdDate;
    private String createdBy;
    private String lastUpdatedDate;
    private String lastUpdatedBy;
}