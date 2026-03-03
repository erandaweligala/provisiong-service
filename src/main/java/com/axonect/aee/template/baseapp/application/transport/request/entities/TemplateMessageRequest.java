package com.axonect.aee.template.baseapp.application.transport.request.entities;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TemplateMessageRequest {

    @NotBlank(message = "Message type is required")
    private String messageType;

    @Min(value = 0, message = "Days to expire must be greater than or equal to 0")
    private Integer daysToExpire;

    @Min(value = 0, message = "Quota percentage must be between 0 and 100")
    @Max(value = 100, message = "Quota percentage must be between 0 and 100")
    private Integer quotaPercentage;

    @NotBlank(message = "Message content is required")
    private String messageContent;
}
