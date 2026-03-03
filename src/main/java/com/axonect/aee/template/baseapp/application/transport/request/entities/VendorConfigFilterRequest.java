package com.axonect.aee.template.baseapp.application.transport.request.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorConfigFilterRequest {

    private String vendorId;
    private String vendorName;
    private String attributeId;
    private String attributeName;

    // Pagination fields with defaults
    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer size = 20;

    @Builder.Default
    private String sortBy = "createdDate";

    @Builder.Default
    private String sortDirection = "DESC";

    /**
     * Check if any filters are applied
     */
    public boolean hasFilters() {
        return (vendorId != null && !vendorId.isBlank()) ||
                (vendorName != null && !vendorName.isBlank()) ||
                attributeId != null ||
                (attributeName != null && !attributeName.isBlank());
    }
}