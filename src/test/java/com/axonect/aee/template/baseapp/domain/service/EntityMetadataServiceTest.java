package com.axonect.aee.template.baseapp.domain.service;

import jakarta.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityMetadataServiceTest {

    private EntityMetadataService entityMetadataService;

    @BeforeEach
    void setUp() {
        entityMetadataService = new EntityMetadataService();
    }

    // --------------------------------------------
    // Valid Entity Lookup (Case Insensitive)
    // --------------------------------------------
    @Test
    void shouldReturnColumnsForValidEntity_caseInsensitive() {
        List<String> columns = entityMetadataService.getEntityColumns("aaa_user");

        assertNotNull(columns);
        assertFalse(columns.isEmpty());
    }

    // --------------------------------------------
    // Invalid Entity (Exception Branch)
    // --------------------------------------------
    @Test
    void shouldThrowExceptionWhenEntityNotFound() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class,
                        () -> entityMetadataService.getEntityColumns("INVALID_TABLE"));

        assertTrue(exception.getMessage().contains("Entity 'INVALID_TABLE' not found"));
    }

    // --------------------------------------------
    //  Full Reflection Coverage Test
    // Covers:
    // - @Column
    // - @Id
    // - @JoinColumn
    // - @Transient
    // - Inherited fields
    // --------------------------------------------
    @Test
    void shouldExtractAllSupportedAnnotationsAndIgnoreTransient() {
        List<String> columns =
                entityMetadataServiceTestHelper(new TestChildEntity());

        assertEquals(3, columns.size());
        assertTrue(columns.contains("ID"));              // from @Id
        assertTrue(columns.contains("TEST_COLUMN"));     // from @Column
        assertTrue(columns.contains("JOIN_ID"));         // from @JoinColumn
        assertFalse(columns.contains("IGNORED_FIELD"));  // @Transient excluded
    }

    // --------------------------------------------
    // Helper method to access private extract logic
    // --------------------------------------------
    private List<String> entityMetadataServiceTestHelper(Object entity) {
        try {
            var method = EntityMetadataService.class
                    .getDeclaredMethod("extractColumnNames", Class.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(entityMetadataService, entity.getClass());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------
    //  Parent class (tests inherited fields)
    // --------------------------------------------
    static class TestParentEntity {

        @Id
        private Long id;
    }

    // --------------------------------------------
    //  Child class (tests all annotation types)
    // --------------------------------------------
    static class TestChildEntity extends TestParentEntity {

        @Column(name = "TEST_COLUMN")
        private String testField;

        @JoinColumn(name = "JOIN_ID")
        private String joinField;

        @Transient
        private String ignoredField;
    }
}
