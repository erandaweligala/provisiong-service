package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoAManagementService {

    private final WebClient accountingApiWebClient;

    // FIX #2: Removed @Value("${accounting.service.url}") — the base URL is now
    //         set on the WebClient bean in WebClientConfig, so injecting it here
    //         was redundant. The old "fullUrl" variable built from it was never
    //         actually used in the .uri() call anyway.

    @Value("${accounting.service.enabled:true}")
    private boolean accountingServiceEnabled;

    @Value("${accounting.service.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${accounting.service.retry.initial-backoff:1000}")
    private int initialBackoffMillis;

    @Value("${accounting.service.retry.max-backoff:5000}")
    private int maxBackoffMillis;

    /**
     * Sends CoA request to Accounting Service when user status changes.
     *
     * Calls: PATCH /patchStatus/{userName}/{status}
     * Status values sent as: ACTIVE, BARRED, INACTIVE (all capitals)
     *
     * @param userName  The username
     * @param oldStatus The previous status
     * @param newStatus The new status
     */
    public void sendCoARequest(String userName, UserStatus oldStatus, UserStatus newStatus) {

        // Check if accounting service is enabled
        if (!accountingServiceEnabled) {
            log.warn("Accounting service is disabled. Skipping CoA request for user '{}'", userName);
            return;
        }

        // Skip if status didn't actually change
        if (oldStatus == newStatus) {
            log.debug("Status unchanged for user '{}', skipping CoA request", userName);
            return;
        }

        // IMPORTANT: Skip if old status is INACTIVE (status code 3)
        // We don't send CoA for transitions FROM inactive state
        // CORRECT: Only skip transitions FROM INACTIVE
        if (oldStatus == UserStatus.INACTIVE) {
            log.info("User '{}' transitioning FROM INACTIVE to {}. No CoA request needed.",
                    userName, newStatus);
            return;
        }

        log.info("Sending CoA request for user '{}': {} -> {}", userName, oldStatus, newStatus);

        try {
            // Convert status enum to uppercase string (ACTIVE, BARRED, INACTIVE)
            String statusString = newStatus.name();

            // Build relative endpoint — base URL is already on the WebClient bean
            String endpoint = String.format("/patchStatus/%s/%s", userName, statusString);

            log.debug("Making PATCH request to Accounting Service endpoint: {}", endpoint);

            // FIX #3: Removed .timeout() Reactor operator — timeout is now handled
            //         solely by ReadTimeoutHandler on the HttpClient (set in WebClientConfig).
            //         Keeping both caused two competing timeout mechanisms; whichever fired
            //         first would determine the exception type, making shouldRetry unreliable.
            //
            // FIX #4: Resolved as a side-effect of FIX #3 — shouldRetry now reliably catches
            //         timeouts as IOException (thrown by Netty's ReadTimeoutHandler), which is
            //         already handled in the shouldRetry method below.
            //
            // FIX #5: Replaced .flatMap() status check with .doOnSuccess() logging.
            //         retrieve() already throws WebClientResponseException for non-2xx responses,
            //         so the old flatMap checking for HttpStatus.OK was redundant. Non-2xx errors
            //         flow naturally into shouldRetry via WebClientResponseException.
            accountingApiWebClient
                    .patch()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity()
                    .retryWhen(Retry.backoff(maxRetryAttempts, Duration.ofMillis(initialBackoffMillis))
                            .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                            .filter(this::shouldRetry)
                            .doBeforeRetry(signal ->
                                    log.warn("Retrying CoA request for user: {}, attempt: {}/{}",
                                            userName, signal.totalRetries() + 1, maxRetryAttempts)))
                    .doOnSuccess(response ->
                            log.info("Successfully sent CoA request to Accounting Service for user '{}'", userName))
                    .block();

            log.info("CoA request completed successfully for user '{}'", userName);

        } catch (Exception e) {
            log.error("Failed to send CoA request to Accounting Service for user '{}': {} -> {}",
                    userName, oldStatus, newStatus, e);
            // Don't throw — CoA failure should not block the user update
        }
    }

    /**
     * Determines whether a failed request should be retried based on the error type.
     *
     * Retries on:
     * - 5xx server errors
     * - 429 Too Many Requests
     * - 408 Request Timeout
     * - IOException / ConnectException (network-level failures)
     *
     * Note: With the .timeout() operator removed (FIX #3), timeouts now arrive as
     * IOException from Netty's ReadTimeoutHandler and are caught by the IOException
     * branch below — no special TimeoutException handling needed.
     */
    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // Retry on server errors (5xx), rate limit (429), or timeout (408)
            return status >= 500 || status == 429 || status == 408;
        }
        // Retry on network-level errors (includes Netty ReadTimeoutException which extends IOException)
        return throwable instanceof java.io.IOException
                || throwable instanceof java.net.ConnectException;
    }

    public void sendServiceDeleteCoARequest(String userName, Long serviceId) {
        if (!accountingServiceEnabled) {
            log.warn("Accounting service is disabled. Skipping delete CoA for user '{}'", userName);
            return;
        }

        log.info("=== CoA DELETE REQUEST INITIATED === user='{}', serviceId='{}'", userName, serviceId);

        try {
            String endpoint = String.format("/deleteService/%s/%s", userName, serviceId);
            log.debug("CoA DELETE endpoint: {}", endpoint);

            accountingApiWebClient
                    .delete()
                    .uri(endpoint)
                    .retrieve()
                    .toBodilessEntity()
                    .retryWhen(Retry.backoff(maxRetryAttempts, Duration.ofMillis(initialBackoffMillis))
                            .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                            .filter(this::shouldRetry)
                            .doBeforeRetry(signal ->
                                    log.warn("=== CoA DELETE RETRY === user='{}', serviceId='{}', attempt={}/{}",
                                            userName, serviceId, signal.totalRetries() + 1, maxRetryAttempts)))
                    .doOnSuccess(response ->
                            log.info("=== CoA DELETE SENT SUCCESSFULLY === user='{}', serviceId='{}', httpStatus='{}'",
                                    userName, serviceId, response.getStatusCode()))
                    .block();

            log.info("=== CoA DELETE COMPLETED === user='{}', serviceId='{}'", userName, serviceId);

        } catch (Exception e) {
            log.error("=== CoA DELETE FAILED === user='{}', serviceId='{}', error='{}'",
                    userName, serviceId, e.getMessage(), e);
        }
    }

    /**
     * Sends CoA request when a service status is updated.
     * Calls: PATCH /patchServiceStatus/{userName}/{serviceId}/{status}
     *
     * Triggered for:
     * - Active -> Suspended
     * - Suspended -> Inactive
     * - Suspended -> Active
     */
    public void sendServiceStatusCoARequest(String userName, Long serviceId, String newStatus) {
        if (!accountingServiceEnabled) {
            log.warn("Accounting service is disabled. Skipping status CoA for user '{}'", userName);
            return;
        }

        log.info("=== CoA STATUS REQUEST INITIATED === user='{}', serviceId='{}', newStatus='{}'",
                userName, serviceId, newStatus);

        try {
            String endpoint = String.format("/patchServiceStatus/%s/%s/%s", userName, serviceId, newStatus);
            log.debug("CoA STATUS endpoint: {}", endpoint);

            accountingApiWebClient
                    .patch()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity()
                    .retryWhen(Retry.backoff(maxRetryAttempts, Duration.ofMillis(initialBackoffMillis))
                            .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                            .filter(this::shouldRetry)
                            .doBeforeRetry(signal ->
                                    log.warn("=== CoA STATUS RETRY === user='{}', serviceId='{}', status='{}', attempt={}/{}",
                                            userName, serviceId, newStatus, signal.totalRetries() + 1, maxRetryAttempts)))
                    .doOnSuccess(response ->
                            log.info("=== CoA STATUS SENT SUCCESSFULLY === user='{}', serviceId='{}', newStatus='{}', httpStatus='{}'",
                                    userName, serviceId, newStatus, response.getStatusCode()))
                    .block();

            log.info("=== CoA STATUS COMPLETED === user='{}', serviceId='{}'", userName, serviceId);

        } catch (Exception e) {
            log.error("=== CoA STATUS FAILED === user='{}', serviceId='{}', status='{}', error='{}'",
                    userName, serviceId, newStatus, e.getMessage(), e);
        }
    }
}