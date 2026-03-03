package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CoAManagementService}.
 *
 * Coverage targets:
 *  sendCoARequest  — service disabled, status unchanged, from-INACTIVE skip,
 *                    successful call, exception swallowed
 *  shouldRetry     — 5xx, 429, 408, 4xx (no retry), IOException,
 *                    ConnectException, unknown throwable
 *
 * WebClient is stubbed with a fluent mock chain so no real HTTP calls are made.
 */
@ExtendWith(MockitoExtension.class)
class CoAManagementServiceTest {

    // ── WebClient fluent-chain mocks ──────────────────────────────────────────
    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private CoAManagementService service;

    @BeforeEach
    void setUp() {
        service = new CoAManagementService(webClient);

        // Default field values matching yaml defaults
        ReflectionTestUtils.setField(service, "accountingServiceEnabled", true);
        ReflectionTestUtils.setField(service, "maxRetryAttempts",    3);
        ReflectionTestUtils.setField(service, "initialBackoffMillis", 1000);
        ReflectionTestUtils.setField(service, "maxBackoffMillis",     5000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Wires up the WebClient mock chain and returns the terminal Mono.
     * The supplied {@code terminalMono} is what toBodilessEntity() returns,
     * allowing each test to control success vs failure.
     */
    @SuppressWarnings("unchecked")
    private void stubWebClientChain(Mono<ResponseEntity<Void>> terminalMono) {
        when(webClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(terminalMono);
    }

    /** Builds a WebClientResponseException for the given HTTP status. */
    private WebClientResponseException responseException(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(), status.getReasonPhrase(),
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0], null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // sendCoARequest — guard-clause branches
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1 · Should skip CoA when accounting service is disabled")
    void sendCoARequest_serviceDisabled_skipsCall() {
        ReflectionTestUtils.setField(service, "accountingServiceEnabled", false);

        service.sendCoARequest("alice", UserStatus.ACTIVE, UserStatus.BARRED);

        // WebClient must never be touched
        verifyNoInteractions(webClient);
    }

    @Test
    @DisplayName("2 · Should skip CoA when old and new status are identical")
    void sendCoARequest_statusUnchanged_skipsCall() {
        service.sendCoARequest("alice", UserStatus.ACTIVE, UserStatus.ACTIVE);

        verifyNoInteractions(webClient);
    }

    @Test
    @DisplayName("3 · Should skip CoA when old status is INACTIVE (no CoA from inactive)")
    void sendCoARequest_fromInactive_skipsCall() {
        service.sendCoARequest("alice", UserStatus.INACTIVE, UserStatus.ACTIVE);

        verifyNoInteractions(webClient);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // sendCoARequest — happy path
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4 · Should call PATCH /patchStatus/{user}/{status} on valid status change")
    void sendCoARequest_validChange_callsPatchEndpoint() {
        stubWebClientChain(Mono.just(ResponseEntity.ok().build()));

        service.sendCoARequest("alice", UserStatus.ACTIVE, UserStatus.BARRED);

        // Verify the full WebClient chain was exercised
        verify(webClient).patch();
        verify(requestBodyUriSpec).uri("/patchStatus/alice/BARRED");
        verify(requestBodySpec).retrieve();
        verify(responseSpec).toBodilessEntity();
    }

    @Test
    @DisplayName("5 · Should NOT propagate exception when accounting service call fails")
    void sendCoARequest_callThrowsException_exceptionSwallowed() {
        stubWebClientChain(Mono.error(new RuntimeException("connection refused")));

        // Must complete without throwing — CoA failure must never block user update
        service.sendCoARequest("alice", UserStatus.ACTIVE, UserStatus.BARRED);

        verify(webClient).patch();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // shouldRetry — accessed via reflection to test private method in isolation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Convenience helper — invokes the private shouldRetry method via reflection.
     */
    private boolean shouldRetry(Throwable t) {
        try {
            var method = CoAManagementService.class
                    .getDeclaredMethod("shouldRetry", Throwable.class);
            method.setAccessible(true);
            return (boolean) method.invoke(service, t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("6 · shouldRetry returns true for 5xx server errors")
    void shouldRetry_5xxError_returnsTrue() {
        assert shouldRetry(responseException(HttpStatus.INTERNAL_SERVER_ERROR));
        assert shouldRetry(responseException(HttpStatus.BAD_GATEWAY));
        assert shouldRetry(responseException(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("7 · shouldRetry returns true for 429 Too Many Requests")
    void shouldRetry_429_returnsTrue() {
        assert shouldRetry(responseException(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("8 · shouldRetry returns true for 408 Request Timeout")
    void shouldRetry_408_returnsTrue() {
        assert shouldRetry(responseException(HttpStatus.REQUEST_TIMEOUT));
    }

    @Test
    @DisplayName("9 · shouldRetry returns false for 4xx client errors (except 408 / 429)")
    void shouldRetry_4xxClientError_returnsFalse() {
        assert !shouldRetry(responseException(HttpStatus.BAD_REQUEST));
        assert !shouldRetry(responseException(HttpStatus.NOT_FOUND));
        assert !shouldRetry(responseException(HttpStatus.UNAUTHORIZED));
        assert !shouldRetry(responseException(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("10 · shouldRetry returns true for IOException and ConnectException, false for unknown")
    void shouldRetry_networkErrors_returnsTrueOrFalseAsExpected() {
        // IOException (covers Netty ReadTimeoutException which extends it)
        assert shouldRetry(new IOException("read timeout"));

        // ConnectException is a subtype of IOException — still true
        assert shouldRetry(new ConnectException("connection refused"));

        // Arbitrary non-network, non-HTTP exception → must NOT retry
        assert !shouldRetry(new RuntimeException("unexpected"));
        assert !shouldRetry(new IllegalStateException("unexpected state"));
    }
}