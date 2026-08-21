package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BngRepository;
import com.axonect.aee.template.baseapp.domain.adapter.PingCheckClient;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PingService {

    @Value("${ip-ping.udp-ports:3799,1812,1813}")
    private String udpPorts;

    private static final Logger log = LoggerFactory.getLogger(PingService.class);

    private static final int REQUEST_TIMEOUT_MS = 10000;

    private static final int MAX_CONCURRENT_PINGS = 5;
    private static final int RATE_LIMIT_MAX_REQUESTS = 3;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000L;

    private static final List<long[]> ALLOWED_CIDR_RANGES = List.of(
            cidr("10.0.0.0", 8),
            cidr("172.16.0.0", 12),
            cidr("192.168.0.0", 16)
    );

    private final Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENT_PINGS);
    private final Map<String, long[]> rateLimitMap = new ConcurrentHashMap<>();

    private final BngRepository bngRepository;
    private final PingCheckClient pingCheckClient; // new — HTTP client to Quarkus

    public PingService(BngRepository bngRepository, PingCheckClient pingCheckClient) {
        this.bngRepository = bngRepository;
        this.pingCheckClient = pingCheckClient;
    }

    public String ping(String bngId) {
        log.info("Ping request received for BNG ID: {}", bngId);

        if (bngId == null || bngId.isBlank() || !bngId.matches("[a-zA-Z0-9_\\-]{1,64}")) {
            log.warn("Invalid bngId format: {}", bngId);
            return "INVALID_REQUEST";
        }

        if (isRateLimited(bngId)) {
            log.warn("Rate limit exceeded for BNG ID: {}", bngId);
            return "RATE_LIMITED";
        }

        if (!concurrencyLimiter.tryAcquire()) {
            log.warn("Max concurrent ping limit reached, rejecting request for BNG ID: {}", bngId);
            return "TOO_MANY_REQUESTS";
        }

        try {
            BngEntity bng = bngRepository.findById(bngId).orElse(null);
            if (bng == null) {
                log.warn("BNG not found for ID: {}", bngId);
                return "NOT_FOUND";
            }

            String nasIp = bng.getNasIpAddress();
            log.info("Found NAS IP {} for BNG ID {}", nasIp, bngId);

            if (!isValidPingableIp(nasIp)) {
                log.warn("NAS IP '{}' for BNG {} failed validation — skipping ping", nasIp, bngId);
                return "INVALID_IP";
            }

            if (!isAllowedIp(nasIp)) {
                log.warn("NAS IP '{}' is not within any allowlisted CIDR range", nasIp);
                return "INVALID_IP";
            }

            // Delegate the actual UDP probe to Quarkus — it's not behind the firewall
            try {
                return pingCheckClient.checkUdp(nasIp, bngId);
            } catch (Exception e) {
                log.error("Ping probe call to Quarkus service failed for BNG {}: {}", bngId, e.getMessage());
                return "FAILED";
            }

        } catch (Exception e) {
            log.error("Ping request failed for BNG {}: {}", bngId, e.getMessage());
            return "FAILED";
        } finally {
            concurrencyLimiter.release();
        }
    }

    /**
     * Sliding-window rate limiter stored in a ConcurrentHashMap.
     * Returns true if the caller has exceeded the allowed request count.
     */
    private boolean isRateLimited(String bngId) {
        long now = System.currentTimeMillis();

        rateLimitMap.compute(bngId, (key, existing) -> {
            if (existing == null || (now - existing[1]) > RATE_LIMIT_WINDOW_MS) {
                return new long[]{1, now};
            }
            existing[0]++;
            return existing;
        });

        long[] entry = rateLimitMap.get(bngId);
        return entry != null && entry[0] > RATE_LIMIT_MAX_REQUESTS;
    }

    /**
     * Checks whether the given IP falls within any of the allowlisted CIDR ranges.
     */
    boolean isAllowedIp(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            byte[] bytes = addr.getAddress();
            if (bytes.length != 4) return false; // IPv6 not in allowlist

            long ipLong = ((bytes[0] & 0xFFL) << 24)
                    | ((bytes[1] & 0xFFL) << 16)
                    | ((bytes[2] & 0xFFL) << 8)
                    | (bytes[3] & 0xFFL);

            for (long[] range : ALLOWED_CIDR_RANGES) {
                if ((ipLong & range[1]) == range[0]) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Allowlist check failed for IP '{}': {}", ip, e.getMessage());
            return false;
        }
    }

    /**
     * Converts a CIDR base address + prefix length into [networkAddress, mask].
     */
    private static long[] cidr(String base, int prefix) {
        try {
            byte[] bytes = InetAddress.getByName(base).getAddress();
            long net = ((bytes[0] & 0xFFL) << 24)
                    | ((bytes[1] & 0xFFL) << 16)
                    | ((bytes[2] & 0xFFL) << 8)
                    | (bytes[3] & 0xFFL);
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return new long[]{net & mask, mask};
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CIDR base: " + base, e);
        }
    }

    boolean isValidPingableIp(String ip) {
        if (ip == null || ip.isBlank()) {
            log.warn("NAS IP is null or blank");
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (!addr.getHostAddress().equals(ip.trim())) {
                log.warn("Input '{}' looks like a hostname, not a raw IP address", ip);
                return false;
            }
            if (addr.isLoopbackAddress()) {
                log.warn("IP '{}' is a loopback/localhost address", ip);
                return false;
            }
            if (addr.isAnyLocalAddress()) {
                log.warn("IP '{}' is an unspecified (0.0.0.0) address", ip);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("IP validation failed for '{}': {}", ip, e.getMessage());
            return false;
        }
    }

}