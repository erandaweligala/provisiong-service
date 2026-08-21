package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ActionLogSpecifications {

    private static final String FIELD_ACTION      = "action";
    private static final String FIELD_GROUP_ID    = "groupId";
    private static final String FIELD_REQUEST_ID  = "requestId";
    private static final String FIELD_USERNAME    = "userName";
    private static final String FIELD_RESULT_CODE = "resultCode";
    private static final String FIELD_HTTP_STATUS = "httpStatus";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_DATE_TIME   = "dateTime";
    private static final String FIELD_CHANNEL   = "channel";
    private static final String HTTP_200          = "200";
    private static final String HTTP_201          = "201";

    private ActionLogSpecifications() {
        // Utility class — do not instantiate
    }

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
            LocalDateTime endTime,
            String channel
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            addEqualPredicates(predicates, root, cb, action, groupId, requestId, username, resultCode, httpStatus, channel);
            addDescriptionPredicate(predicates, root, cb, description);
            addSuccessPredicate(predicates, root, cb, success);
            addDateRangePredicates(predicates, root, cb, startTime, endTime);

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void addEqualPredicates(
            List<Predicate> predicates,
            Root<ActionLog> root,
            CriteriaBuilder cb,
            String action,
            String groupId,
            String requestId,
            String username,
            String resultCode,
            String httpStatus,
            String channel
    ) {
        if (hasValue(action))     predicates.add(cb.equal(root.get(FIELD_ACTION),      action));
        if (hasValue(groupId))    predicates.add(cb.equal(root.get(FIELD_GROUP_ID),    groupId));
        if (hasValue(requestId))  predicates.add(cb.equal(root.get(FIELD_REQUEST_ID),  requestId));
        if (hasValue(username))   predicates.add(cb.equal(root.get(FIELD_USERNAME),    username));
        if (hasValue(resultCode)) predicates.add(cb.equal(root.get(FIELD_RESULT_CODE), resultCode));
        if (hasValue(httpStatus)) predicates.add(cb.equal(root.get(FIELD_HTTP_STATUS), httpStatus));
        if (hasValue(channel))    predicates.add(cb.equal(root.get(FIELD_CHANNEL),     channel));
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static void addDescriptionPredicate(
            List<Predicate> predicates,
            Root<ActionLog> root,
            CriteriaBuilder cb,
            String description
    ) {
        if (hasValue(description)) {
            predicates.add(cb.like(root.get(FIELD_DESCRIPTION), "%" + description + "%"));
        }
    }

    // success: true -> 200 or 201, false -> not 200/201, null -> no filter
    private static void addSuccessPredicate(
            List<Predicate> predicates,
            Root<ActionLog> root,
            CriteriaBuilder cb,
            Boolean success
    ) {
        if (success == null) return;

        if (Boolean.TRUE.equals(success)) {
            predicates.add(root.get(FIELD_HTTP_STATUS).in(HTTP_200, HTTP_201));
        } else {
            predicates.add(cb.not(root.get(FIELD_HTTP_STATUS).in(HTTP_200, HTTP_201)));
        }
    }

    private static void addDateRangePredicates(
            List<Predicate> predicates,
            Root<ActionLog> root,
            CriteriaBuilder cb,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        if (startTime != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(FIELD_DATE_TIME), startTime));
        }
        if (endTime != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(FIELD_DATE_TIME), endTime));
        }
    }
}