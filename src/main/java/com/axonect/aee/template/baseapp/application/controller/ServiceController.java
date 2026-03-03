package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.request.entities.ActiveServiceRequestDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ActiveServiceResponseDTO;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ApiResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.BaseResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.BucketInfo;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfo;
import com.axonect.aee.template.baseapp.domain.service.BucketInfoService;
import com.axonect.aee.template.baseapp.domain.service.ServiceProvisioningService;
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
 * REST controller for managing Service operations.
 */
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@Slf4j
public class ServiceController {

    private final ServiceProvisioningService serviceProvisioningService;
    private final BucketInfoService bucketInfoService;

    /**
     * Endpoint to activate a service for a given plan.
     *
     * @param request The activation request payload.
     * @return ResponseEntity containing standardized API response.
     */
    @LoggableAction
    @PostMapping("/activate")
    public ResponseEntity<ApiResponse> activateService(
            @Valid @RequestBody ActiveServiceRequestDTO request) {

        log.info("Received service activation request for Plan ID: {}", request.getPlanId());

        ActiveServiceResponseDTO response = serviceProvisioningService.activateService(request);

        return ResponseEntity.ok(
                ApiResponse.success(
                        request.getRequestId(),
                        "AAA_200_SUCCESS",
                        "Service activated successfully",
                        response
                )
        );
    }





    /**
     * Endpoint to retrieve bucket and speed details for a given service.
     * <p>
     * This API returns quota-related information along with QoS speed details
     * for all buckets attached to the specified service ID.
     *
     * @param serviceId The service identifier used to fetch associated bucket information.
     * @return ResponseEntity containing a BaseResponse with a list of BucketInfo objects.
     */
    @GetMapping("/bucket-info/{serviceId}")
    public ResponseEntity<BaseResponse<List<BucketInfo>>> getBucketInfo(@PathVariable Long serviceId) {

        log.info("Received request to fetch bucket info for serviceId={}", serviceId);

        BaseResponse<List<BucketInfo>> response = bucketInfoService.getBucketInfoByServiceId(serviceId);

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to retrieve service information for a user.
     *
     * <p>This API returns service data filtered by username
     * plus all services belonging to the user's group if applicable.</p>
     *
     * @param username       mandatory username
     * @param serviceId      optional filter
     * @param status         optional filter
     * @param planId         optional filter
     * @param planType       optional filter
     * @param recurringFlag  optional filter
     * @param page           page index
     * @param pageSize       page size
     * @return BaseResponse containing paginated list of ServiceInfo
     */
    @GetMapping("/service-info/filter")
    public ResponseEntity<BaseResponse<List<ServiceInfo>>> getServiceInfo(
            @RequestParam String username,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planId,
            @RequestParam(required = false) String planType,
            @RequestParam(required = false) Boolean recurringFlag,
            @RequestParam(required = false) Boolean isGroup,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        BaseResponse<List<ServiceInfo>> response =
                bucketInfoService.getServiceInfo(username, serviceId, status, planId, planType, recurringFlag, isGroup, page, pageSize);

        return ResponseEntity.ok(response);
    }



}
