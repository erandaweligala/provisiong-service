package com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BucketInfo {
    private String bucketId;
    private Long initialQuota;
    private Long usedQuota;
    private Long remainingQuota;
    private Long priority;
    private SpeedDto speed;
    private LocalDateTime expiration;
}
