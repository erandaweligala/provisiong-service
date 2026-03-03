package com.axonect.aee.template.baseapp.application.transport.response.transformers.actionlogs;

import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PagedActionLogResponse {

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("page_size")
    private Integer pageSize;

    @JsonProperty("total_records")
    private Long totalRecords;

    @JsonProperty("logs")
    private List<ActionLog> logs;
}
