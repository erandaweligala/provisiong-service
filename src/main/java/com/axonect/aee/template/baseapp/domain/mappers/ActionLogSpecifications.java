package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Component
public class ActionLogSpecifications {

    public static Specification<ActionLog> filterLogs(
            String action,
            String groupId,
            String requestId,
            String username,
            String resultCode,
            String httpStatus,
            String description,
            Boolean success,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (groupId != null) predicates.add(cb.equal(root.get("groupId"), groupId));
            if (requestId != null) predicates.add(cb.equal(root.get("requestId"), requestId));
            if (username != null) predicates.add(cb.equal(root.get("userName"), username));
            if (resultCode != null) predicates.add(cb.equal(root.get("resultCode"), resultCode));
            if (httpStatus != null) predicates.add(cb.equal(root.get("httpStatus"), httpStatus));
            if (description != null) predicates.add(cb.like(root.get("description"), "%" + description + "%"));

            // success: true -> 200 or 201, false -> not 200/201, null -> no filter
            if (success != null) {
                if (success) {
                    predicates.add(root.get("httpStatus").in("200", "201"));
                } else {
                    predicates.add(cb.not(root.get("httpStatus").in("200", "201")));
                }
            }

            if (startTime != null) predicates.add(cb.greaterThanOrEqualTo(root.get("dateTime"), startTime));
            if (endTime != null) predicates.add(cb.lessThanOrEqualTo(root.get("dateTime"), endTime));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

