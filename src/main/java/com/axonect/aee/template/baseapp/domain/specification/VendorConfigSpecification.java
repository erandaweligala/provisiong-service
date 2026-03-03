package com.axonect.aee.template.baseapp.domain.specification;

import com.axonect.aee.template.baseapp.application.transport.request.entities.VendorConfigFilterRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.VendorConfig;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class VendorConfigSpecification {

    public static Specification<VendorConfig> filterVendorConfig(VendorConfigFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Vendor ID filter (partial match, case-insensitive)
            if (filter.getVendorId() != null && !filter.getVendorId().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("vendorId")),
                        "%" + filter.getVendorId().toLowerCase() + "%"
                ));
            }

            // Vendor Name filter (partial match, case-insensitive)
            if (filter.getVendorName() != null && !filter.getVendorName().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("vendorName")),
                        "%" + filter.getVendorName().toLowerCase() + "%"
                ));
            }

            // Attribute ID filter (exact match)
            // Attribute ID filter (partial match, case-insensitive)
            if (filter.getAttributeId() != null && !filter.getAttributeId().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("attributeId")),
                        "%" + filter.getAttributeId().toLowerCase() + "%"
                ));
            }

            // Attribute Name filter (partial match, case-insensitive)
            if (filter.getAttributeName() != null && !filter.getAttributeName().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("attributeName")),
                        "%" + filter.getAttributeName().toLowerCase() + "%"
                ));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}