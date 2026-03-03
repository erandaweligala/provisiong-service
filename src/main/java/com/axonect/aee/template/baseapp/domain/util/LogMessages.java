package com.axonect.aee.template.baseapp.domain.util;

public final class LogMessages {

    private LogMessages() {
        throw new IllegalStateException("Utility class");
    }

    // User operations
    public static final String USER_CREATE_REQUEST = "Received request to create user: {}";
    public static final String USER_CREATED = "User '{}' created successfully with ID {}";
    public static final String USER_RETRIEVE = "Retrieving user by username: {}";
    public static final String USER_RETRIEVED = "User '{}' retrieved successfully";
    public static final String USER_NOT_FOUND = "User not found: {}";
    public static final String USER_UPDATE_REQUEST = "Received request to update user: {}";
    public static final String USER_UPDATED = "User '{}' updated successfully";
    public static final String USER_DELETE_REQUEST = "Received request to delete user: {} with request_id: {}";
    public static final String USER_DELETED = "User '{}' deleted successfully";
    public static final String USER_VALIDATION_ERROR_CODE = "USER_VALIDATION_ERROR";
    public static final String SERVICE_TERMINATION = "SERVICE_TERMINATION|{}|{}";


    // BNG operations
    public static final String BNG_CREATING = "Creating BNG: {} with ID: {}";
    public static final String BNG_CREATED = "BNG created successfully: {} with ID: {}";
    public static final String BNG_UPDATING = "Updating BNG with ID: {}";
    public static final String BNG_UPDATED = "BNG updated successfully: {}";
    public static final String BNG_RETRIEVING_BY_ID = "Retrieving BNG with ID: {}";
    public static final String BNG_RETRIEVING_BY_NAME = "Retrieving BNG with name: {}";
    public static final String BNG_RETRIEVED = "BNG retrieved successfully: {}";
    public static final String BNG_RETRIEVED_WITH_ID = "BNG retrieved successfully: {} (ID: {})";
    public static final String BNG_SEARCHING = "Searching BNG with filters: {}";
    public static final String BNG_FOUND_RECORDS = "Found {} BNG records";
    public static final String BNG_DUPLICATE_ID = "Duplicate BNG ID: {}";
    public static final String BNG_DUPLICATE_NAME = "Duplicate BNG name: {}";
    public static final String BNG_VALIDATION_ERROR = "Validation error: {}";
    public static final String BNG_ERROR_RETRIEVING = "Error retrieving BNG: {}";
    public static final String BNG_ERROR_SEARCHING = "Error searching BNG: {}";

    // Duplicate detection
    public static final String DUPLICATE_USER = "Duplicate user detected for username: {}";
    public static final String DUPLICATE_REQUEST_ID = "Duplicate request_id detected: {}";
    public static final String DUPLICATE_PASSWORD = "User '{}' attempted to reuse current password";

    // List operations
    public static final String RETRIEVE_ALL_USERS = "Retrieving all users - page: {}, pageSize: {}, status: {}";
    public static final String RETRIEVED_USERS_WITH_STATUS = "Retrieved {} users with status: {}";
    public static final String RETRIEVED_TOTAL_USERS = "Retrieved {} total users";
    public static final String RETRIEVE_GROUP_USERS = "Retrieving users for group_id: {} - page: {}, pageSize: {}, status: {}";
    public static final String RETRIEVED_GROUP_USERS_STATUS = "Retrieved {} users for group {} with status: {}";
    public static final String RETRIEVED_GROUP_USERS_TOTAL = "Retrieved {} total users for group {}";

    // Success codes
    public static final String SUCCESS = "AAA_200_SUCCESS";
    public static final String CREATED = "AAA_201_CREATED";

    // Success messages
    public static final String MSG_FETCH_BUCKET_DETAILS = "Bucket details fetched successfully";
    public static final String MSG_FETCH_SERVICE_INFO = "Service info fetched successfully";

    // Error codes
    public static final String ERROR_BAD_REQUEST = "AAA_400_BAD_REQUEST";
    public static final String ERROR_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    public static final String ERROR_NOT_FOUND = "AAA_404_NOT_FOUND";
    public static final String ERROR_DUPLICATE_USER = "AAA_409_DUPLICATE_USER";
    public static final String ERROR_DUPLICATE_REQ = "AAA_409_DUPLICATE_REQUEST";
    public static final String ERROR_VALIDATION_FAILED = "AAA_422_VALIDATION_FAILED";
    public static final String ERROR_INTERNAL_ERROR = "AAA_500_INTERNAL_ERROR";
    public static final String ERROR_SERVICE_UNAVAILABLE = "AAA_SERVICE_UNAVAILABLE";
    public static final String ERROR_POLICY_CONFLICT = "AAA_POLICY_CONFLICT";
    public static final String BNG_NOT_FOUND = "BNG_NOT_FOUND";
    public static final String BNG_DUPLICATE = "BNG_DUPLICATE";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String SEARCH_ERROR = "SEARCH_ERROR";
    public static final String SERVICE_OR_USER_UNAVAILABLE = "SERVICE_OR_USER_UNAVAILABLE";
    public static final String ERROR_DUPLICATE_TEMPLATE = "AAA_409_DUPLICATE_TEMPLATE";


    // Error messages
    public static final String MSG_NAS_PORT_MANDATORY = "nas_port_type is mandatory (PPPoE/IPoE)";
    public static final String MSG_NAS_PORT_INVALID = "nas_port_type must be PPPoE or IPoE";
    public static final String MSG_PASSWORD_REQUIRED_PPPOE = "Password is required when nas_port_type = PPPoE";
    public static final String MSG_MAC_REQUIRED_IPOE = "MAC address is required when nas_port_type = IPoE";
    public static final String MSG_IP_ALLOCATION_REQUIRED_IPOE = "ip_allocation is required when nas_port_type = IPoE";
    public static final String MSG_IPV4_IPV6_REQUIRED_STATIC = "IPv4 or IPv6 address is required when ip_allocation = static";
    public static final String MSG_IPV6_REQUIRED_STATIC = "IPv6 address is required when ip_allocation = static";
    public static final String MSG_IP_POOL_REQUIRED_DYNAMIC = "ip_pool_name is required when ip_allocation = Dynamic";
    public static final String MSG_BILLING_INVALID = "billing must be 1 (daily), 2 (monthly), or 3 (billing cycle)";
    public static final String MSG_CYCLE_DATE_REQUIRED = "cycle_date is required when billing = 3 (billing cycle)";
    public static final String MSG_CONCURRENCY_MIN = "concurrency must be at least 1";
    public static final String MSG_STATUS_MANDATORY = "status is mandatory (Active/Inactive)";
    public static final String MSG_REQUEST_ID_MANDATORY = "request_id is mandatory";
    public static final String MSG_BANDWIDTH_REQUIRED = "bandwidth is required when group_id is provided";
    public static final String MSG_PASSWORD_NOT_APPLICABLE_IPOE = "Password is not applicable for IPoE users";
    public static final String MSG_MAC_REQUIRED_IPOE_USER = "MAC address is required for IPoE users";
    public static final String MSG_STATUS_INVALID = "Invalid status value. Must be 'Active' or 'Inactive'";
    public static final String MSG_BUCKET_DETAILS_NOT_FOUND = "No bucket details found for serviceId: ";
    public static final String MSG_BNG_ID_EXISTS = "BNG ID '{}' already exists";
    public static final String MSG_BNG_NAME_EXISTS = "BNG name '{}' already exists";
    public static final String MSG_BNG_NOT_FOUND_ID = "BNG with ID '{}' not found";
    public static final String MSG_BNG_NOT_FOUND_NAME = "BNG with name '{}' not found";
    public static final String MSG_BNG_SEARCH_ERROR = "Error occurred while searching BNG records";
    public static final String MSG_EXPIRE_DATE_MANDATORY = "Days to Expire is Mandatory";
    public static final String MSG_QUOTA_MANDATORY = "Quota Percentage is Mandatory";
    public static final String MSG_TEMPLATE_NOT_FOUND = "Notification Template Not Found";
    public static final String MSG_INVALID_QUOTA = "Quota Percentage should be a value between 0 - 100";
    public static final String MSG_CREATED_BY_MANDATORY = "Created By is Mandatory";
    public static final String MSG_UPDATED_BY_MANDATORY = "Updated By is Mandatory";

    public static final String ERROR_FETCHING_USER_LIST = "Error occurred while fetching user list: {}";
    public static final String MSG_TEMPLATE_IN_USE = "Template is currently in use and cannot be deleted";
    public static final String MSG_CANNOT_DELETE_DEFAULT = "Cannot delete default template";

    // Redis operations
    public static final String REDIS_SERVICE_TTL_SET = "Redis TTL set for service ID: {}, TTL: {}ms";
    public static final String REDIS_SERVICE_TTL_SKIPPED = "Skipped Redis TTL for service ID: {} — expiry date is in the past or null";
    public static final String REDIS_SERVICE_TTL_FAILED = "Failed to store TTL in Redis for service ID: {} — non-blocking";
    public static final String REDIS_SERVICE_TTL_SET_CODE = "REDIS_SERVICE_TTL_SET";
    public static final String REDIS_SERVICE_TTL_ERROR_CODE = "REDIS_TTL_ERROR";

    



}