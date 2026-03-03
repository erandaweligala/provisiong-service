package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionLogEvent {
    private Long id;
    private String adminUser;
    private String userName;
    private String groupId;
    private String dateTime;
    private String requestId;
    private String action;
    private String resultCode;
    private String httpStatus;
    private String description;
}