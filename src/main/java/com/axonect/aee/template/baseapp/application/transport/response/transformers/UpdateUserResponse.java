package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserResponse {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("encryption_method")
    private Integer encryptionMethod;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("nas_port_type")
    private String nasPortType;

    @JsonProperty("group_id")
    private String groupId;

    @JsonProperty("bandwidth")
    private String bandwidth;

    @JsonProperty("vlan_id")
    private String vlanId;

    @JsonProperty("circuit_id")
    private String circuitId;

    @JsonProperty("remote_id")
    private String remoteId;

    @JsonProperty("mac_address")
    private String macAddress;

    @JsonProperty("ip_allocation")
    private String ipAllocation;

    @JsonProperty("ip_pool_name")
    private String ipPoolName;

    @JsonProperty("ipv4")
    private String ipv4;

    @JsonProperty("ipv6")
    private String ipv6;

    @JsonProperty("status")
    private Integer status;

    @JsonProperty("contact_name")
    private String contactName;

    @JsonProperty("contact_email")
    private String contactEmail;

    @JsonProperty("contact_number")
    private String contactNumber;

    @JsonProperty("concurrency")
    private Integer concurrency;

    @JsonProperty("billing")
    private String billing;

    @JsonProperty("cycle_date")
    private Integer cycleDate;

    @JsonProperty("billing_account_ref")
    private String billingAccountRef;

    @JsonProperty("session_timeout")
    private String sessionTimeout;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("subscription")
    private Integer subscription;

    @JsonProperty("template_name")
    private String templateName;

    @JsonIgnore
    private Long templateId;

    @JsonProperty("last_updated_timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedDate;
}
