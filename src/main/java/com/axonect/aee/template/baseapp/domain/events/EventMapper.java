package com.axonect.aee.template.baseapp.domain.events;

import com.axonect.aee.template.baseapp.domain.entities.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
public class EventMapper {

    private static final String USERNAME = "USER_NAME";
    private static final String CREATED_DATE = "CREATED_DATE";
    private static final String STATUS = "STATUS";
    private static final String CREATED_BY = "CREATED_BY";
    private static final String REQUEST_ID = "REQUEST_ID";
    private static final String CREATED_AT = "CREATED_AT";
    private static final String UPDATED_AT = "UPDATED_AT";



    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    public UserEvent toUserEvent(UserEntity user) {
        return UserEvent.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .groupId(user.getGroupId())
                .nasPortType(user.getNasPortType())
                .bandwidth(user.getBandwidth())
                .vlanId(user.getVlanId())
                .circuitId(user.getCircuitId())
                .remoteId(user.getRemoteId())
                .ipAllocation(user.getIpAllocation())
                .ipPoolName(user.getIpPoolName())
                .ipv4(user.getIpv4())
                .ipv6(user.getIpv6())
                .templateId(user.getTemplateId())
                .templateName(user.getTemplateName())
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .subscription(user.getSubscription() != null ? user.getSubscription().name() : null)
                .contactName(user.getContactName())
                .contactEmail(user.getContactEmail())
                .contactNumber(user.getContactNumber())
                .concurrency(user.getConcurrency())
                .billing(user.getBilling())
                .cycleDate(user.getCycleDate())
                .billingAccountRef(user.getBillingAccountRef())
                .sessionTimeout(user.getSessionTimeout())
                .requestId(user.getRequestId())
                .createdDate(user.getCreatedDate() != null ? user.getCreatedDate().format(FORMATTER) : null)
                .updatedDate(user.getUpdatedDate() != null ? user.getUpdatedDate().format(FORMATTER) : null)
                .build();
    }

    public DBWriteRequestGeneric toDBWriteEvent(String eventType, UserEntity user, String tableName) {
        Map<String, Object> columnValues = buildColumnValues(user);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put(USERNAME, user.getUserName());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(user.getUserName())
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName(tableName)
                .build();
    }

    private Map<String, Object> buildColumnValues(UserEntity user) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("USER_ID", user.getUserId());
        columns.put(USERNAME, user.getUserName());
        columns.put("PASSWORD", user.getPassword());
        columns.put("ENCRYPTION_METHOD", user.getEncryptionMethod());
        columns.put("NAS_PORT_TYPE", user.getNasPortType());
        columns.put("GROUP_ID", user.getGroupId());
        columns.put("BANDWIDTH", user.getBandwidth());
        columns.put("VLAN_ID", user.getVlanId());
        columns.put("CIRCUIT_ID", user.getCircuitId());
        columns.put("REMOTE_ID", user.getRemoteId());
        columns.put("IP_ALLOCATION", user.getIpAllocation());
        columns.put("IP_POOL_NAME", user.getIpPoolName());
        columns.put("IPV4", user.getIpv4());
        columns.put("IPV6", user.getIpv6());
        columns.put("TEMPLATE_ID", user.getTemplateId());
        columns.put(STATUS, user.getStatus() != null ? user.getStatus().name() : null);
        columns.put("SUBSCRIPTION", user.getSubscription() != null ? user.getSubscription().name() : null);
        columns.put("CONTACT_NAME", user.getContactName());
        columns.put("CONTACT_EMAIL", user.getContactEmail());
        columns.put("CONTACT_NUMBER", user.getContactNumber());
        columns.put("CONCURRENCY", user.getConcurrency());
        columns.put("BILLING", user.getBilling());
        columns.put("CYCLE_DATE", user.getCycleDate());
        columns.put("BILLING_ACCOUNT_REF", user.getBillingAccountRef());
        columns.put("SESSION_TIMEOUT", user.getSessionTimeout());
        columns.put(REQUEST_ID, user.getRequestId());
        //      FIXED: Send dates as formatted strings
        columns.put(CREATED_DATE, user.getCreatedDate() != null ? user.getCreatedDate().format(FORMATTER) : null);
        columns.put("UPDATED_DATE", user.getUpdatedDate() != null ? user.getUpdatedDate().format(FORMATTER) : null);
        return columns;
    }

    /**
     * Map ServiceInstance to ServiceEvent
     */
    public ServiceEvent toServiceEvent(ServiceInstance service) {
        return ServiceEvent.builder()
                .serviceId(service.getId())
                .planId(service.getPlanId())
                .planName(service.getPlanName())
                .planType(service.getPlanType())
                .recurringFlag(service.getRecurringFlag())
                .username(service.getUsername())
                .serviceCycleStartDate(service.getServiceCycleStartDate() != null ?
                        service.getServiceCycleStartDate().format(FORMATTER) : null)
                .serviceCycleEndDate(service.getServiceCycleEndDate() != null ?
                        service.getServiceCycleEndDate().format(FORMATTER) : null)
                .nextCycleStartDate(service.getNextCycleStartDate() != null ?
                        service.getNextCycleStartDate().format(FORMATTER) : null)
                .serviceStartDate(service.getServiceStartDate() != null ?
                        service.getServiceStartDate().format(FORMATTER) : null)
                .expiryDate(service.getExpiryDate() != null ?
                        service.getExpiryDate().format(FORMATTER) : null)
                .status(service.getStatus())
                .requestId(service.getRequestId())
                .isGroup(service.getIsGroup())
                .cycleDate(service.getCycleDate())
                .billing(service.getBilling())
                .createdAt(service.getCreatedAt() != null ?
                        service.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(service.getUpdatedAt() != null ?
                        service.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    /**
     * Map BucketInstance to BucketEvent
     */
    public BucketEvent toBucketEvent(BucketInstance bucket) {
        return BucketEvent.builder()
                .bucketInstanceId(bucket.getId())
                .bucketId(bucket.getBucketId())
                .serviceId(bucket.getServiceId())
                .bucketType(bucket.getBucketType())
                .rule(bucket.getRule())
                .priority(bucket.getPriority())
                .initialBalance(bucket.getInitialBalance())
                .currentBalance(bucket.getCurrentBalance())
                .usage(bucket.getUsage())
                .carryForward(bucket.getCarryForward())
                .maxCarryForward(bucket.getMaxCarryForward())
                .totalCarryForward(bucket.getTotalCarryForward())
                .carryForwardValidity(bucket.getCarryForwardValidity())
                .timeWindow(bucket.getTimeWindow())
                .consumptionLimit(bucket.getConsumptionLimit())
                .consumptionLimitWindow(bucket.getConsumptionLimitWindow())
                .expiration(bucket.getExpiration() != null ?
                        bucket.getExpiration().format(FORMATTER) : null)
                .isUnlimited(bucket.getIsUnlimited())
                .updatedAt(bucket.getUpdatedAt() != null ?
                        bucket.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for service
     */
    public DBWriteRequestGeneric toServiceDBWriteEvent(String eventType, ServiceInstance service) {
        Map<String, Object> columnValues = buildServiceColumnValues(service);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", service.getId());
        whereConditions.put("USERNAME", service.getUsername());
        whereConditions.put("PLAN_ID", service.getPlanId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(service.getUsername())
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("SERVICE_INSTANCE")
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for bucket
     */
    public DBWriteRequestGeneric toBucketDBWriteEvent(String eventType, BucketInstance bucket, String username) {
        Map<String, Object> columnValues = buildBucketColumnValues(bucket);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", bucket.getId());
        whereConditions.put("SERVICE_ID", bucket.getServiceId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(username)
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("BUCKET_INSTANCE")
                .build();
    }

    private Map<String, Object> buildServiceColumnValues(ServiceInstance service) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", service.getId());
        columns.put("PLAN_ID", service.getPlanId());
        columns.put("PLAN_NAME", service.getPlanName());
        columns.put("PLAN_TYPE", service.getPlanType());
        columns.put("RECURRING_FLAG", service.getRecurringFlag());
        columns.put("USERNAME", service.getUsername());
        columns.put("CYCLE_START_DATE", service.getServiceCycleStartDate() != null ?
                service.getServiceCycleStartDate().format(FORMATTER) : null);
        columns.put("CYCLE_END_DATE", service.getServiceCycleEndDate() != null ?
                service.getServiceCycleEndDate().format(FORMATTER) : null);
        columns.put("NEXT_CYCLE_START_DATE", service.getNextCycleStartDate() != null ?
                service.getNextCycleStartDate().format(FORMATTER) : null);
        columns.put("SERVICE_START_DATE", service.getServiceStartDate() != null ?
                service.getServiceStartDate().format(FORMATTER) : null);
        columns.put("EXPIRY_DATE", service.getExpiryDate() != null ?
                service.getExpiryDate().format(FORMATTER) : null);
        columns.put(STATUS, service.getStatus());
        columns.put(REQUEST_ID, service.getRequestId());
        columns.put("IS_GROUP", service.getIsGroup());
        columns.put("CYCLE_DATE", service.getCycleDate());
        columns.put("BILLING", service.getBilling());
        columns.put(CREATED_AT, service.getCreatedAt() != null ?
                service.getCreatedAt().format(FORMATTER) : null);
        columns.put(UPDATED_AT, service.getUpdatedAt() != null ?
                service.getUpdatedAt().format(FORMATTER) : null);
        return columns;
    }

    private Map<String, Object> buildBucketColumnValues(BucketInstance bucket) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", bucket.getId());
        columns.put("BUCKET_ID", bucket.getBucketId());
        columns.put("SERVICE_ID", bucket.getServiceId());
        columns.put("BUCKET_TYPE", bucket.getBucketType());
        columns.put("RULE", bucket.getRule());
        columns.put("PRIORITY", bucket.getPriority());
        columns.put("INITIAL_BALANCE", bucket.getInitialBalance());
        columns.put("CURRENT_BALANCE", bucket.getCurrentBalance());
        columns.put("USAGE", bucket.getUsage());
        columns.put("CARRY_FORWARD", bucket.getCarryForward());
        columns.put("MAX_CARRY_FORWARD", bucket.getMaxCarryForward());
        columns.put("TOTAL_CARRY_FORWARD", bucket.getTotalCarryForward());
        columns.put("CARRY_FORWARD_VALIDITY", bucket.getCarryForwardValidity());
        columns.put("TIME_WINDOW", bucket.getTimeWindow());
        columns.put("CONSUMPTION_LIMIT", bucket.getConsumptionLimit());
        columns.put("CONSUMPTION_LIMIT_WINDOW", bucket.getConsumptionLimitWindow());
        columns.put("EXPIRATION", bucket.getExpiration() != null ?
                bucket.getExpiration().format(FORMATTER) : null);
        columns.put("IS_UNLIMITED", bucket.getIsUnlimited());
        columns.put(UPDATED_AT, bucket.getUpdatedAt() != null ?
                bucket.getUpdatedAt().format(FORMATTER) : null);
        return columns;
    }
    // ========== TEMPLATE EVENT MAPPING (NEW) ==========

    /**
     * Map SuperTemplate to SuperTemplateEvent
     */
    public SuperTemplateEvent toSuperTemplateEvent(SuperTemplate template) {
        return SuperTemplateEvent.builder()
                .superTemplateId(template.getId())
                .templateName(template.getTemplateName())
                .status(template.getStatus() != null ? template.getStatus().name() : null)  //      Enum as string
                .isDefault(template.getIsDefault())
                .createdBy(template.getCreatedBy())
                .createdAt(template.getCreatedAt() != null ?
                        template.getCreatedAt().format(FORMATTER) : null)  //      Date as string
                .updatedBy(template.getUpdatedBy())
                .updatedAt(template.getUpdatedAt() != null ?
                        template.getUpdatedAt().format(FORMATTER) : null)  //      Date as string
                .build();
    }

    /**
     * Map ChildTemplate to ChildTemplateEvent
     */
    public ChildTemplateEvent toChildTemplateEvent(ChildTemplate template) {
        return ChildTemplateEvent.builder()
                .childTemplateId(template.getId())
                .messageType(template.getMessageType())
                .daysToExpire(template.getDaysToExpire())
                .quotaPercentage(template.getQuotaPercentage())
                .messageContent(template.getMessageContent())
                .superTemplateId(template.getSuperTemplateId())
                .createdAt(template.getCreatedAt() != null ?
                        template.getCreatedAt().format(FORMATTER) : null)  //      Date as string
                .updatedAt(template.getUpdatedAt() != null ?
                        template.getUpdatedAt().format(FORMATTER) : null)  //      Date as string
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for SuperTemplate
     */
    public DBWriteRequestGeneric toSuperTemplateDBWriteEvent(String eventType, SuperTemplate template) {
        Map<String, Object> columnValues = buildSuperTemplateColumnValues(template);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", template.getId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(template.getUpdatedBy() != null ? template.getUpdatedBy() : template.getCreatedBy())
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("SUPER_TEMPLATE_TABLE")
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for ChildTemplate
     */
    public DBWriteRequestGeneric toChildTemplateDBWriteEvent(String eventType, ChildTemplate template, String userName) {
        Map<String, Object> columnValues = buildChildTemplateColumnValues(template);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", template.getId());
        whereConditions.put("SUPER_TEMPLATE_ID", template.getSuperTemplateId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(userName)
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("CHILD_TEMPLATE_TABLE")
                .build();
    }

    private Map<String, Object> buildSuperTemplateColumnValues(SuperTemplate template) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", template.getId());
        columns.put("TEMPLATE_NAME", template.getTemplateName());
        columns.put(STATUS, template.getStatus() != null ? template.getStatus().name() : null);  //      Enum as string
        columns.put("IS_DEFAULT", template.getIsDefault());
        columns.put(CREATED_BY, template.getCreatedBy());
        columns.put(CREATED_AT, template.getCreatedAt() != null ?
                template.getCreatedAt().format(FORMATTER) : null);  //      Date as string
        columns.put("UPDATED_BY", template.getUpdatedBy());
        columns.put(UPDATED_AT, template.getUpdatedAt() != null ?
                template.getUpdatedAt().format(FORMATTER) : null);  //      Date as string
        return columns;
    }

    private Map<String, Object> buildChildTemplateColumnValues(ChildTemplate template) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", template.getId());
        columns.put("MESSAGE_TYPE", template.getMessageType());
        columns.put("DAYS_TO_EXPIRE", template.getDaysToExpire());
        columns.put("QUOTA_PERCENTAGE", template.getQuotaPercentage());
        columns.put("MESSAGE_CONTENT", template.getMessageContent());
        columns.put("SUPER_TEMPLATE_ID", template.getSuperTemplateId());
        columns.put(CREATED_AT, template.getCreatedAt() != null ?
                template.getCreatedAt().format(FORMATTER) : null);  //      Date as string
        columns.put(UPDATED_AT, template.getUpdatedAt() != null ?
                template.getUpdatedAt().format(FORMATTER) : null);  //      Date as string
        return columns;
    }

    // ========== ADD THESE METHODS TO YOUR EXISTING EventMapper.java ==========

    /**
     * Map BngEntity to BngEvent
     */
    public BngEvent toBngEvent(BngEntity bng) {
        return BngEvent.builder()
                .bngId(bng.getBngId())
                .bngName(bng.getBngName())
                .bngIp(bng.getBngIp())
                .bngTypeVendor(bng.getBngTypeVendor())
                .modelVersion(bng.getModelVersion())
                .nasIpAddress(bng.getNasIpAddress())
                .nasIdentifier(bng.getNasIdentifier())
                .coaIp(bng.getCoaIp())
                .coaPort(bng.getCoaPort())
                .sharedSecret(bng.getSharedSecret())
                .location(bng.getLocation())
                .status(bng.getStatus())
                .createdBy(bng.getCreatedBy())
                .updatedBy(bng.getUpdatedBy())
                .createdDate(bng.getCreatedDate() != null ? bng.getCreatedDate().format(FORMATTER) : null)
                .updatedDate(bng.getUpdatedDate() != null ? bng.getUpdatedDate().format(FORMATTER) : null)
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for BNG
     */
    public DBWriteRequestGeneric toBngDBWriteEvent(String eventType, BngEntity bng) {
        Map<String, Object> columnValues = buildBngColumnValues(bng);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("BNG_ID", bng.getBngId());
        whereConditions.put("BNG_NAME", bng.getBngName());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(bng.getUpdatedBy() != null ? bng.getUpdatedBy() : bng.getCreatedBy())
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("BNG")
                .build();
    }

    private Map<String, Object> buildBngColumnValues(BngEntity bng) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("BNG_ID", bng.getBngId());
        columns.put("BNG_NAME", bng.getBngName());
        columns.put("BNG_IP", bng.getBngIp());
        columns.put("BNG_TYPE_VENDOR", bng.getBngTypeVendor());
        columns.put("MODEL_VERSION", bng.getModelVersion());
        columns.put("NAS_IP_ADDRESS", bng.getNasIpAddress());
        columns.put("NAS_IDENTIFIER", bng.getNasIdentifier());
        columns.put("COA_IP", bng.getCoaIp());
        columns.put("COA_PORT", bng.getCoaPort());
        columns.put("SHARED_SECRET", bng.getSharedSecret());
        columns.put("LOCATION", bng.getLocation());
        columns.put(STATUS, bng.getStatus());
        columns.put(CREATED_BY, bng.getCreatedBy());
        columns.put("UPDATED_BY", bng.getUpdatedBy());
        columns.put(CREATED_DATE, bng.getCreatedDate() != null ?
                bng.getCreatedDate().format(FORMATTER) : null);
        columns.put("UPDATED_DATE", bng.getUpdatedDate() != null ?
                bng.getUpdatedDate().format(FORMATTER) : null);
        return columns;
    }

    // ADD THESE METHODS TO YOUR EXISTING EventMapper.java

    // ========== ACTION LOG EVENT MAPPING ==========

    /**
     * Map ActionLog to ActionLogEvent
     */
    public ActionLogEvent toActionLogEvent(ActionLog actionLog) {
        return ActionLogEvent.builder()
                .id(actionLog.getId())
                .adminUser(actionLog.getAdminUser())
                .userName(actionLog.getUserName())
                .groupId(actionLog.getGroupId())
                .dateTime(actionLog.getDateTime() != null ?
                        actionLog.getDateTime().format(FORMATTER) : null)
                .requestId(actionLog.getRequestId())
                .action(actionLog.getAction())
                .resultCode(actionLog.getResultCode())
                .httpStatus(actionLog.getHttpStatus())
                .description(actionLog.getDescription())
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for ActionLog
     */
    public DBWriteRequestGeneric toActionLogDBWriteEvent(String eventType, ActionLog actionLog) {
        Map<String, Object> columnValues = buildActionLogColumnValues(actionLog);
        Map<String, Object> whereConditions = new HashMap<>();

        if (actionLog.getId() != null) {
            whereConditions.put("ID", actionLog.getId());
        }
        if (actionLog.getRequestId() != null) {
            whereConditions.put(REQUEST_ID, actionLog.getRequestId());
        }

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(actionLog.getUserName() != null ? actionLog.getUserName() : actionLog.getAdminUser())
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("ACTION_LOG")
                .build();
    }

    private Map<String, Object> buildActionLogColumnValues(ActionLog actionLog) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", actionLog.getId());
        columns.put("ADMIN_USER", actionLog.getAdminUser());
        columns.put(USERNAME, actionLog.getUserName());
        columns.put("GROUP_ID", actionLog.getGroupId());
        columns.put("DATE_TIME", actionLog.getDateTime() != null ?
                actionLog.getDateTime().format(FORMATTER) : null);
        columns.put(REQUEST_ID, actionLog.getRequestId());
        columns.put("ACTION", actionLog.getAction());
        columns.put("RESULT_CODE", actionLog.getResultCode());
        columns.put("HTTP_STATUS", actionLog.getHttpStatus());
        columns.put("DESCRIPTION", actionLog.getDescription());
        return columns;
    }

    // ========== VENDOR CONFIG EVENT MAPPING ==========

    /**
     * Map VendorConfig to VendorConfigEvent
     */
    public VendorConfigEvent toVendorConfigEvent(VendorConfig vendorConfig) {
        return VendorConfigEvent.builder()
                .id(vendorConfig.getId())
                .vendorId(vendorConfig.getVendorId())
                .vendorName(vendorConfig.getVendorName())
                .attributeName(vendorConfig.getAttributeName())
                .attributeId(vendorConfig.getAttributeId())
                .valuePath(vendorConfig.getValuePath())
                .entity(vendorConfig.getEntity())
                .dataType(vendorConfig.getDataType())
                .parameter(vendorConfig.getParameter())
                .isActive(vendorConfig.getIsActive())
                .attributePrefix(vendorConfig.getAttributePrefix())
                .createdDate(vendorConfig.getCreatedDate() != null ?
                        vendorConfig.getCreatedDate().format(FORMATTER) : null)
                .createdBy(vendorConfig.getCreatedBy())
                .lastUpdatedDate(vendorConfig.getLastUpdatedDate() != null ?
                        vendorConfig.getLastUpdatedDate().format(FORMATTER) : null)
                .lastUpdatedBy(vendorConfig.getLastUpdatedBy())
                .build();
    }

    /**
     * Create DBWriteRequestGeneric for VendorConfig
     */
    public DBWriteRequestGeneric toVendorConfigDBWriteEvent(String eventType, VendorConfig vendorConfig, String userName) {
        Map<String, Object> columnValues = buildVendorConfigColumnValues(vendorConfig);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", vendorConfig.getId());
        whereConditions.put("VENDOR_ID", vendorConfig.getVendorId());
        whereConditions.put("ATTRIBUTE_ID", vendorConfig.getAttributeId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(userName)
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("VENDOR_CONFIG_TABLE")
                .build();
    }

    private Map<String, Object> buildVendorConfigColumnValues(VendorConfig vendorConfig) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("ID", vendorConfig.getId());
        columns.put("VENDOR_ID", vendorConfig.getVendorId());
        columns.put("VENDOR_NAME", vendorConfig.getVendorName());
        columns.put("ATTRIBUTE_NAME", vendorConfig.getAttributeName());
        columns.put("ATTRIBUTE_ID", vendorConfig.getAttributeId());
        columns.put("VALUE_PATH", vendorConfig.getValuePath());
        columns.put("ENTITY", vendorConfig.getEntity());
        columns.put("DATA_TYPE", vendorConfig.getDataType());
        columns.put("PARAMETER", vendorConfig.getParameter());
        columns.put("IS_ACTIVE", vendorConfig.getIsActive());
        columns.put("ATTRIBUTE_PREFIX", vendorConfig.getAttributePrefix());
        columns.put(CREATED_DATE, vendorConfig.getCreatedDate() != null ?
                vendorConfig.getCreatedDate().format(FORMATTER) : null);
        columns.put(CREATED_BY, vendorConfig.getCreatedBy());
        columns.put("LAST_UPDATED_DATE", vendorConfig.getLastUpdatedDate() != null ?
                vendorConfig.getLastUpdatedDate().format(FORMATTER) : null);
        columns.put("LAST_UPDATED_BY", vendorConfig.getLastUpdatedBy());
        return columns;
    }
}