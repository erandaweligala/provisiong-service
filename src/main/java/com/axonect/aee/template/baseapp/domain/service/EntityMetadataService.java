package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.ServiceInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EntityMetadataService {

    // Map of table names to entity classes
    private static final Map<String, Class<?>> TABLE_TO_ENTITY_MAP = new HashMap<>();

    // Cache reflection results — entity classes are immutable at runtime
    private static final Map<String, List<String>> COLUMN_CACHE = new HashMap<>();

    static {
        TABLE_TO_ENTITY_MAP.put("AAA_USER", UserEntity.class);
        TABLE_TO_ENTITY_MAP.put("SERVICE_INSTANCE", ServiceInstance.class);
        TABLE_TO_ENTITY_MAP.put("BUCKET_INSTANCE", BucketInstance.class);
    }

    /**
     * Get all column names for a specific entity/table
     * Case-insensitive lookup
     *
     * @param entityName - The table name (AAA_USER, SERVICE_INSTANCE, BUCKET_INSTANCE)
     * @return List of column names sorted alphabetically
     */
    public List<String> getEntityColumns(String entityName) {
        String normalizedName = entityName.toUpperCase();

        return COLUMN_CACHE.computeIfAbsent(normalizedName, key -> {
            Class<?> entityClass = TABLE_TO_ENTITY_MAP.get(key);

            if (entityClass == null) {
                throw new IllegalArgumentException(
                        String.format("Entity '%s' not found. Available entities: AAA_USER, SERVICE_INSTANCE, BUCKET_INSTANCE",
                                entityName)
                );
            }

            return Collections.unmodifiableList(extractColumnNames(entityClass));
        });
    }

    /**
     * Extract column names from entity class using reflection
     * Excludes @Transient fields
     *
     * @param entityClass - The entity class
     * @return Sorted list of column names
     */
    private List<String> extractColumnNames(Class<?> entityClass) {
        List<String> columnNames = new ArrayList<>();

        // Get all fields including inherited ones
        Field[] fields = getAllFields(entityClass);

        for (Field field : fields) {
            // Skip transient fields (not stored in database)
            if (field.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                continue;
            }

            // Get column name from @Column annotation
            String columnName = getColumnName(field);
            if (columnName != null) {
                columnNames.add(columnName);
            }
        }

        // Sort alphabetically
        Collections.sort(columnNames);

        return columnNames;
    }

    /**
     * Get all fields from a class including inherited fields
     */
    private Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    /**
     * Get column name from field annotations
     */
    private String getColumnName(Field field) {
        // Check for @Column annotation
        if (field.isAnnotationPresent(jakarta.persistence.Column.class)) {
            jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
            if (!column.name().isEmpty()) {
                return column.name();
            }
        }

        // Check for @Id annotation (primary keys)
        if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
            return convertToSnakeCase(field.getName()).toUpperCase();
        }

        // Check for @JoinColumn annotation
        if (field.isAnnotationPresent(jakarta.persistence.JoinColumn.class)) {
            jakarta.persistence.JoinColumn joinColumn = field.getAnnotation(jakarta.persistence.JoinColumn.class);
            if (!joinColumn.name().isEmpty()) {
                return joinColumn.name();
            }
        }

        return null;
    }

    /**
     * Convert camelCase to SNAKE_CASE
     */
    private String convertToSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}