// RateLimitFilter.java
package com.axonect.aee.template.baseapp.application.filter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Order(2) // Runs after XssFilter (which is Order 1)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter implements Filter {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration readBucketConfiguration;
    private final BucketConfiguration writeBucketConfiguration;
    private final BucketConfiguration deleteBucketConfiguration;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (true) { // need to remove code after testing
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Only apply to /api/** endpoints
        String uri = httpRequest.getRequestURI();
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // Build a unique key per client + HTTP method type
        String clientKey = buildClientKey(httpRequest);
        BucketConfiguration config = resolveBucketConfig(httpRequest.getMethod());

        // Get or create bucket in Redis for this client
        Bucket bucket = proxyManager.builder()
                .build(clientKey, () -> config);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Add rate limit info headers so clients can see their limits
            httpResponse.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            // Too many requests — return 429
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            log.warn("Rate limit exceeded for client: {} on URI: {}", clientKey, uri);

            httpResponse.setStatus(429);
            httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            httpResponse.addHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitSeconds));
            httpResponse.getWriter().write(buildErrorResponse(waitSeconds));
        }
    }

    /**
     * Build unique Redis key:  method_type:ip:username
     * This limits per client, not globally
     */
    private String buildClientKey(HttpServletRequest request) {
        String ip = resolveClientIp(request);
        String method = resolveMethodType(request.getMethod());
        // Optionally include username from header if your ChannelAuthFilter sets it
        String user = request.getHeader("X-User-Id") != null
                ? request.getHeader("X-User-Id") : "anonymous";

        return String.format("rate_limit:%s:%s:%s", method, ip, user);
    }

    /**
     * Different bucket config based on HTTP method
     */
    private BucketConfiguration resolveBucketConfig(String method) {
        return switch (method.toUpperCase()) {
            case "DELETE"       -> deleteBucketConfiguration;
            case "POST", "PATCH"-> writeBucketConfiguration;
            default             -> readBucketConfiguration;  // GET
        };
    }

    private String resolveMethodType(String method) {
        return switch (method.toUpperCase()) {
            case "DELETE"        -> "delete";
            case "POST", "PATCH" -> "write";
            default              -> "read";
        };
    }

    /**
     * Handles X-Forwarded-For for clients behind a proxy/load balancer
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim(); // take first IP in chain
        }
        return request.getRemoteAddr();
    }

    private String buildErrorResponse(long retryAfterSeconds) {
        return String.format("""
                {
                    "status": "ERROR",
                    "code": "429",
                    "message": "Too many requests. Please retry after %d seconds."
                }
                """, retryAfterSeconds);
    }
}