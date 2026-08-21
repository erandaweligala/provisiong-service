package com.axonect.aee.template.baseapp.domain.entities.dto;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ACTION_LOG")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ADMIN_USER")
    private String adminUser;

    @Column(name = "USER_NAME")
    private String userName;

    @Column(name = "GROUP_ID")
    private String groupId;

    @Column(name = "DATE_TIME")
    private LocalDateTime dateTime;

    @Column(name = "REQUEST_ID")
    private String requestId;

    @Column(name = "ACTION")
    private String action;

    @Column(name = "RESULT_CODE")
    private String resultCode;

    @Column(name = "HTTP_STATUS")
    private String httpStatus;

    @Column(name = "DESCRIPTION")
    private String description;

    @Column(name = "CHANNEL")
    private String channel;

    @Column(name = "RESPONSE_TIME")
    private Long responseTime;

    @Column(name = "APP")
    private String application;

    @Column(name = "OPCO")
    private String opco;

    @Column(name = "REQUEST_START_TIME")
    private LocalDateTime requestStartTime;

    @Column(name = "REQUEST_END_TIME")
    private LocalDateTime requestEndTime;

}
