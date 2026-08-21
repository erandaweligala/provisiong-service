package com.axonect.aee.template.baseapp.domain.entities.dto;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "PLAN", uniqueConstraints = {
        @UniqueConstraint(name = "UK_HN0VVCYKK4BPI9T12GGJBEM6A", columnNames = "PLAN_NAME")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plan_seq")
    @SequenceGenerator(
            name = "plan_seq",
            sequenceName = "PLAN_SEQ",
            allocationSize = 1
    )
    @Column(name = "ID")
    private Long id;


    @Column(name = "PLAN_ID", nullable = false, unique = true, length = 100)
    private String planId;

    @Column(name = "PLAN_NAME", nullable = false, unique = true, length = 255)
    private String planName;

    @Column(name = "PLAN_TYPE", nullable = false, length = 64)
    private String planType;

    @Column(name = "RECURRING_FLAG", nullable = false)
    private Boolean recurringFlag = false;

    @Column(name = "RECURRING_PERIOD", length = 64)
    private String recurringPeriod;

    @Column(name = "VALIDITY_TYPE", length = 64)
    private String validityType; /*Minutes/Hours/Days/BillCycle*/
    @Column(name = "VALIDITY_PERIOD")
    private Integer validityPeriod;

    @Column(name = "STATUS", nullable = false, length = 64)
    private String status;

    @Column(name = "CONNECTION_TYPE", nullable = false, length = 64)
    private String connectionType;

    @Column(name = "QUOTA_PRORATION_FLAG")
    private Boolean quotaProrationFlag = false;

    /* ---------- Approval State ---------- */

    @Column(name = "APPROVAL_STATUS", nullable = false, length = 30)
    private String approvalStatus;

    @Column(name = "CURRENT_APPROVAL_LEVEL")
    private Integer currentApprovalLevel;

    @Column(name = "CURRENT_APPROVAL_ROLE", length = 50)
    private String currentApprovalRole;

    @Column(name = "REQUIRED_APPROVAL_LEVELS")
    private Integer requiredApprovalLevels;

    /* ---------- Submission ---------- */

    @Column(name = "SUBMITTED_BY", length = 100)
    private String submittedBy;

    @Column(name = "SUBMITTED_AT")
    private LocalDateTime submittedAt;

    /* ---------- Approval ---------- */

    @Column(name = "APPROVED_BY", length = 100)
    private String approvedBy;

    @Column(name = "APPROVED_AT")
    private LocalDateTime approvedAt;

    /* ---------- Rejection ---------- */

    @Column(name = "REJECTED_BY", length = 100)
    private String rejectedBy;

    @Column(name = "REJECTED_AT")
    private LocalDateTime rejectedAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.recurringFlag = this.recurringFlag != null && this.recurringFlag;
        this.quotaProrationFlag = this.quotaProrationFlag != null && this.quotaProrationFlag;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}