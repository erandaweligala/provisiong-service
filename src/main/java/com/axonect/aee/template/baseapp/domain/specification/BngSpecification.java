package com.axonect.aee.template.baseapp.domain.specification;

import com.axonect.aee.template.baseapp.application.transport.request.entities.BngFilterRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class BngSpecification {

    public static Specification<BngEntity> filterBng(BngFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.getBngId() != null && !filter.getBngId().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("bngId")),
                        "%" + filter.getBngId().toLowerCase() + "%"
                ));
            }

            if (filter.getBngName() != null && !filter.getBngName().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("bngName")),
                        "%" + filter.getBngName().toLowerCase() + "%"
                ));
            }

            if (filter.getBngIp() != null && !filter.getBngIp().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("bngIp")),
                        "%" + filter.getBngIp().toLowerCase() + "%"
                ));
            }

            if (filter.getStatus() != null && !filter.getStatus().isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("status")),
                        filter.getStatus().toLowerCase()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}