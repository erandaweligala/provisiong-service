package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BucketInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.ServiceInstanceRepository;
import com.axonect.aee.template.baseapp.application.repository.UserRepository;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.BaseResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.PageDetails;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.*;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.mappers.ServiceInfoMapper;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BucketInfoService {

    @Autowired
    BucketInstanceRepository bucketInstanceRepository;
    @Autowired
    ServiceInstanceRepository serviceInstanceRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ServiceInfoMapper mapper;


    public BaseResponse<List<ServiceInfo>> getServiceInfo(
            String username,
            String serviceId,
            String status,
            String planId,
            String planType,
            Boolean recurringFlag,
            Boolean isGroup,
            int page,
            int pageSize) throws AAAException {

        log.info("Fetching service info for username={}, serviceId={}, status={}, planId={}, planType={}, recurringFlag={}, isGroup={}, page={}, pageSize={}",
                username, serviceId, status, planId, planType, recurringFlag, isGroup, page, pageSize);

        try {
            // Get groupId
            String groupId = userRepository.findGroupIdByUsername(username);
            log.debug("Fetched groupId={} for username={}", groupId, username);

            List<String> usernames = new ArrayList<>();
            if (isGroup == null || isGroup)
                if (groupId != null && !groupId.equalsIgnoreCase(username)) {
                    usernames.add(groupId);
                    log.debug("Including groupId in search usernames list: {}", groupId);
                }
            if (isGroup == null || !isGroup) {
                usernames.add(username);
                log.debug("Including main username in search usernames list: {}", username);
            }
            Pageable pageable = PageRequest.of(page - 1, pageSize);

            Page<ServiceInfoProjection> resultPage = serviceInstanceRepository.searchServiceInfo(usernames, serviceId, status, planId, planType, recurringFlag, pageable);
            log.info("Fetched {} service records from repository for usernames={}", resultPage.getNumberOfElements(), usernames);

            if (!resultPage.hasContent()) {
                log.warn("No service info found for usernames={}", usernames);
                throw new AAAException(
                        LogMessages.ERROR_NOT_FOUND,
                        LogMessages.MSG_BUCKET_DETAILS_NOT_FOUND + serviceId,
                        HttpStatus.NOT_FOUND
                );
            }

            List<ServiceInfo> serviceInfoList = resultPage.getContent().stream()
                    .map(row -> {
                        ServiceInfo info = mapper.mapIntoServiceInfo(row);
                        log.debug("Mapped ServiceInfo: {}", info);
                        return info;
                    })
                    .toList();

            PageDetails pageDetails = new PageDetails();
            pageDetails.setPageNumber(page);
            pageDetails.setPageElementCount(serviceInfoList.size());
            pageDetails.setTotalRecords(resultPage.getTotalElements());

            log.info("Returning {} service info records with totalRecords={}", serviceInfoList.size(), resultPage.getTotalElements());

            return new BaseResponse<>(
                    LogMessages.SUCCESS,
                    LogMessages.MSG_FETCH_SERVICE_INFO,
                    serviceInfoList,
                    pageDetails
            );
        } catch (AAAException ex) {
            log.error("AAAException while fetching service info: code={}, message={}", ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error while fetching service info for username={}", username, ex);
            throw new AAAException(
                    LogMessages.ERROR_INTERNAL_ERROR,
                    "Internal server error while fetching service info",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }

    }


    public BaseResponse<List<BucketInfo>> getBucketInfoByServiceId(Long serviceId) throws AAAException {

        {
            try {
                log.info("Fetching bucket info with speed for serviceId={}", serviceId);

                List<BucketInfoProjection> results =
                        bucketInstanceRepository.findBucketInfoWithSpeed(serviceId);

                if (results.isEmpty()) {
                    log.warn("No bucket info found for serviceId={}", serviceId);
                    throw new AAAException(
                            LogMessages.ERROR_NOT_FOUND,
                            LogMessages.MSG_BUCKET_DETAILS_NOT_FOUND + serviceId,
                            HttpStatus.NOT_FOUND
                    );
                }

                List<BucketInfo> bucketInfoList = results.stream()
                        .map(p -> new BucketInfo(
                                p.getBucketId(),
                                p.getInitialBalance(),
                                p.getUsage(),
                                p.getCurrentBalance(),
                                p.getPriority(),
                                new SpeedDto(
                                        p.getUplinkSpeed(),
                                        p.getDownlinkSpeed()
                                ),
                                p.getExpiration()
                        ))
                        .toList();

                return new BaseResponse<>(
                        LogMessages.SUCCESS,
                        LogMessages.MSG_FETCH_BUCKET_DETAILS,
                        bucketInfoList,
                        null
                );
            } catch (AAAException ex) {
                log.error("AAAException while fetching bucket info for Service Id: {} - Code: {}, Message: {}",
                        serviceId, ex.getCode(), ex.getMessage(), ex);
                throw ex;
            } catch (Exception ex) {
                log.error("Unexpected error while fetching bucket info for Service Id: {}",
                        serviceId, ex);
                throw new AAAException(
                        LogMessages.ERROR_INTERNAL_ERROR,
                        "Internal server error while fetching bucket info",
                        HttpStatus.INTERNAL_SERVER_ERROR
                );
            }
        }
    }

}
