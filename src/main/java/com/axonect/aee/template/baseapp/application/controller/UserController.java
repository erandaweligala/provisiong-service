    package com.axonect.aee.template.baseapp.application.controller;

    import com.axonect.aee.template.baseapp.application.constants.LoggingAdviceConstants;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.DeleteUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateRequestDTO;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
    import com.axonect.aee.template.baseapp.domain.service.ServiceProvisioningService;
    import com.axonect.aee.template.baseapp.domain.service.UserProvisioningService;
    import com.axonect.aee.template.baseapp.domain.util.LoggableAction;
    import com.fasterxml.jackson.annotation.JsonInclude;
    import com.fasterxml.jackson.core.JsonProcessingException;
    import com.fasterxml.jackson.databind.ObjectMapper;
    import jakarta.servlet.http.HttpServletRequest;
    import jakarta.validation.Valid;
    import jakarta.validation.constraints.Pattern;
    import lombok.AllArgsConstructor;
    import lombok.Getter;
    import lombok.RequiredArgsConstructor;
    import lombok.Setter;
    import lombok.extern.java.Log;
    import lombok.extern.slf4j.Slf4j;
    import org.slf4j.MDC;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;
    import java.util.Map;
    import java.util.UUID;

    import static com.axonect.aee.template.baseapp.domain.util.Constants.USERNAME;

    /**
     * REST controller for managing User operations.
     */
    @RestController
    @RequestMapping("/api/user")
    @RequiredArgsConstructor
    @Slf4j
    public class UserController {


        private final UserProvisioningService userProvisioningService;
        private final ServiceProvisioningService serviceProvisioningService;
        ObjectMapper mapper = new ObjectMapper();

        /**
         * Endpoint to create a new user.
         *
         * @param request The user creation request payload.
         * @return ResponseEntity containing API response with created user.
         */
        @LoggableAction
        @PostMapping
        public ResponseEntity<ApiResponse> createUser(@Valid @RequestBody CreateUserRequest request, HttpServletRequest httpServletRequest) throws Exception,JsonProcessingException {
            long startTime = System.currentTimeMillis();
            final String requestId = request.getRequestId();
            MDC.put(LoggingAdviceConstants.REQUEST_ID, requestId);
            MDC.put(LoggingAdviceConstants.USERNAME, request.getUserName());
            log.info(LoggingAdviceConstants.REQUEST_INITIATED,"POST",httpServletRequest.getRequestURI(),mapper.writeValueAsString(request));

            // Delegate creation logic to UserService
            CreateUserResponse response = userProvisioningService.createUser(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User creation completed");

            return ResponseEntity.ok(
                    ApiResponse.success(
                            request.getRequestId(),
                            "AAA_201_CREATED",
                            "User created successfully",
                            response
                    )
            );
        }

        /**
         * Endpoint to retrieve a specific user by username.
         *
         * @param userName The username (path parameter).
         * @return ResponseEntity containing API response with user details.
         */
        @LoggableAction
        @GetMapping("/{user_name}")
        public ResponseEntity<ApiResponse> getUser(@PathVariable("user_name") String userName, HttpServletRequest httpServletRequest) throws JsonProcessingException {
            long startTime = System.currentTimeMillis();
            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                MDC.put(LoggingAdviceConstants.USERNAME, userName);
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"GET",httpServletRequest.getRequestURI());

                GetUserResponse getUserResponse = userProvisioningService.getUserByUserName(userName);

                return ResponseEntity.ok(
                        ApiResponse.success(getUserResponse)
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User retrieval request terminated");
                MDC.clear();
            }
        }

        /**
         * Endpoint to retrieve a service details by username.
         *
         * @param userName The username (path parameter).
         * @return ResponseEntity containing API response with user details.
         */
        @LoggableAction
        @GetMapping("service-lines/status/{serviceLineNumber}")
        public ResponseEntity<ApiResponse> getServiceDetails(@PathVariable("serviceLineNumber") String userName, HttpServletRequest httpServletRequest){
            long startTime = System.currentTimeMillis();
            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                MDC.put(LoggingAdviceConstants.USERNAME, userName);
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"GET",httpServletRequest.getRequestURI());

                ServiceLineResponse getUserResponse =
                        userProvisioningService.getServiceDetailsByUsername(userName);

                return ResponseEntity.ok(
                        ApiResponse.success(getUserResponse)
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"Service retrieval request terminated");
                MDC.clear();
            }
        }

        /**
         * Get a simple list of all usernames (no pagination)
         *
         * Example: GET /api/user/list
         */
        @LoggableAction
        @GetMapping("/list")
        public ResponseEntity<ApiResponse> getUserList(HttpServletRequest httpServletRequest) {
            long startTime = System.currentTimeMillis();
            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                log.info(LoggingAdviceConstants.REQUEST_INITIATED, "GET", httpServletRequest.getRequestURI());
                List<UserListResponse> userList = userProvisioningService.getUserList();
                return ResponseEntity.ok(
                        ApiResponse.success("User list retrieved successfully", userList)
                );
            } finally {
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED, System.currentTimeMillis() - startTime, "User List retrieval request terminated");
                MDC.clear();
            }
        }


        /**
         * Endpoint to retrieve all users with optional pagination and filtering.
         *
         * @param page Optional page number (default: 1).
         * @param pageSize Optional number of users per page (default: 50).
         * @param status Optional status filter (Active/Inactive).
         * @param userName Optional username filter.
         * @param groupId Optional group ID filter.
         * @param subscription Optional subscription filter.
         * @return ResponseEntity containing API response with paged user list.
         */
        @LoggableAction
        @GetMapping
        public ResponseEntity<ApiResponse> getAllUsers(
                @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                @RequestParam(value = "page_size", required = false, defaultValue = "50") Integer pageSize,
                @RequestParam(value = "status", required = false) Integer status,
                @RequestParam(value = "user_name", required = false) String userName,
                @RequestParam(value = "subscription", required = false) Integer subscription,
                @RequestParam(value = "group_id", required = false) String groupId,
                HttpServletRequest httpServletRequest) {

            long startTime = System.currentTimeMillis();

            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID,httpServletRequest.getRequestId());
                MDC.put("page", String.valueOf(page));
                MDC.put("pageSize", String.valueOf(pageSize));
                if (status != null) MDC.put("status", String.valueOf(status));
                if (userName != null) MDC.put("userName", userName);
                if (subscription != null) MDC.put("subscription", String.valueOf(subscription));
                if (groupId != null) MDC.put("groupId", groupId);
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"GET",httpServletRequest.getRequestURI());

                PagedUserResponse pagedResponse = userProvisioningService.getAllUsers(
                        page, pageSize, status, userName, subscription, groupId
                );

                return ResponseEntity.ok(
                        ApiResponse.success(
                                "User list retrieved successfully",
                                pagedResponse
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User retrieval request terminated");
                MDC.clear();
            }
        }




        /**
         * Endpoint to update an existing user.
         * Only the fields provided in the request body will be updated.
         *
         * @param userName The username (path parameter).
         * @param request The user update request payload.
         * @return ResponseEntity containing API response with updated user details.
         */
        @LoggableAction
        @PatchMapping("/{user_name}")
        public ResponseEntity<ApiResponse> updateUser(
                @PathVariable("user_name") String userName,
                @Valid @RequestBody UpdateUserRequest request,HttpServletRequest httpServletRequest) throws JsonProcessingException {

            long startTime = System.currentTimeMillis();

            try {
                MDC.put(LoggingAdviceConstants.REQUEST_ID, request.getRequestId());
                MDC.put(LoggingAdviceConstants.USERNAME, userName);
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"PATCH",httpServletRequest.getRequestURI(),mapper.writeValueAsString(request));

                UpdateUserResponse updateResponse =
                        userProvisioningService.updateUser(userName, request);

                return ResponseEntity.ok(
                        ApiResponse.success(
                                request.getRequestId(),
                                "AAA_200_SUCCESS",
                                "User information updated successfully",
                                updateResponse
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User update request terminated");
                MDC.clear();
            }
        }

        @LoggableAction
        @DeleteMapping(value = "/{user_name}", consumes = "application/json")
        public ResponseEntity<ApiResponse> deleteUser(
                @PathVariable("user_name") String userName,
                @Valid @RequestBody DeleteUserRequest request,HttpServletRequest httpServletRequest) throws JsonProcessingException{

            long startTime = System.currentTimeMillis();

            try {
                String requestId = request.getRequestId() != null
                        ? request.getRequestId()
                        : UUID.randomUUID().toString();

                MDC.put(LoggingAdviceConstants.REQUEST_ID, requestId);
                MDC.put(LoggingAdviceConstants.USERNAME, userName);
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"DELETE",httpServletRequest.getRequestURI(),mapper.writeValueAsString(request));

                // Perform deletion
                userProvisioningService.deleteUser(userName, requestId);

                return ResponseEntity.ok(
                        ApiResponse.success(
                                requestId,
                                "AAA_200_SUCCESS",
                                "User " + userName + " has been deleted successfully.",
                                null
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User delete request terminated");
                MDC.clear();
            }
        }



        @LoggableAction
        @GetMapping("/group/{group_id}")
        public ResponseEntity<ApiResponse> getUsersByGroup(
                @PathVariable("group_id") String groupId,
                @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                @RequestParam(value = "page_size", required = false, defaultValue = "50") Integer pageSize,
                @RequestParam(value = "status", required = false) Integer status,
                HttpServletRequest httpServletRequest) {

            long startTime = System.currentTimeMillis();

            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID,httpServletRequest.getRequestId());
                MDC.put("groupId", groupId);
                MDC.put("page", String.valueOf(page));
                MDC.put("pageSize", String.valueOf(pageSize));
                if (status != null) {
                    MDC.put("status", String.valueOf(status));
                }
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"GET",httpServletRequest.getRequestURI());

                PagedGroupUsersResponse pagedResponse =
                        userProvisioningService.getUsersByGroupId(groupId, page, pageSize, status);

                return ResponseEntity.ok(
                        ApiResponse.success(
                                "Group users retrieved successfully",
                                pagedResponse
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User retrieval by group ID request terminated");
                MDC.clear();
            }
        }



        @LoggableAction
        @PatchMapping({
                "/{user_id}/services/{plan_id}/{request_id}",
                "/services/{user_id}/{plan_id}/{request_id}"
        })
        public ResponseEntity<ApiResponse> updateService(
                @PathVariable("user_id") String userId,
                @PathVariable("plan_id") String planId,
                @PathVariable("request_id")
                @Pattern(
                        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                        message = "request_id must be a valid UUID format (e.g. 550e8400-e29b-41d4-a716-446655440000)"
                ) String requestId,
                @Valid @RequestBody UpdateRequestDTO updateDto,HttpServletRequest httpServletRequest) throws JsonProcessingException{

            long startTime = System.currentTimeMillis();

            try {

                String finalRequestId = (requestId != null && !requestId.isBlank())
                        ? requestId
                        : java.util.UUID.randomUUID().toString();


                MDC.put(LoggingAdviceConstants.REQUEST_ID, finalRequestId);
                MDC.put(LoggingAdviceConstants.USERNAME, userId);
                MDC.put(LoggingAdviceConstants.PLAN_ID, planId);
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"PATCH", httpServletRequest.getRequestURI(),mapper.writeValueAsString(updateDto));

                UpdateResponseDTO response =
                        serviceProvisioningService.updateService(userId, planId, finalRequestId, updateDto);

                return ResponseEntity.ok(
                        ApiResponse.success(
                                finalRequestId,
                                "AAA_200_SUCCESS",
                                "Service updated successfully",
                                response
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User update request terminated");
                MDC.clear();
            }
        }


        @LoggableAction
        @DeleteMapping({
                "/{user_id}/services/{plan_id}/{request_id}",
                "/services/{user_id}/{plan_id}/{request_id}"
        })
        public ResponseEntity<ApiResponse> deleteService(
                @PathVariable("user_id") String userId,
                @PathVariable("plan_id") String planId,
                @PathVariable("request_id")
                @Pattern(
                        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                        message = "request_id must be a valid UUID format (e.g. 550e8400-e29b-41d4-a716-446655440000)"
                )
                String requestId, HttpServletRequest httpServletRequest) {

            long startTime = System.currentTimeMillis();

            try {

                MDC.put(LoggingAdviceConstants.REQUEST_ID, requestId);
                MDC.put(LoggingAdviceConstants.USERNAME, userId);
                MDC.put(LoggingAdviceConstants.PLAN_ID, planId);
                log.info(LoggingAdviceConstants.REQUEST_INITIATED,"DELETE",httpServletRequest.getRequestURI());

                DeleteResponseDTO response =
                        serviceProvisioningService.deleteService(userId, planId, requestId);

                return ResponseEntity.ok(
                        ApiResponse.success(
                                requestId,
                                "AAA_200_SUCCESS",
                                "Service deleted successfully",
                                response
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED,duration,"User delete request terminated");
                MDC.clear();
            }
        }
        @LoggableAction
        @GetMapping("/status/counts")
        public ResponseEntity<ApiResponse> getUserStatusCounts(HttpServletRequest httpServletRequest) {
            long startTime = System.currentTimeMillis();
            try {
                MDC.put(LoggingAdviceConstants.TRACE_ID, httpServletRequest.getRequestId());
                log.info(LoggingAdviceConstants.REQUEST_INITIATED, "GET", httpServletRequest.getRequestURI());
                Map<String, Long> counts = userProvisioningService.getUserStatusCounts();
                return ResponseEntity.ok(ApiResponse.success("User status counts retrieved", counts));
            } finally {
                log.info(LoggingAdviceConstants.REQUEST_TERMINATED, System.currentTimeMillis() - startTime, "User status counts request terminated");
                MDC.clear();
            }
        }

    }