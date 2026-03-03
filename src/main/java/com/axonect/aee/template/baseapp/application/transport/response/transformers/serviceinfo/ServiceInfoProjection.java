package com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo;

import java.time.LocalDateTime;

public interface ServiceInfoProjection {

    String getServiceId();
    String getUsername();
    String getStatus();
    String getPlanId();
    String getPlanType();
    Integer getRecurringFlag();
    LocalDateTime getNextCycleStartDate();
    LocalDateTime getExpiryDate();
    LocalDateTime getServiceStartDate();
    LocalDateTime getCurrentCycleStartDate();
    LocalDateTime getCurrentCycleEndDate();
    Integer getIsGroup();
    Integer getCycleDate();
}
