package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * DTO for Create User API request.
 * Based on AAA API Specification v1.3.
 * Uses @JsonProperty to preserve snake_case field names in JSON.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateUserRequest {

    // --- Basic User Info ---
    @JsonProperty("user_name")
    @NotBlank(message = "user_name is mandatory")
    private String userName;

    @JsonProperty("password")
    private String password; // Required if nas_port_type = PPPoE

    @JsonProperty("encryption_method")
    private Integer encryptionMethod;
    /*
        0 – Plain text
        1 – MD5
        2 – CSG/ADL proprietary
        Mandatory only if password is provided
    */

    @JsonProperty("nas_port_type")
    @NotBlank(message = "nas_port_type is mandatory (PPPoE/IPoE)")
    private String nasPortType; // PPPoE or IPoE

    // --- Group / Network Info ---
    @JsonProperty("group_id")
    private String groupId; // Optional; defaults to 1 if not provided

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

// Required for IPoE users

    @JsonProperty("ip_allocation")
    @Pattern(
            regexp = "^$|^(?i)(Dynamic|static)$",
            message = "ip_allocation must be either 'Dynamic' or 'static'"
    )
    private String ipAllocation; // Required if nas_port_type = IPoE; Dynamic or static

    @JsonProperty("ip_pool_name")
    private String ipPoolName;   // Required if ip_allocation = Dynamic

    @JsonProperty("ipv4")
    private String ipv4; // Required if ip_allocation = static

    @JsonProperty("ipv6")
    private String ipv6; // Required if ip_allocation = static

    // --- Billing ---
    @JsonProperty("billing")
    private String billing; // 1=daily, 2=monthly, 3=billing cycle

    @JsonProperty("cycle_date")
    private Integer cycleDate; // Required if billing = 3

    // --- Contact Info ---
    @JsonProperty("contact_name")
    private String contactName;

    @JsonProperty("contact_email")
    @Pattern(
            regexp = "^$|^(\\s*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\s*)(,\\s*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\s*)*$",
            message = "Invalid contact_email format. Multiple emails allowed, separated by commas."
    )
    private String contactEmail;

    @JsonProperty("contact_number")
    @Pattern(
            regexp = "^(\\+?[0-9\\s\\-\\(\\)]+)(,\\s*\\+?[0-9\\s\\-\\(\\)]+)*$",
            message = "Invalid contact_number format. Multiple numbers allowed, separated by commas."
    )
    private String contactNumber;

    // --- Session Config ---
    @JsonProperty("concurrency")
    private Integer concurrency = 5;

    @JsonProperty("billing_account_ref")
    private String billingAccountRef;

    @JsonProperty("session_timeout")
    private String sessionTimeout = String.valueOf(86400L);

    // --- Status / Request ---
    @JsonProperty("status")
    @NotNull(message = "status is mandatory")
    private Integer status; // 1 = Active, 2 = Suspended, 3 = Inactive

    @JsonProperty("subscription")
    @NotNull(message = "subscription is mandatory")
    private Integer subscription; // 0 = Prepaid, 1 = Postpaid, 2 = Hybrid


    @JsonProperty("request_id")
    @NotBlank(message = "request_id is mandatory")
    private String requestId;

    @JsonProperty("template_id")
    private Long templateId;
}
