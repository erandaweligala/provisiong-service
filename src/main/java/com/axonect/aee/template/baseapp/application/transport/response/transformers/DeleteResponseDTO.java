package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DeleteResponseDTO {
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("plan_id")
    private String planId;
    @JsonProperty("removed_date")
    private LocalDateTime removedDate;
}
