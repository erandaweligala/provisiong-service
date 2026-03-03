package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;


class ActionLogSpecificationIntegrationTest {

    @Test
    void filterLogs_buildsAllPredicates_whenAllParamsProvided() {

        // Arrange
        Root<ActionLog> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        Path<String> actionPath = mock(Path.class);
        Path<String> groupIdPath = mock(Path.class);
        Path<String> requestIdPath = mock(Path.class);
        Path<String> usernamePath = mock(Path.class);
        Path<String> resultCodePath = mock(Path.class);
        Path<String> httpStatusPath = mock(Path.class);
        Path<String> descriptionPath = mock(Path.class);
        Path<LocalDateTime> dateTimePath = mock(Path.class);

        when(root.get("action")).thenReturn((Path) actionPath);
        when(root.get("groupId")).thenReturn((Path)groupIdPath);
        when(root.get("requestId")).thenReturn((Path)requestIdPath);
        when(root.get("userName")).thenReturn((Path)usernamePath);
        when(root.get("resultCode")).thenReturn((Path)resultCodePath);
        when(root.get("httpStatus")).thenReturn((Path)httpStatusPath);
        when(root.get("description")).thenReturn((Path)descriptionPath);
        when(root.get("dateTime")).thenReturn((Path)dateTimePath);

        Predicate p1 = mock(Predicate.class);
        Predicate p2 = mock(Predicate.class);
        Predicate p3 = mock(Predicate.class);
        Predicate p4 = mock(Predicate.class);
        Predicate p5 = mock(Predicate.class);
        Predicate p6 = mock(Predicate.class);
        Predicate p7 = mock(Predicate.class);
        Predicate p8 = mock(Predicate.class);
        Predicate p9 = mock(Predicate.class);
        Predicate pFinal = mock(Predicate.class);

        when(cb.equal(actionPath, "LOGIN")).thenReturn(p1);
        when(cb.equal(groupIdPath, "G1")).thenReturn(p2);
        when(cb.equal(requestIdPath, "REQ1")).thenReturn(p3);
        when(cb.equal(usernamePath, "john")).thenReturn(p4);
        when(cb.equal(resultCodePath, "200X")).thenReturn(p5);
        when(cb.equal(httpStatusPath, "200")).thenReturn(p6);
        when(cb.like(descriptionPath, "%success%")).thenReturn(p7);

        when(httpStatusPath.in("200", "201")).thenReturn(p8);

        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();

        when(cb.greaterThanOrEqualTo(dateTimePath, start)).thenReturn(p9);
        when(cb.lessThanOrEqualTo(dateTimePath, end)).thenReturn(mock(Predicate.class));

        when(cb.and(any(Predicate[].class))).thenReturn(pFinal);

        // Act
        Specification<ActionLog> spec = ActionLogSpecifications.filterLogs(
                "LOGIN", "G1", "REQ1", "john", "200X", "200",
                "success", true, start, end
        );

        Predicate result = spec.toPredicate(root, query, cb);

        // Assert
        assertEquals(pFinal, result);
        verify(cb, atLeastOnce()).and(any(Predicate[].class));
    }

    @Test
    void filterLogs_handlesSuccessFalse_andNullDescription() {

        // Arrange
        Root<ActionLog> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        Path<String> httpStatusPath = mock(Path.class);
        when(root.get("httpStatus")).thenReturn((Path)httpStatusPath);

        Predicate notSuccessPredicate = mock(Predicate.class);
        when(cb.not(httpStatusPath.in("200", "201"))).thenReturn(notSuccessPredicate);

        Predicate finalPredicate = mock(Predicate.class);
        when(cb.and(any(Predicate[].class))).thenReturn(finalPredicate);

        // Act
        Specification<ActionLog> spec = ActionLogSpecifications.filterLogs(
                null, null, null, null, null, null,
                null, false, null, null
        );

        Predicate result = spec.toPredicate(root, query, cb);

        // Assert
        assertEquals(finalPredicate, result);
        verify(cb).not(httpStatusPath.in("200", "201"));
    }
}


