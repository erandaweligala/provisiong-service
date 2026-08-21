package com.axonect.aee.template.baseapp.domain.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Component
public class PingCheckClient {

    private final RestTemplate restTemplate;
    private final String pingProbeBaseUrl; // e.g. from @Value("${radius.server.base-url}")

    public PingCheckClient(RestTemplateBuilder builder,
                           @Value("${radius.server.base-url}") String pingProbeBaseUrl) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(3000))
                .setReadTimeout(Duration.ofMillis(10000)) // matches REQUEST_TIMEOUT_MS
                .build();
        this.pingProbeBaseUrl = pingProbeBaseUrl;
    }

    public String checkUdp(String nasIp, String bngId) {
        Map<String, String> body = Map.of("nasIp", nasIp, "bngId", bngId);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                pingProbeBaseUrl + "/internal/ping/udp", body, Map.class);
        Object status = resp.getBody() != null ? resp.getBody().get("status") : null;
        return status != null ? status.toString() : "FAILED";
    }
}
