package com.axonect.aee.template.baseapp.domain.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishResult {
    private boolean Success;
    private String Error;
    private long LatencyMs;

    public boolean isOverallSuccess() {
        return Success; // At least one succeeded
    }

    public boolean isSuccess() {
        return Success;
    }

    public boolean isCompleteFailure() {
        return !Success;
    }
}