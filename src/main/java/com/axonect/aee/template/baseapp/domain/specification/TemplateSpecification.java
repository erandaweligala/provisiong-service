package com.axonect.aee.template.baseapp.domain.specification;

import com.axonect.aee.template.baseapp.application.transport.request.entities.TemplateFilterRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.SuperTemplate;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class TemplateSpecification {

    public static Specification<SuperTemplate> filterTemplate(TemplateFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.getTemplateName() != null && !filter.getTemplateName().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("templateName")),
                        "%" + filter.getTemplateName().toLowerCase() + "%"
                ));
            }

            if (filter.getStatus() != null && !filter.getStatus().isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("status")),
                        filter.getStatus().toLowerCase()
                ));
            }

            if (filter.getIsDefault() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("isDefault"),
                        filter.getIsDefault()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}