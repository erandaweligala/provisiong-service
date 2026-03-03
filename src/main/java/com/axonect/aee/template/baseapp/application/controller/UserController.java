    package com.axonect.aee.template.baseapp.application.controller;

    import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.DeleteUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateRequestDTO;
    import com.axonect.aee.template.baseapp.application.transport.request.entities.UpdateUserRequest;
    import com.axonect.aee.template.baseapp.application.transport.response.transformers.*;
    import com.axonect.aee.template.baseapp.domain.service.ServiceProvisioningService;
    import com.axonect.aee.template.baseapp.domain.service.UserProvisioningService;
    import com.axonect.aee.template.baseapp.domain.util.LoggableAction;
    import com.fasterxml.jackson.annotation.JsonInclude;
    import jakarta.validation.Valid;
    import lombok.AllArgsConstructor;
    import lombok.Getter;
    import lombok.RequiredArgsConstructor;
    import lombok.Setter;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;

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

        /**
         * Endpoint to create a new user.
         *
         * @param request The user creation request payload.
         * @return ResponseEntity containing API response with created user.
         */
        @LoggableAction
        @PostMapping
        public ResponseEntity<ApiResponse> createUser(@Valid @RequestBody CreateUserRequest request) throws Exception {
            long startTime = System.currentTimeMillis();
            log.info("Received user creation request for username: {}", request.getUserName());
            // Delegate creation logic to UserService
            CreateUserResponse response = userProvisioningService.createUser(request);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Received user creation complete: {}", duration);


            // Return a standardized API response
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
        public ResponseEntity<ApiResponse> getUser(@PathVariable("user_name") String userName) {
            long startTime = System.currentTimeMillis();

            try {
                GetUserResponse getUserResponse = userProvisioningService.getUserByUserName(userName);
                return ResponseEntity.ok(
                        ApiResponse.success(getUserResponse)
                );
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info("Execution time for GET /{} = {} ms", userName, duration);
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
        public ResponseEntity<ApiResponse> getServiceDetails(@PathVariable("serviceLineNumber") String userName) {
            long startTime = System.currentTimeMillis();

            try {
                ServiceLineResponse getUserResponse = userProvisioningService.getServiceDetailsByUsername(userName);
                return ResponseEntity.ok(
                        ApiResponse.success(getUserResponse)
                );
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info("Execution time for GET /{} = {} ms", userName, duration);
            }
        }

        /**
         * Get a simple list of all usernames (no pagination)
         *
         * Example: GET /api/user/list
         */
        @GetMapping("/list")
        public ResponseEntity<ApiResponse> getUserList() {
            long startTime = System.currentTimeMillis();

            try {
                List<UserListResponse> userList = userProvisioningService.getUserList();
                return ResponseEntity.ok(
                        ApiResponse.success("User list retrieved successfully", userList)
                );
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info("Execution time for GET /list = {} ms", duration);
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
                @RequestParam(value = "group_id", required = false) String groupId) {

            long startTime = System.currentTimeMillis();

            try {
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
                log.info(
                        "Execution time for GET /users?page={}&pageSize={}&status={}&userName={}&subscription={}&groupId={} = {} ms",
                        page, pageSize, status, userName, subscription, groupId, duration
                );
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
                @Valid @RequestBody UpdateUserRequest request) {

            long startTime = System.currentTimeMillis();

            try {
                UpdateUserResponse updateResponse = userProvisioningService.updateUser(userName, request);
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
                log.info("Execution time for PATCH /{} = {} ms", userName, duration);
            }
        }




        @LoggableAction
        @DeleteMapping(value = "/{user_name}", consumes = "application/json")
        public ResponseEntity<ApiResponse> deleteUser(
                @PathVariable("user_name") String userName,
                @Valid @RequestBody DeleteUserRequest request) {

            long startTime = System.currentTimeMillis();

            try {
                // Perform deletion
                userProvisioningService.deleteUser(userName, request.getRequestId());

                return ResponseEntity.ok(
                        ApiResponse.success(
                                request.getRequestId(),
                                "AAA_200_SUCCESS",
                                "User " + userName + " has been deleted successfully.",
                                null
                        )
                );

            } finally {
                long duration = System.currentTimeMillis() - startTime;
                log.info("Execution time for DELETE /{} = {} ms", userName, duration);
            }
        }



        @LoggableAction
        @GetMapping("/group/{group_id}")
        public ResponseEntity<ApiResponse> getUsersByGroup(
                @PathVariable("group_id") String groupId,
                @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                @RequestParam(value = "page_size", required = false, defaultValue = "50") Integer pageSize,
                @RequestParam(value = "status", required = false) Integer status) {

            long startTime = System.currentTimeMillis();

            try {
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
                log.info(
                        "Execution time for GET /group/{}?page={}&pageSize={}&status={} = {} ms",
                        groupId, page, pageSize, status, duration
                );
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
                @PathVariable("request_id") String requestId,
                @Valid @RequestBody UpdateRequestDTO updateDto) {

            log.info("Received service update request for user: {}, plan: {}, request: {}",
                    userId, planId, requestId);

            UpdateResponseDTO response =
                    serviceProvisioningService.updateService(userId, planId, requestId, updateDto);

            return ResponseEntity.ok(
                    ApiResponse.success(
                            requestId,
                            "AAA_200_SUCCESS",
                            "Service updated successfully",
                            response
                    )
            );
        }


        @LoggableAction
        @DeleteMapping({
                "/{user_id}/services/{plan_id}/{request_id}",
                "/services/{user_id}/{plan_id}/{request_id}"
        })
        public ResponseEntity<ApiResponse> deleteService(
                @PathVariable("user_id") String userId,
                @PathVariable("plan_id") String planId,
                @PathVariable("request_id") String requestId) {

            log.info("Received service delete request for user: {}, plan: {}, request: {}",
                    userId, planId, requestId);

            // Perform deletion
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
        }

    }