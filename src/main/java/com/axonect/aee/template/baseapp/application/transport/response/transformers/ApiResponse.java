package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "request_id",
        "success",
        "code",
        "message",
        "timestamp",
        "data"
})
public class ApiResponse {

    @JsonProperty("request_id")
    private String requestId;

    private boolean success;

    private String code;

    private String message;

    private Instant timestamp;

    private Object data;

    /* ---------- Factory methods ---------- */

    public static ApiResponse success(String requestId, String code, String message, Object data) {
        return ApiResponse.builder()
                .requestId(requestId)
                .success(true)
                .code(code)
                .message(message)
                .timestamp(Instant.now())
                .data(data) // always serialized last
                .build();
    }

    public static ApiResponse success(String message, Object data) {
        return success(null, "AAA_200_SUCCESS", message, data);
    }

    public static ApiResponse success(Object data) {
        return success(null, "AAA_200_SUCCESS", null, data);
    }

    public static ApiResponse failure(String requestId, String code, String message) {
        return ApiResponse.builder()
                .requestId(requestId)
                .success(false)
                .code(code)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
