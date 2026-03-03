package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * DTO for updating user information.
 * All fields are optional - only provided fields will be updated.
 * Uses @JsonProperty to preserve snake_case in JSON.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserRequest {

    @JsonProperty("password")
    private String password;

    @JsonProperty("encryption_method")
    private Integer encryptionMethod;
    /*
        0 – Plain text
        1 – MD5
        2 – CSG/ADL proprietary
        Mandatory only if password is provided
    */

    @JsonProperty("nas_port_type")
    @Pattern(regexp = "^(PPPoE|IPoE)$", message = "nas_port_type must be PPPoE or IPoE")
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
    @Pattern(
            regexp =
                    "^$|" +
                            "^(" +
                            "([0-9A-Fa-f]{12})|" +
                            "(([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})|" +
                            "(([0-9A-Fa-f]{2} ){5}[0-9A-Fa-f]{2})|" +
                            "(([0-9A-Fa-f]{4}\\.){2}[0-9A-Fa-f]{4})" +
                            ")" +
                            "(\\s*,\\s*" +
                            "(" +
                            "([0-9A-Fa-f]{12})|" +
                            "(([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})|" +
                            "(([0-9A-Fa-f]{2} ){5}[0-9A-Fa-f]{2})|" +
                            "(([0-9A-Fa-f]{4}\\.){2}[0-9A-Fa-f]{4})" +
                            "))*$",
            message = "Invalid MAC address format. Multiple MACs allowed, separated by commas."
    )
    private String macAddress;


    @JsonProperty("ip_allocation")
    @Pattern(
            regexp = "^$|^(?i)(Dynamic|static)$",
            message = "ip_allocation must be either 'Dynamic' or 'static'"
    )
    private String ipAllocation;

    @JsonProperty("ip_pool_name")
    private String ipPoolName;

    @JsonProperty("ipv4")
    private String ipv4;

    @JsonProperty("ipv6")
    private String ipv6;

    @JsonProperty("billing")
    @Pattern(regexp = "^[123]$", message = "billing must be 1 (daily), 2 (monthly), or 3 (billing cycle)")
    private String billing;

    @JsonProperty("cycle_date")
    private Integer cycleDate;

    @JsonProperty("status")
    private Integer status; // 1 = Active, 2 = Barred, 3 = Inactive

    @JsonProperty("subscription")
    private Integer subscription; // 0 = Prepaid, 1 = Postpaid, 2 = Hybrid // 0 = Prepaid, 1 = Postpaid, 2 = Hybrid

    @JsonProperty("contact_name")
    private String contactName;

    @JsonProperty("contact_email")
    private String contactEmail;

    @JsonProperty("contact_number")
    private String contactNumber;

    @JsonProperty("concurrency")
    private Integer concurrency;

    @JsonProperty("billing_account_ref")
    private String billingAccountRef;

    @JsonProperty("session_timeout")
    private String sessionTimeout;

    @JsonProperty("request_id")
    @NotBlank(message = "Request ID is mandatory")
    private String requestId;

    @JsonProperty("template_id")
    private Long templateId;
}
