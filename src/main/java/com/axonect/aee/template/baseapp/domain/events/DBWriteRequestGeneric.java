package com.axonect.aee.template.baseapp.domain.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DBWriteRequestGeneric {
    private String eventType;
    private String timestamp;
    private String userName;
    private Map<String, Object> columnValues;
    private Map<String, Object> whereConditions;
    private String tableName;

    // All related table writes bundled into one event
    private List<DBWriteRequestGeneric> relatedWrites;
}