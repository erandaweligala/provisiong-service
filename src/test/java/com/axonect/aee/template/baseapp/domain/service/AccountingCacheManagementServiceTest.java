package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.Balance;
import com.axonect.aee.template.baseapp.domain.entities.dto.BalanceWrapper;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingCacheManagementServiceTest {

    private AccountingCacheManagementService service;

    @Mock
    private WebClient cacheApiWebClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Captor
    private ArgumentCaptor<Object> bodyCaptor;

    private final String testUsername = "testUser";
    private final String serviceStatus = "Active";
    private final String cacheApiUrl = "http://cache-api:8080/buckets/{bucketUsername}";

    @BeforeEach
    void setUp() {
        service = new AccountingCacheManagementService(cacheApiWebClient);
        setField("cacheApiUrl", cacheApiUrl);
        setField("maxRetryAttempts", 0);   // no retries by default in tests
        setField("initialBackoffSeconds", 0);
        setField("maxBackoffSeconds", 0);
    }

    // =========================================================================
    // syncBuckets – early-exit guard paths
    // =========================================================================

    @Test
    void syncBuckets_withNullUsername_shouldReturnWithoutCallingWebClient() {
        ServiceInstance si = serviceInstance(null, serviceStatus);

        assertDoesNotThrow(() ->
                service.syncBuckets(List.of(bucket("b1")), "86400", 5L, si));

        verify(cacheApiWebClient, never()).patch();
    }

    @Test
    void syncBuckets_withBlankUsername_shouldReturnWithoutCallingWebClient() {
        ServiceInstance si = serviceInstance("  ", serviceStatus);

        assertDoesNotThrow(() ->
                service.syncBuckets(List.of(bucket("b1")), "86400", 5L, si));

        verify(cacheApiWebClient, never()).patch();
    }

    @Test
    void syncBuckets_withNullBucketList_shouldReturnWithoutCallingWebClient() {
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        assertDoesNotThrow(() ->
                service.syncBuckets(null, "86400", 5L, si));

        verify(cacheApiWebClient, never()).patch();
    }

    @Test
    void syncBuckets_withEmptyBucketList_shouldReturnWithoutCallingWebClient() {
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        assertDoesNotThrow(() ->
                service.syncBuckets(Collections.emptyList(), "86400", 5L, si));

        verify(cacheApiWebClient, never()).patch();
    }

    // =========================================================================
    // syncBuckets – fire-and-forget: single request, correct wrapper
    // =========================================================================

    @Test
    void syncBuckets_success_shouldMakeExactlyOneWebClientCall() {
        mockSuccess();
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        service.syncBuckets(List.of(bucket("b1"), bucket("b2")), "86400", 5L, si);

        verify(requestBodySpec, times(1)).bodyValue(any());
    }

    @Test
    void syncBuckets_shouldBuildBalanceWrapperWithUserSessionData() {
        mockSuccess();
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        service.syncBuckets(List.of(bucket("b1")), "86400", 10L, si);

        verify(requestBodySpec).bodyValue(bodyCaptor.capture());
        BalanceWrapper wrapper = (BalanceWrapper) bodyCaptor.getValue();

        assertThat(wrapper.getSessionTimeOut()).isEqualTo("86400");
        assertThat(wrapper.getConcurrency()).isEqualTo(10L);
        assertThat(wrapper.getBalance()).hasSize(1);
    }

    @Test
    void syncBuckets_shouldMapAllBucketsIntoBalanceList() {
        mockSuccess();
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        service.syncBuckets(List.of(bucket("b1"), bucket("b2"), bucket("b3")), "86400", 5L, si);

        verify(requestBodySpec).bodyValue(bodyCaptor.capture());
        assertThat(((BalanceWrapper) bodyCaptor.getValue()).getBalance()).hasSize(3);
    }

    @Test
    void syncBuckets_shouldPopulateBalanceFieldsFromBucketAndServiceInstance() {
        mockSuccess();
        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        LocalDateTime start = LocalDateTime.now();

        BucketInstance b = new BucketInstance();
        b.setBucketId("bkt-99");
        b.setInitialBalance(2000L);
        b.setCurrentBalance(1500L);
        b.setExpiration(expiry);
        b.setServiceId(7L);
        b.setPriority(2L);
        b.setTimeWindow("9AM-5PM");
        b.setConsumptionLimit(500L);
        b.setConsumptionLimitWindow("12");
        b.setIsUnlimited(false);
        b.setUsage(100L);

        ServiceInstance si = serviceInstance(testUsername, serviceStatus);
        si.setServiceStartDate(start);
        si.setExpiryDate(expiry);    // serviceExpiry is taken from serviceInstance.getExpiryDate()

        service.syncBuckets(List.of(b), "86400", 5L, si);

        verify(requestBodySpec).bodyValue(bodyCaptor.capture());
        Balance balance = ((BalanceWrapper) bodyCaptor.getValue()).getBalance().get(0);

        assertThat(balance.getBucketId()).isEqualTo("bkt-99");
        assertThat(balance.getInitialBalance()).isEqualTo(2000L);
        assertThat(balance.getQuota()).isEqualTo(1500L);          // quota = currentBalance
        assertThat(balance.getServiceExpiry()).isEqualTo(expiry); // from serviceInstance.getExpiryDate()
        assertThat(balance.getBucketExpiryDate()).isEqualTo(expiry); // from instance.getExpiration()
        assertThat(balance.getServiceId()).isEqualTo("7");
        assertThat(balance.getPriority()).isEqualTo(2L);
        assertThat(balance.getServiceStartDate()).isEqualTo(start);
        assertThat(balance.getServiceStatus()).isEqualTo(serviceStatus);
        assertThat(balance.getTimeWindow()).isEqualTo("9AM-5PM");
        assertThat(balance.getConsumptionLimit()).isEqualTo(500L);
        assertThat(balance.getConsumptionLimitWindow()).isEqualTo(12L);
        assertThat(balance.getBucketUsername()).isEqualTo(testUsername);
        assertThat(balance.isUnlimited()).isFalse();
        assertThat(balance.isGroup()).isFalse();
        assertThat(balance.getUsage()).isEqualTo(100L);
    }

    @Test
    void syncBuckets_withNullableFieldsInBucket_shouldPassThroughNullsAndApplyConsumptionDefault() {
        mockSuccess();

        // Only fields that would NPE on primitive unboxing are explicitly set
        BucketInstance b = new BucketInstance();
        b.setBucketId("bkt-nullable");
        b.setIsUnlimited(false);  // Balance.isUnlimited is primitive boolean — must be non-null
        b.setUsage(0L);           // Balance.usage is primitive long — must be non-null

        // serviceInstance.isGroup is primitive boolean — already false in the helper
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        service.syncBuckets(List.of(b), null, 5L, si);

        verify(requestBodySpec).bodyValue(bodyCaptor.capture());
        Balance balance = ((BalanceWrapper) bodyCaptor.getValue()).getBalance().get(0);

        // Null fields are passed through as null (no magic defaults in service)
        assertThat(balance.getInitialBalance()).isNull();
        assertThat(balance.getQuota()).isNull();
        assertThat(balance.getServiceExpiry()).isNull();      // serviceInstance.getExpiryDate() = null
        assertThat(balance.getBucketExpiryDate()).isNull();   // instance.getExpiration() = null
        assertThat(balance.getTimeWindow()).isNull();
        assertThat(balance.getConsumptionLimit()).isNull();
        // parseConsumptionLimitWindow returns 24L for null input
        assertThat(balance.getConsumptionLimitWindow()).isEqualTo(24L);
        assertThat(balance.isUnlimited()).isFalse();
        assertThat(balance.getUsage()).isZero();
    }

    @Test
    void syncBuckets_withNullSessionTimeout_shouldNotThrow() {
        mockSuccess();
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        assertDoesNotThrow(() ->
                service.syncBuckets(List.of(bucket("b1")), null, 5L, si));

        verify(requestBodySpec, times(1)).bodyValue(any());
    }

    @Test
    void syncBuckets_onWebClientFailure_shouldNotThrowExceptionToCaller() {
        mockFailure();
        ServiceInstance si = serviceInstance(testUsername, serviceStatus);

        // fire-and-forget: error is consumed inside subscribe(onNext, onError), never rethrown
        assertDoesNotThrow(() ->
                service.syncBuckets(List.of(bucket("b1")), "86400", 5L, si));
    }

    // =========================================================================
    // syncBuckets – retry path: covers doBeforeRetry lambda (lines 80-82)
    // =========================================================================

    @Test
    void syncBuckets_onRetryableError_shouldExecuteDoBeforeRetryAndRetry() throws InterruptedException {
        // Override maxRetryAttempts to allow one retry
        setField("maxRetryAttempts", 1);

        CountDownLatch successLatch = new CountDownLatch(1);
        AtomicInteger subscriptionCount = new AtomicInteger(0);

        WebClientResponseException serverError = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error", HttpHeaders.EMPTY, null, null);

        // toBodilessEntity() is called ONCE — it returns a Mono.
        // Reactor retries by re-subscribing to that same Mono (not by calling toBodilessEntity() again).
        // Mono.defer() evaluates the lambda on each subscription, so:
        //   1st subscription → Mono.error (triggers retry + doBeforeRetry)
        //   2nd subscription (the retry) → Mono.just (success, counts down the latch)
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(
                Mono.defer(() -> {
                    if (subscriptionCount.incrementAndGet() == 1) {
                        return Mono.error(serverError);  // first subscription: 500 → triggers retry
                    }
                    successLatch.countDown();             // retry subscription: success
                    return Mono.just(ResponseEntity.ok().build());
                }));

        ServiceInstance si = serviceInstance(testUsername, serviceStatus);
        service.syncBuckets(List.of(bucket("b1")), "86400", 5L, si);

        // Wait for the async retry to complete (doBeforeRetry fires before the second subscription)
        boolean completed = successLatch.await(3, TimeUnit.SECONDS);

        assertTrue(completed, "Retry did not complete within the timeout — doBeforeRetry may not have fired");
    }

    // =========================================================================
    // shouldRetry – private method via reflection
    // =========================================================================

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 429, 408})
    void shouldRetry_withRetryableStatusCodes_shouldReturnTrue(int status) {
        WebClientResponseException ex = WebClientResponseException.create(
                status, "Error", HttpHeaders.EMPTY, null, null);
        assertThat(invokeShouldRetry(ex)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void shouldRetry_withNonRetryableStatusCodes_shouldReturnFalse(int status) {
        WebClientResponseException ex = WebClientResponseException.create(
                status, "Error", HttpHeaders.EMPTY, null, null);
        assertThat(invokeShouldRetry(ex)).isFalse();
    }

    @Test
    void shouldRetry_withTimeoutException_shouldReturnTrue() {
        assertThat(invokeShouldRetry(new java.util.concurrent.TimeoutException())).isTrue();
    }

    @Test
    void shouldRetry_withIOException_shouldReturnTrue() {
        assertThat(invokeShouldRetry(new java.io.IOException("IO Error"))).isTrue();
    }

    @Test
    void shouldRetry_withConnectException_shouldReturnTrue() {
        // ConnectException extends IOException — covered by the IOException check
        assertThat(invokeShouldRetry(new java.net.ConnectException("refused"))).isTrue();
    }

    @Test
    void shouldRetry_withOtherException_shouldReturnFalse() {
        assertThat(invokeShouldRetry(new IllegalArgumentException("other"))).isFalse();
    }

    // =========================================================================
    // parseConsumptionLimitWindow – private method via reflection
    // =========================================================================

    @ParameterizedTest
    @MethodSource("consumptionWindowProvider")
    void parseConsumptionLimitWindow_withVariousInputs_shouldReturnCorrectLong(String input, Long expected) {
        assertThat(invokeParseConsumptionLimitWindow(input)).isEqualTo(expected);
    }

    private static Stream<Arguments> consumptionWindowProvider() {
        return Stream.of(
                Arguments.of(null,   24L),
                Arguments.of("",     24L),
                Arguments.of("  ",   24L),
                Arguments.of("12",   12L),
                Arguments.of("24",   24L),
                Arguments.of("48",   48L),
                Arguments.of("0",    24L),   // zero is treated as invalid → default
                Arguments.of("-5",   24L),   // negative is treated as invalid → default
                Arguments.of("abc",  24L),   // non-numeric → NumberFormatException → default
                Arguments.of(" 12 ", 12L)    // trimmed before parsing
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BucketInstance bucket(String bucketId) {
        BucketInstance b = new BucketInstance();
        b.setBucketId(bucketId);
        b.setInitialBalance(1000L);
        b.setCurrentBalance(800L);
        b.setExpiration(LocalDateTime.now().plusDays(30));
        b.setServiceId(1L);
        b.setPriority(1L);
        b.setTimeWindow("ANY");
        b.setConsumptionLimit(500L);
        b.setConsumptionLimitWindow("24");
        b.setIsUnlimited(false);
        b.setUsage(0L);
        return b;
    }

    private ServiceInstance serviceInstance(String username, String status) {
        ServiceInstance si = new ServiceInstance();
        si.setUsername(username);
        si.setStatus(status);
        si.setIsGroup(false);
        si.setServiceStartDate(LocalDateTime.now());
        return si;
    }

    private void mockSuccess() {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));
    }

    private void mockFailure() {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(
                Mono.error(WebClientResponseException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error", HttpHeaders.EMPTY, null, null)));
    }

    private boolean invokeShouldRetry(Throwable throwable) {
        try {
            var method = AccountingCacheManagementService.class
                    .getDeclaredMethod("shouldRetry", Throwable.class);
            method.setAccessible(true);
            return (boolean) method.invoke(service, throwable);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Long invokeParseConsumptionLimitWindow(String window) {
        try {
            var method = AccountingCacheManagementService.class
                    .getDeclaredMethod("parseConsumptionLimitWindow", String.class);
            method.setAccessible(true);
            return (Long) method.invoke(service, window);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(String name, Object value) {
        try {
            Field f = AccountingCacheManagementService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + name, e);
        }
    }
}
