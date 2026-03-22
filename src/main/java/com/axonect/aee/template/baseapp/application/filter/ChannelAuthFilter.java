package com.axonect.aee.template.baseapp.application.filter;

import com.axonect.aee.template.baseapp.domain.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelAuthFilter extends OncePerRequestFilter {

    private static final String CHANNEL_HEADER = "channel";
    private static final String IAT             = "IAT";
    private static final String SRH             = "SRH";
    private static final String AUTH_HEADER     = "Authorization";
    private static final String BEARER_PREFIX   = "Bearer ";

    /**
     * Only these path prefixes are protected by channel auth.
     * Any path NOT in this list is completely ignored by this filter.
     */
    private static final List<String> PROTECTED_PATHS = List.of(
            /*"/api/user",
            "/api/services"*/
    );

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    // ── Skip filter for anything outside the protected paths ──────────────

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();

        // Always skip the auth endpoints themselves
        if (uri.startsWith("/api/auth/")) {
            return true;
        }

        // Only run filter for explicitly protected paths
        boolean isProtected = PROTECTED_PATHS.stream()
                .anyMatch(uri::startsWith);

        if (!isProtected) {
            log.debug("Path '{}' is not protected — skipping channel filter", uri);
        }

        return !isProtected;
    }

    // ── Main gate logic ───────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String channel = request.getHeader(CHANNEL_HEADER);

        // ── 1. Channel header must be present ─────────────────────────────
        if (channel == null || channel.isBlank()) {
            writeError(response, 400, "MISSING_CHANNEL",
                    "Required header 'channel' is missing. Accepted values: SRH, IAT");
            return;
        }

        // ── 2. SRH — internal channel, bypass token check ─────────────────
        if (SRH.equalsIgnoreCase(channel)) {
            log.debug("SRH channel — skipping token validation for {}", request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        // ── 3. IAT — external channel, token is mandatory ─────────────────
        if (IAT.equalsIgnoreCase(channel)) {
            String authHeader = request.getHeader(AUTH_HEADER);

            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                writeError(response, 401, "MISSING_TOKEN",
                        "Authorization Bearer token is required for channel IAT");
                return;
            }

            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            if (!tokenService.validate(token)) {
                writeError(response, 401, "INVALID_TOKEN",
                        "Token is invalid or has expired. Please re-authenticate.");
                return;
            }

            String username = tokenService.usernameFromToken(token);
            request.setAttribute("authenticatedUser", username);
            log.debug("IAT channel — token valid for user='{}', path={}",
                    username, request.getRequestURI());

            chain.doFilter(request, response);
            return;
        }

        // ── 4. Unknown channel value ───────────────────────────────────────
        writeError(response, 400, "INVALID_CHANNEL",
                "Invalid channel value '" + channel + "'. Accepted values: SRH, IAT");
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