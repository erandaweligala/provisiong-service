package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    /**
     * Forward index  — auth:token:<jwt>   →  <username>   (STRING, TTL = token TTL)
     * Reverse index  — auth:user:<name>   →  {<jwt>, ...} (SET,    TTL = token TTL, refreshed on each login)
     */
    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final String USER_KEY_PREFIX  = "auth:user:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtUtil jwtUtil;

    // ── Generate & Store ───────────────────────────────────────────────────

    /**
     * Generates a JWT for {@code username}, writes both Redis indexes, and
     * returns the raw token string.
     */
    public String generateAndStore(String username) {
        String token = jwtUtil.generateToken(username);
        long   ttlMs = jwtUtil.getExpirationMs();

        // 1. Forward: token → username  (with TTL so Redis auto-expires it)
        redisTemplate.opsForValue()
                .set(tokenKey(token), username, ttlMs, TimeUnit.MILLISECONDS);

        // 2. Reverse: username → Set<token>  (TTL kept in sync with the token)
        String userKey = userKey(username);
        redisTemplate.opsForSet().add(userKey, token);
        redisTemplate.expire(userKey, ttlMs, TimeUnit.MILLISECONDS);

        log.info("Token stored for user='{}', TTL={}ms", username, ttlMs);
        return token;
    }

    // ── Validate ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} only when:
     * <ol>
     *   <li>The token exists in the Redis forward index (not expired / not revoked)</li>
     *   <li>The JWT signature and expiry are still valid</li>
     * </ol>
     */
    public boolean validate(String token) {
        try {
            String stored = redisTemplate.opsForValue().get(tokenKey(token));

            if (stored == null) {
                log.debug("Token not found in Redis — expired or revoked");
                return false;
            }

            boolean jwtOk = jwtUtil.isTokenValid(token);

            if (!jwtOk) {
                // JWT expired but Redis entry somehow still exists — clean up both indexes
                cleanupToken(token, stored);
                log.debug("JWT expired — stale Redis entries removed for user='{}'", stored);
            }

            return jwtOk;

        } catch (Exception e) {
            log.error("Token validation error: {}", e.getMessage());
            return false;
        }
    }

    // ── Revoke single token ────────────────────────────────────────────────

    /**
     * Immediately invalidates one token by removing it from both Redis indexes.
     * Used on logout.
     */
    public void revoke(String token) {
        try {
            String username = redisTemplate.opsForValue().get(tokenKey(token));
            cleanupToken(token, username);
            log.info("Token revoked for user='{}'", username != null ? username : "unknown");
        } catch (Exception e) {
            log.error("Error revoking token: {}", e.getMessage());
        }
    }

    // ── Revoke all tokens for a user ───────────────────────────────────────

    /**
     * Revokes every active token that belongs to {@code username}.
     * Called when a credential is deactivated so no live sessions remain.
     */
    public void revokeAllForUser(String username) {
        try {
            String      userKey = userKey(username);
            Set<String> tokens  = redisTemplate.opsForSet().members(userKey);

            if (tokens == null || tokens.isEmpty()) {
                log.info("No active tokens found for user='{}'", username);
                return;
            }

            // Delete every forward-index entry
            tokens.forEach(t -> redisTemplate.delete(tokenKey(t)));

            // Delete the reverse-index set itself
            redisTemplate.delete(userKey);

            log.info("Revoked {} token(s) for user='{}'", tokens.size(), username);

        } catch (Exception e) {
            log.error("Error revoking all tokens for user='{}': {}", username, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    public String usernameFromToken(String token) {
        return jwtUtil.extractUsername(token);
    }

    public long expirationSeconds() {
        return jwtUtil.getExpirationMs() / 1000;
    }

    /**
     * Removes a token from the forward index and, if {@code username} is known,
     * from the reverse index set too.
     */
    private void cleanupToken(String token, String username) {
        redisTemplate.delete(tokenKey(token));

        if (username != null) {
            redisTemplate.opsForSet().remove(userKey(username), token);

            // If the set is now empty, remove the key entirely to avoid orphan keys
            Long remaining = redisTemplate.opsForSet().size(userKey(username));
            if (remaining != null && remaining == 0) {
                redisTemplate.delete(userKey(username));
            }
        }
    }

    private String tokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    private String userKey(String username) {
        return USER_KEY_PREFIX + username;
    }
}