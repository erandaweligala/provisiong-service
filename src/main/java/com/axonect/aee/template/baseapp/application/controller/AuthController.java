package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateCredentialRequest;
import com.axonect.aee.template.baseapp.application.transport.request.entities.TokenRequest;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.ApiResponse;
import com.axonect.aee.template.baseapp.application.transport.response.transformers.TokenResponse;
import com.axonect.aee.template.baseapp.domain.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication controller.
 *
 * POST   /api/auth/token                — obtain access token   (channel: SRH)
 * DELETE /api/auth/token                — revoke access token   (channel: IAT + Bearer)
 * POST   /api/auth/credentials          — create a credential   (channel: SRH)
 * DELETE /api/auth/credentials/{username} — deactivate a credential (channel: SRH)
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    // ── Token endpoints ────────────────────────────────────────────────────

    @PostMapping("/token")
    public ResponseEntity<ApiResponse> generateToken(
            @Valid @RequestBody TokenRequest request) {

        log.info("Token request for user='{}'", request.getUsername());
        TokenResponse tokenResponse = authService.login(request);

        return ResponseEntity.ok(
                ApiResponse.success("Token generated successfully", tokenResponse)
        );
    }

    @DeleteMapping("/token")
    public ResponseEntity<ApiResponse> revokeToken(
            @RequestHeader("Authorization") String authHeader) {

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        return ResponseEntity.ok(
                ApiResponse.success("Token revoked successfully", null)
        );
    }

    // ── Credential management endpoints ────────────────────────────────────

    /**
     * Create a new API credential.
     * Caller must use channel: SRH (internal channel — no Bearer token required).
     *
     * Request body:
     * {
     *   "username": "alice",
     *   "password": "Str0ng@Pass!"
     * }
     */
    @PostMapping("/credentials")
    public ResponseEntity<ApiResponse> createCredential(
            @Valid @RequestBody CreateCredentialRequest request) {

        String created = authService.createCredential(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Credential created for user '" + created + "'", null
                ));
    }

    /**
     * Deactivate an existing credential and revoke all its live tokens.
     * Caller must use channel: SRH.
     *
     * DELETE /api/auth/credentials/alice
     */
    @DeleteMapping("/credentials/{username}")
    public ResponseEntity<ApiResponse> deleteCredential(
            @PathVariable String username) {

        authService.deleteCredential(username);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Credential deactivated for user '" + username + "'", null
                ));
    }
}