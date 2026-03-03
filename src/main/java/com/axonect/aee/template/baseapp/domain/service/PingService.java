package com.axonect.aee.template.baseapp.domain.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

@Service
public class PingService {

    private static final int TIMEOUT_MS = 5000;

    /**
     * Ping the given IP and return only "SUCCESS" or "FAILED"
     */
    public String ping(String ipAddress) {
        try {
            InetAddress inet = InetAddress.getByName(ipAddress);

            boolean reachable = inet.isReachable(TIMEOUT_MS);

            if (!reachable) reachable = systemPing(ipAddress);
            if (!reachable) reachable = tcpPing(ipAddress, 80);

            return reachable ? "SUCCESS" : "FAILED";

        } catch (Exception e) {
            return "FAILED";
        }
    }

    private boolean systemPing(String ipAddress) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String pingCmd = os.contains("win")
                    ? "ping -n 1 " + ipAddress
                    : "ping -c 1 " + ipAddress;

            Process process = Runtime.getRuntime().exec(pingCmd);

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("ttl")
                            || line.toLowerCase().contains("time=")) {
                        return true;
                    }
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean tcpPing(String ipAddress, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ipAddress, port), TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
