    package com.axonect.aee.template.baseapp.application.transport.response.transformers;

    import com.fasterxml.jackson.annotation.JsonFormat;
    import com.fasterxml.jackson.annotation.JsonIgnore;
    import com.fasterxml.jackson.annotation.JsonProperty;
    import lombok.*;

    import java.time.LocalDateTime;

    /**
     * DTO for User API response.
     * Field names in JSON follow the API specification (snake_case),
     * while Java fields use standard camelCase for maintainability.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public class GetUserResponse {

        @JsonProperty("user_name")
        private String userName;

        @JsonProperty("encryption_method")
        private Integer encryptionMethod;

        @JsonProperty("nas_port_type")
        private String nasPortType;

        @JsonProperty("group_id")
        private String groupId;

        @JsonProperty("vlan_id")
        private String vlanId;

        @JsonProperty("mac_address")
        private String macAddress;

        @JsonProperty("bandwidth")
        private String bandwidth;

        @JsonProperty("remote_id")
        private String remoteId;

        @JsonProperty("circuit_id")
        private String circuitId;

        @JsonProperty("billing")
        private String billing;

        @JsonProperty("cycle_date")
        private Integer cycleDate;

        @JsonProperty("concurrency")
        private Integer concurrency;

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

        @JsonProperty("subscription")
        private Integer subscription;

        @JsonProperty("contact_name")
        private String contactName;

        @JsonProperty("contact_email")
        private String contactEmail;

        @JsonProperty("contact_number")
        private String contactNumber;

        @JsonProperty("billing_account_ref")
        private String billingAccountRef;

        @JsonIgnore
        private Long templateId;

        @JsonProperty("template_name")
        private String templateName;

        @JsonProperty("created_timestamp")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createdDate;

        @JsonProperty("last_updated_timestamp")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime updatedDate;
    }
