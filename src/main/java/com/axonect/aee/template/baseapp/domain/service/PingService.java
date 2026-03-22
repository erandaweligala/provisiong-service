package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.repository.BngRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PingService {

    private static final Logger log = LoggerFactory.getLogger(PingService.class);

    private static final int TIMEOUT_MS = 5000;
    private static final int REQUEST_TIMEOUT_MS = 10000;

    private static final int[] TCP_PORTS = {22, 23, 161, 443};

    private final BngRepository bngRepository;

    public PingService(BngRepository bngRepository) {
        this.bngRepository = bngRepository;
    }

    /**
     * Look up NAS IP by bngId, then ping it.
     * Returns "SUCCESS", "FAILED", "NOT_FOUND", or "INVALID_IP"
     */
    public String ping(String bngId) {
        log.info("Ping request received for BNG ID: {}", bngId);

        BngEntity bng = bngRepository.findById(bngId).orElse(null);

        if (bng == null) {
            log.warn("BNG not found for ID: {}", bngId);
            return "NOT_FOUND";
        }

        String nasIp = bng.getNasIpAddress();
        log.info("Found NAS IP {} for BNG ID {}", nasIp, bngId);

        if (!isValidPingableIp(nasIp)) {
            log.warn("NAS IP '{}' for BNG {} is invalid, a domain, or a loopback address — skipping ping", nasIp, bngId);
            return "INVALID_IP";
        }

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // 1️ Java ICMP reachability check
                    if (inetPing(nasIp)) {
                        return "SUCCESS";
                    }

                    // 2️ System ping
                    if (systemPing(nasIp)) {
                        return "SUCCESS";
                    }

                    // 3️ TCP port probing
                    for (int port : TCP_PORTS) {
                        log.info("Attempting TCP ping to {} on port {}", nasIp, port);
                        if (tcpPing(nasIp, port)) {
                            log.info("TCP ping successful on {}:{}", nasIp, port);
                            return "SUCCESS";
                        }
                    }

                    log.error("All ping methods failed for NAS IP {}", nasIp);
                    return "FAILED";

                } catch (Exception e) {
                    log.error("Exception occurred while pinging NAS IP {} for BNG {}", nasIp, bngId, e);
                    return "FAILED";
                }
            });

            return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            log.error("Ping request timed out or failed for BNG {}: {}", bngId, e.getMessage());
            return "FAILED";
        }
    }

    /**
     * Validates that the given string is a proper IPv4 or IPv6 address,
     * and is NOT a loopback, localhost, or unspecified address.
     *
     * Rejects:
     *  - null or blank values
     *  - hostnames / domain names (e.g. "router.local", "example.com")
     *  - IPv4 loopback: 127.0.0.0/8
     *  - IPv6 loopback: ::1
     *  - Unspecified address: 0.0.0.0
     */
    boolean isValidPingableIp(String ip) {
        if (ip == null || ip.isBlank()) {
            log.warn("NAS IP is null or blank");
            return false;
        }

        try {
            InetAddress addr = InetAddress.getByName(ip);

            // Reject if the input was resolved as a hostname
            // (i.e. the string is not itself a numeric IP literal)
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

    /**
     * Java InetAddress ping
     */
    boolean inetPing(String ipAddress) {
        try {
            log.info("Attempting InetAddress ping to {}", ipAddress);
            InetAddress inet = InetAddress.getByName(ipAddress);
            boolean reachable = inet.isReachable(TIMEOUT_MS);

            if (reachable) {
                log.info("InetAddress ping successful for {}", ipAddress);
            } else {
                log.warn("InetAddress ping failed for {}", ipAddress);
            }

            return reachable;

        } catch (Exception e) {
            log.warn("InetAddress ping error for {}", ipAddress, e);
            return false;
        }
    }

    /**
     * System ping using OS command with timeout
     */
    boolean systemPing(String ipAddress) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            log.info("Detected OS: {}", os);

            String pingCmd = os.contains("win")
                    ? "ping -n 1 " + ipAddress
                    : "ping -c 1 " + ipAddress;

            log.info("Executing system ping command: {}", pingCmd);
            Process process = Runtime.getRuntime().exec(pingCmd);

            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("System ping timed out for {}", ipAddress);
                process.destroyForcibly();
                return false;
            }

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Ping output: {}", line);
                    if (line.toLowerCase().contains("ttl") || line.toLowerCase().contains("time=")) {
                        log.info("System ping detected successful response for {}", ipAddress);
                        return true;
                    }
                }
            }

            int exitCode = process.exitValue();
            log.info("System ping exit code for {} : {}", ipAddress, exitCode);
            return exitCode == 0;

        } catch (InterruptedException e) {
            log.error("System ping interrupted for {}", ipAddress, e);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("System ping failed for {}", ipAddress, e);
            return false;
        }
    }

    /**
     * TCP connection check with timeout
     */
    boolean tcpPing(String ipAddress, int port) {
        log.info("Attempting TCP connection to {}:{}", ipAddress, port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ipAddress, port), TIMEOUT_MS);
            log.info("TCP connection successful to {}:{}", ipAddress, port);
            return true;
        } catch (Exception e) {
            log.warn("TCP connection failed to {}:{} ", ipAddress, port);
            log.debug("TCP failure details", e);
            return false;
        }
    }
}