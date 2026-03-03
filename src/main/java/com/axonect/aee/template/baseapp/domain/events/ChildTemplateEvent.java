package com.axonect.aee.template.baseapp.domain.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChildTemplateEvent {
    private Long childTemplateId;
    private String messageType;      // "EXPIRE", "USAGE", etc.
    private Integer daysToExpire;
    private Integer quotaPercentage;
    private String messageContent;
    private Long superTemplateId;
    private String createdAt;        // Format: "yyyy-MM-dd'T'HH:mm:ss.SSS"
    private String updatedAt;        // Format: "yyyy-MM-dd'T'HH:mm:ss.SSS"
}