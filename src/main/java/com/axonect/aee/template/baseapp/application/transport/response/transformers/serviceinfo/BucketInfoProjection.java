package com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo;

import java.time.LocalDateTime;

public interface BucketInfoProjection {
    String getBucketId();
    Long getInitialBalance();
    Long getUsage();
    Long getCurrentBalance();
    Long getPriority();

    String getUplinkSpeed();
    String getDownlinkSpeed();
    LocalDateTime getExpiration();
}
