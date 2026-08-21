package com.axonect.aee.template.baseapp.application.filter;

import com.axonect.aee.template.baseapp.application.repository.AuthCredentialRepository;
import com.axonect.aee.template.baseapp.domain.algorithm.EncryptionPlugin;
import com.axonect.aee.template.baseapp.domain.entities.dto.AuthCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelAuthFilter extends OncePerRequestFilter {

    private static final String CHANNEL_HEADER = "channel";
    private static final String IAT             = "IAT";
    private static final String SRH             = "SRH";
    private static final String UIP             = "UIP";
    private static final String AAUIP           = "AAUIP";
    private static final String AUTH_HEADER     = "Authorization";
    private static final String BASIC_PREFIX    = "Basic ";

    private static final List<String> PROTECTED_PATHS = List.of(
           "/api/user",
            "/api/services"
    );

    private final AuthCredentialRepository authCredentialRepository;
    private final PasswordEncoder          passwordEncoder;
    private final ObjectMapper             objectMapper;
    private final EncryptionPlugin         encryptionPlugin;

    @Value("${app.encryption.algorithm:AES}")
    private String encryptionAlgorithm;

    @Value("${app.encryption.secret-key}")
    private String encryptionSecretKey;

    // ── Skip logic ────────────────────────────────────────────────────────

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/auth/")) return true;

        boolean isProtected = PROTECTED_PATHS.stream().anyMatch(uri::startsWith);
        if (!isProtected) log.debug("Path '{}' is not protected — skipping channel filter", uri);
        return !isProtected;
    }

    // ── Main gate logic ───────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        // ── 1. Block raw username/password headers ─────────────────────────
        if (request.getHeader("username") != null || request.getHeader("password") != null) {
            log.warn("Request rejected — raw 'username' or 'password' headers are not accepted");
            writeError(response, 400, "INVALID_AUTH_METHOD",
                    "Plain 'username' and 'password' headers are not accepted. Use Authorization: Basic <base64>");
            return;
        }

        // ── 2. Channel header must be present ─────────────────────────────
        String channel = request.getHeader(CHANNEL_HEADER);
        if (channel == null || channel.isBlank()) {
            writeError(response, 400, "MISSING_CHANNEL",
                    "Required header 'channel' is missing. Accepted values: SRH, IAT , UIP and AAUIP ");
            return;
        }

        // ── 3. SRH — internal channel, no auth needed ─────────────────────
        if (SRH.equalsIgnoreCase(channel)) {
            log.debug("SRH channel — skipping auth for {}", request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        // ── 4. IAT — Basic Authentication required ────────────────────────
        if (IAT.equalsIgnoreCase(channel) || UIP.equalsIgnoreCase(channel) || AAUIP.equalsIgnoreCase(channel)) {
            String authHeader = request.getHeader(AUTH_HEADER);

            if (authHeader == null || !authHeader.startsWith(BASIC_PREFIX)) {
                writeError(response, 401, "MISSING_CREDENTIALS",
                        "Authorization Basic credentials are required for channel IAT");
                return;
            }

            String[] parts = decodeBasicHeader(authHeader);
            if (parts == null) {
                writeError(response, 401, "MALFORMED_CREDENTIALS",
                        "Authorization header could not be decoded as valid Basic credentials");
                return;
            }

            String username          = parts[0];
            String encryptedPassword = parts[1];

            String password = decryptPassword(encryptedPassword);
            if (password == null) {
                log.error("IAT channel — AES decryption failed for user '{}'", username);
                writeError(response, 401, "INVALID_CREDENTIALS",
                        "Invalid username or password");
                return;
            }

            Optional<AuthCredential> credOpt =
                    authCredentialRepository.findByUsernameAndActiveTrue(username);

            if (credOpt.isEmpty() || !passwordEncoder.matches(password, credOpt.get().getPassword())) {
                log.error("IAT channel — invalid credentials for user '{}'", username);
                writeError(response, 401, "INVALID_CREDENTIALS",
                        "Invalid username or password");
                return;
            }

            request.setAttribute("authenticatedUser", username);
            log.debug("IAT channel — basic auth valid for user='{}', path={}",
                    username, request.getRequestURI());

            chain.doFilter(request, response);
            return;
        }

        // ── 5. Unknown channel value ───────────────────────────────────────
        writeError(response, 400, "INVALID_CHANNEL",
                "Invalid channel value '" + channel + "'. Accepted values: SRH, IAT");
    }

    // ── Basic Auth decoder ────────────────────────────────────────────────

    /**
     * Decodes "Basic <base64(username:password)>" → [username, password].
     * Returns null if the header is malformed.
     */
    private String[] decodeBasicHeader(String authHeader) {
        try {
            String encoded  = authHeader.substring(BASIC_PREFIX.length()).trim();
            String decoded  = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int    colonIdx = decoded.indexOf(':');
            if (colonIdx < 1) return null;
            return new String[]{ decoded.substring(0, colonIdx),
                    decoded.substring(colonIdx + 1) };
        } catch (IllegalArgumentException e) {
            log.warn("Failed to decode Basic auth header", e);
            return null;
        }
    }

    // ── AES password decryptor ────────────────────────────────────────────

    // @SneakyThrows on AESEncryptionPlugin.decrypt() rethrows crypto failures as unchecked
    // RuntimeExceptions — broad catch is intentional, not lazy
    private String decryptPassword(String encryptedPassword) {
        try {
            return encryptionPlugin.decrypt(encryptedPassword, encryptionAlgorithm, encryptionSecretKey);
        } catch (Exception e) {
            log.warn("AES decryption of Basic Auth password failed — possible malformed or non-encrypted payload", e);
            return null;
        }
    }

    // ── Error helper ──────────────────────────────────────────────────────

    private void writeError(HttpServletResponse response,
                            int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", LocalDateTime.now().toString());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}