package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.BalanceWrapper;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.ServiceInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountingCacheManagementService {

    private final WebClient cacheApiWebClient;

    @Value("${cache.api.base-url}")
    private String cacheApiUrl;

    @Value("${cache.api.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${cache.api.retry.initial-backoff:2}")
    private int initialBackoffSeconds;

    @Value("${cache.api.retry.max-backoff:10}")
    private int maxBackoffSeconds;

    /**
     * Async fire-and-forget sync of all buckets as a single BalanceWrapper request.
     * Uses reactive subscribe() so the call returns immediately without blocking the caller,
     * ensuring it does not impact the response time of Activate Service or Update Service.
     */
    public void syncBuckets(List<BucketInstance> bucketInstances,
                            String sessionTimeout, long concurrency, ServiceInstance serviceInstance) {

        if (!StringUtils.hasText(serviceInstance.getUsername())) {
            log.error("Bucket username is null or empty, skipping bucket sync");
            return;
        }

        if (bucketInstances == null || bucketInstances.isEmpty()) {
            log.warn("No bucket instances provided for user: {}, skipping bucket sync", serviceInstance.getUsername());
            return;
        }

        List<Balance> balanceList = bucketInstances.stream()
                .map(b -> buildBalance(b, serviceInstance))
                .toList();

        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setSessionTimeOut(sessionTimeout);
        wrapper.setConcurrency(concurrency);
        wrapper.setBalance(balanceList);

        String fullUrl = cacheApiUrl.replace("{bucketUsername}", serviceInstance.getUsername());

        log.info("Dispatching async bucket sync for user: {}, bucket count: {}", serviceInstance.getUsername(), bucketInstances.size());

        cacheApiWebClient
                .patch()
                .uri(fullUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wrapper)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(Retry.backoff(maxRetryAttempts, Duration.ofSeconds(initialBackoffSeconds))
                        .maxBackoff(Duration.ofSeconds(maxBackoffSeconds))
                        .filter(this::shouldRetry)
                        .doBeforeRetry(signal ->
                                log.warn("Retrying bucket sync for user: {}, attempt: {}/{}",
                                        serviceInstance.getUsername(), signal.totalRetries() + 1, maxRetryAttempts)))
                .subscribe(
                        r -> log.info("Async bucket sync completed successfully for user: {}", serviceInstance.getUsername()),
                        e -> log.error("Async bucket sync failed for user: {}", serviceInstance.getUsername(), e));
    }

    private Balance buildBalance(BucketInstance instance, ServiceInstance serviceInstance) {
        Balance balance = new Balance();
        balance.setInitialBalance(instance.getInitialBalance() != null ? instance.getInitialBalance() : 0L);
        balance.setQuota(instance.getCurrentBalance() != null ? instance.getCurrentBalance() : 0L);
        balance.setServiceExpiry(serviceInstance.getExpiryDate());
        balance.setBucketExpiryDate(instance.getExpiration());
        balance.setBucketId(instance.getBucketId());
        balance.setServiceId(String.valueOf(instance.getServiceId()));
        balance.setPriority(instance.getPriority());
        balance.setServiceStartDate(serviceInstance.getServiceStartDate());
        balance.setServiceStatus(serviceInstance.getStatus());
        balance.setTimeWindow(instance.getTimeWindow());
        balance.setConsumptionLimit(instance.getConsumptionLimit());
        balance.setConsumptionLimitWindow(parseConsumptionLimitWindow(instance.getConsumptionLimitWindow()));
        balance.setBucketUsername(serviceInstance.getUsername());
        balance.setUnlimited(instance.getIsUnlimited());
        balance.setGroup(serviceInstance.getIsGroup());
        balance.setUsage(instance.getUsage());
        return balance;
    }

    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return status >= 500 || status == 429 || status == 408;
        }
        return throwable instanceof java.util.concurrent.TimeoutException
                || throwable instanceof java.io.IOException;
    }

    private Long parseConsumptionLimitWindow(String window) {
        if (!StringUtils.hasText(window)) {
            return 24L;
        }
        try {
            long parsed = Long.parseLong(window.trim());
            return parsed > 0 ? parsed : 24L;
        } catch (NumberFormatException e) {
            log.warn("Invalid consumption limit window: {}, using default 24", window);
            return 24L;
        }
    }
}
