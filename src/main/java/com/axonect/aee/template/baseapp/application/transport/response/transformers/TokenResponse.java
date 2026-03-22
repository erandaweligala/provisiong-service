package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class TokenResponse {
    private String accessToken;
    private String tokenType;       // always "Bearer"
    private long   expiresIn;       // seconds
    private String username;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
}