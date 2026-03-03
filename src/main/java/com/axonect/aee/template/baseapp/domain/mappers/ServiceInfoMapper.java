package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfo;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfoProjection;
import org.springframework.stereotype.Component;

@Component
public class ServiceInfoMapper {

    public ServiceInfo mapIntoServiceInfo(ServiceInfoProjection p) {


        return ServiceInfo.builder()
                .serviceId(p.getServiceId())
                .username(p.getUsername())
                .status(p.getStatus())
                .planId(p.getPlanId())
                .planType(p.getPlanType())
                .recurringFlag(p.getRecurringFlag() != null && p.getRecurringFlag() == 1)
                .nextCycleStartDate(p.getNextCycleStartDate())
                .expiryDate(p.getExpiryDate())
                .serviceStartDate(p.getServiceStartDate())
                .currentCycleStartDate(p.getCurrentCycleStartDate())
                .currentCycleEndDate(p.getCurrentCycleEndDate())
                .isGroup(p.getIsGroup() != null && p.getIsGroup() == 1)
                .cycleDate(p.getCycleDate())
                .build();
    }
}
