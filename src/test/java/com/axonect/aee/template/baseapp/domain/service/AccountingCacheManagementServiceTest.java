package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.CacheBucketRequest;
import com.axonect.aee.template.baseapp.domain.exception.CacheBucketException;
import com.axonect.aee.template.baseapp.domain.service.AccountingCacheManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private ArgumentCaptor<CacheBucketRequest> requestCaptor;

    private final String testBucketUsername = "testUser";
    private final String serviceStatus = "ACTIVE";
    private final String cacheApiUrl = "http://cache-api:8080/buckets/{bucketUsername}";

    @BeforeEach
    void setUp() {
        service = new AccountingCacheManagementService(cacheApiWebClient);

        // Set field values using reflection
        setFieldValue("cacheApiUrl", cacheApiUrl);
        setFieldValue("maxRetryAttempts", 3);
        setFieldValue("initialBackoffSeconds", 0); // set to 0 to avoid test delays
        setFieldValue("maxBackoffSeconds", 0);
        setFieldValue("requestTimeoutSeconds", 1);
    }

    private void setFieldValue(String fieldName, Object value) {
        try {
            Field field = AccountingCacheManagementService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(service, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field value", e);
        }
    }

    // ============ syncBuckets Tests ============

    @Test
    void syncBuckets_withNullBucketUsername_shouldThrowIllegalArgumentException() {
        // Arrange
        List<BucketInstance> bucketInstances = List.of(createBucketInstance("bucket1"));

        // Act & Assert
        assertThatThrownBy(() -> service.syncBuckets(null, serviceStatus, bucketInstances))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bucket username cannot be null or empty");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void syncBuckets_withEmptyOrNullBucketUsername_shouldThrowIllegalArgumentException(String bucketUsername) {
        // Arrange
        List<BucketInstance> bucketInstances = List.of(createBucketInstance("bucket1"));

        // Act & Assert
        assertThatThrownBy(() -> service.syncBuckets(bucketUsername, serviceStatus, bucketInstances))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bucket username cannot be null or empty");
    }

    @Test
    void syncBuckets_withNullBucketInstances_shouldLogWarningAndReturn() {
        // Act
        assertDoesNotThrow(() -> service.syncBuckets(testBucketUsername, serviceStatus, null));
    }

    @Test
    void syncBuckets_withEmptyBucketInstances_shouldLogWarningAndReturn() {
        // Act
        assertDoesNotThrow(() -> service.syncBuckets(testBucketUsername, serviceStatus, Collections.emptyList()));
    }

    @Test
    void syncBuckets_allBucketsSynced_shouldCompleteSuccessfully() {
        // Arrange
        mockWebClientSuccess();
        List<BucketInstance> buckets = List.of(createBucketInstance("b1"), createBucketInstance("b2"));

        // Act & Assert - no exception
        assertDoesNotThrow(() -> service.syncBuckets(testBucketUsername, serviceStatus, buckets));

        // Verify bodyValue was called for each bucket
        verify(requestBodySpec, times(2)).bodyValue(requestCaptor.capture());
        List<CacheBucketRequest> captured = requestCaptor.getAllValues();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).getBucketUsername()).isEqualTo(testBucketUsername);
    }

    @Test
    void syncBuckets_oneBucketFails_shouldThrowCacheBucketException() {
        // Arrange: simulate failure for all calls
        mockWebClientFailure();
        List<BucketInstance> buckets = List.of(createBucketInstance("failed-bucket"));

        // Act & Assert
        assertThatThrownBy(() -> service.syncBuckets(testBucketUsername, serviceStatus, buckets))
                .isInstanceOf(CacheBucketException.class)
                .hasMessageContaining("Batch sync completed with");
    }

    // ============ processBucket Tests ============

    @Test
    void processBucket_withNullBucket_shouldThrowIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> invokeProcessBucket(testBucketUsername, serviceStatus, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bucket instance is null");
    }

    @Test
    void processBucket_withEmptyBucketId_shouldThrowIllegalArgumentException() {
        // Arrange
        BucketInstance bucket = new BucketInstance();
        bucket.setBucketId("");

        // Act & Assert
        assertThatThrownBy(() -> invokeProcessBucket(testBucketUsername, serviceStatus, bucket))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bucket ID is null or empty");
    }

    @Test
    void processBucket_success_shouldNotThrow() {
        // Arrange
        mockWebClientSuccess();
        BucketInstance bucket = createBucketInstance("good-bucket");

        // Act & Assert
        assertDoesNotThrow(() -> invokeProcessBucket(testBucketUsername, serviceStatus, bucket));
        verify(requestBodySpec).bodyValue(requestCaptor.capture());
        CacheBucketRequest req = requestCaptor.getValue();
        assertThat(req.getBucketId()).isEqualTo("good-bucket");
        assertThat(req.getBucketUsername()).isEqualTo(testBucketUsername);
    }

    @Test
    void processBucket_non200Response_shouldThrowRuntimeException() {
        // Arrange
        mockWebClientWithNon200Response(HttpStatus.INTERNAL_SERVER_ERROR);
        BucketInstance bucket = createBucketInstance("bad-bucket");

        // Act & Assert
        assertThrows(RuntimeException.class, () -> invokeProcessBucket(testBucketUsername, serviceStatus, bucket));
    }


    // ============ shouldRetry Tests ============

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 429, 408})
    void shouldRetry_withRetryableStatusCodes_shouldReturnTrue(int statusCode) {
        // Arrange
        WebClientResponseException exception = WebClientResponseException.create(
                statusCode,
                "Test Error",
                HttpHeaders.EMPTY,
                null,
                null
        );

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void shouldRetry_withNonRetryableStatusCodes_shouldReturnFalse(int statusCode) {
        // Arrange
        WebClientResponseException exception = WebClientResponseException.create(
                statusCode,
                "Test Error",
                HttpHeaders.EMPTY,
                null,
                null
        );

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isFalse();
    }

    @Test
    void shouldRetry_withTimeoutException_shouldReturnTrue() {
        // Arrange
        java.util.concurrent.TimeoutException exception = new java.util.concurrent.TimeoutException("Timeout");

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isTrue();
    }

    @Test
    void shouldRetry_withIOException_shouldReturnTrue() {
        // Arrange
        java.io.IOException exception = new java.io.IOException("IO Error");

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isTrue();
    }

    @Test
    void shouldRetry_withConnectException_shouldReturnTrue() {
        // Arrange
        java.net.ConnectException exception = new java.net.ConnectException("Connection refused");

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isTrue();
    }

    @Test
    void shouldRetry_withOtherException_shouldReturnFalse() {
        // Arrange
        IllegalArgumentException exception = new IllegalArgumentException("Other error");

        // Act
        boolean shouldRetry = invokeShouldRetry(exception);

        // Assert
        assertThat(shouldRetry).isFalse();
    }

    // ============ buildRequest Tests ============

    /*@Test
    void buildRequest_withCompleteBucket_shouldBuildCorrectRequest() {
        // Arrange
        LocalDateTime expiration = LocalDateTime.of(2024, 12, 31, 23, 59, 59);
        BucketInstance bucket = new BucketInstance();
        bucket.setBucketId("test-bucket");
        bucket.setInitialBalance(1000L);
        bucket.setExpiration(expiration);
        bucket.setServiceId(1L);
        bucket.setPriority(2L);
        bucket.setTimeWindow("9AM-5PM");
        bucket.setConsumptionLimit(500L);
        bucket.setConsumptionLimitWindow("12");

        // Act
        CacheBucketRequest request = invokeBuildRequest(bucket, testBucketUsername, serviceStatus);

        // Assert
        assertThat(request.getBucketId()).isEqualTo("test-bucket");
        assertThat(request.getInitialBalance()).isEqualTo(1000L);
        assertThat(request.getQuota()).isEqualTo(1000L);
        assertThat(request.getServiceExpiry()).contains("2024-12-31T23:59:59.951");
        assertThat(request.getServiceId()).isEqualTo("test-service");
        assertThat(request.getPriority()).isEqualTo(2);
        assertThat(request.getServiceStartDate()).isEqualTo("2024-10-31T23:59:59");
        assertThat(request.getServiceStatus()).isEqualTo(serviceStatus);
        assertThat(request.getTimeWindow()).isEqualTo("9AM-5PM");
        assertThat(request.getConsumptionLimit()).isEqualTo(500L);
        assertThat(request.getConsumptionLimitWindow()).isEqualTo(12);
        assertThat(request.getBucketUsername()).isEqualTo(testBucketUsername);
    }*/

    /*@Test
    void buildRequest_withNullFields_shouldUseDefaults() {
        // Arrange
        BucketInstance bucket = new BucketInstance();
        bucket.setBucketId("test-bucket");

        // Act
        CacheBucketRequest request = invokeBuildRequest(bucket, testBucketUsername, serviceStatus);

        // Assert
        assertThat(request.getInitialBalance()).isZero();
        assertThat(request.getQuota()).isZero();
        assertThat(request.getServiceExpiry()).contains(".951"); // Should have default expiration
        assertThat(request.getServiceId()).isEqualTo("DEFAULT_SERVICE");
        assertThat(request.getPriority()).isEqualTo(4);
        assertThat(request.getServiceStartDate()).isNotNull(); // Current time
        assertThat(request.getTimeWindow()).isEqualTo("6AM-03AM");
        assertThat(request.getConsumptionLimit()).isZero();
        assertThat(request.getConsumptionLimitWindow()).isEqualTo(24);
    }

    @ParameterizedTest
    @CsvSource({
            "null, 24",
            "'', 24",
            "'  ', 24",
            "'12', 12",
            "'24', 24",
            "'48', 48",
            "'0', 24",
            "'-5', 24",
            "'abc', 24"
    })
    void buildRequest_withVariousConsumptionWindows_shouldParseCorrectly(String window, int expected) {
        // Arrange
        if ("null".equals(window)) {
            window = null;
        }

        BucketInstance bucket = new BucketInstance();
        bucket.setBucketId("test-bucket");
        bucket.setConsumptionLimitWindow(window);

        // Act
        CacheBucketRequest request = invokeBuildRequest(bucket, testBucketUsername, serviceStatus);

        // Assert
        assertThat(request.getConsumptionLimitWindow()).isEqualTo(expected);
    }*/

    // ============ parseConsumptionWindow Tests ============

    @ParameterizedTest
    @MethodSource("consumptionWindowProvider")
    void parseConsumptionWindow_withVariousInputs_shouldReturnCorrectValue(String input, Integer expected) {
        // Act
        Integer result = invokeParseConsumptionWindow(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    private static Stream<Arguments> consumptionWindowProvider() {
        return Stream.of(
                Arguments.of(null, 24),
                Arguments.of("", 24),
                Arguments.of("  ", 24),
                Arguments.of("12", 12),
                Arguments.of("24", 24),
                Arguments.of("48", 48),
                Arguments.of("0", 24),
                Arguments.of("-5", 24),
                Arguments.of("abc", 24),
                Arguments.of(" 12 ", 12)
        );
    }

    // ============ Helper Methods ============
    private BucketInstance createBucketInstance(String bucketId) {
        BucketInstance instance = new BucketInstance();
        instance.setBucketId(bucketId);
        instance.setInitialBalance(1000L);
        instance.setExpiration(LocalDateTime.now().plusDays(30));
        instance.setServiceId(1L);
        instance.setPriority(1L);
        instance.setTimeWindow("9AM-5PM");
        instance.setConsumptionLimit(500L);
        instance.setConsumptionLimitWindow("24");
        return instance;
    }


    private void mockWebClientSuccess() {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(
                Mono.just(ResponseEntity.ok().build())
        );
    }

    private void mockWebClientFailure() {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        WebClientResponseException exception = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                HttpHeaders.EMPTY,
                null,
                null
        );

        when(responseSpec.toBodilessEntity()).thenReturn(Mono.error(exception));
    }

    private void mockWebClientForRetry(Throwable throwable) {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        // First call throws exception, subsequent calls succeed (or continue throwing)
        when(responseSpec.toBodilessEntity())
                .thenReturn(Mono.error(throwable))
                .thenReturn(Mono.error(throwable))
                .thenReturn(Mono.error(throwable));
    }

    private void mockWebClientForSingleFailure(Throwable throwable) {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        when(responseSpec.toBodilessEntity()).thenReturn(Mono.error(throwable));
    }

    private void mockWebClientWithTimeout() {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        java.util.concurrent.TimeoutException timeoutException =
                new java.util.concurrent.TimeoutException("Request timeout");

        when(responseSpec.toBodilessEntity()).thenReturn(Mono.error(timeoutException));
    }

    private void mockWebClientWithNon200Response(HttpStatus status) {
        when(cacheApiWebClient.patch()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        org.mockito.Mockito.doReturn(requestBodySpec).when(requestBodySpec).bodyValue(any());
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        when(responseSpec.toBodilessEntity()).thenReturn(
                Mono.just(ResponseEntity.status(status).build())
        );
    }

    // Reflection helpers to test private methods
    private void invokeProcessBucket(String bucketUsername, String serviceStatus, BucketInstance bucket) {
        try {
            var method = AccountingCacheManagementService.class.getDeclaredMethod(
                    "processBucket", String.class, String.class, BucketInstance.class);
            method.setAccessible(true);
            method.invoke(service, bucketUsername, serviceStatus, bucket);
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new RuntimeException(e);
        }
    }

    private boolean invokeShouldRetry(Throwable throwable) {
        try {
            var method = AccountingCacheManagementService.class.getDeclaredMethod("shouldRetry", Throwable.class);
            method.setAccessible(true);
            return (boolean) method.invoke(service, throwable);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CacheBucketRequest invokeBuildRequest(BucketInstance instance, String bucketUsername, String serviceStatus) {
        try {
            var method = AccountingCacheManagementService.class.getDeclaredMethod(
                    "buildRequest", BucketInstance.class, String.class, String.class);
            method.setAccessible(true);
            return (CacheBucketRequest) method.invoke(service, instance, bucketUsername, serviceStatus);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Integer invokeParseConsumptionWindow(String window) {
        try {
            var method = AccountingCacheManagementService.class.getDeclaredMethod("parseConsumptionWindow", String.class);
            method.setAccessible(true);
            return (Integer) method.invoke(service, window);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
