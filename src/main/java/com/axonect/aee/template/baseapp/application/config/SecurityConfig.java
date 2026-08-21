package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.filter.ChannelAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ChannelAuthFilter channelAuthFilter;
    private final ObjectMapper      objectMapper;

    /*@Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }*/

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(channelAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .headers(headers -> headers

                        // X-XSS-Protection (disabled by default in Spring Security 6)
                        .xssProtection(xss -> xss
                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK)
                        )

                        // Prevents MIME type sniffing attacks
                        .contentTypeOptions(contentType -> {})

                        // Prevents clickjacking via iframe embedding
                        .frameOptions(frame -> frame.deny())

                        // Forces HTTPS forever via browser preload list
                        //    max-age    → resets 365 day clock on every response
                        //    preload    → registers in Chrome/Firefox/Edge built-in
                        //                 HSTS list (HTTPS forced even before first visit)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                                .preload(true)
                        )

                        // Restricts resource loading, blocks XSS & data injection
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives(
                                        "default-src 'none'; " +
                                                "frame-ancestors 'none'; " +
                                                "script-src 'self'; " +
                                                "style-src 'self'"
                                )
                        )

                        // Stops referrer info leaking to other origins
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)
                        )

                        // Restricts browser features (camera, mic, geolocation etc.)
                        .permissionsPolicy(permissions -> permissions
                                .policy(
                                        "camera=(), " +
                                                "microphone=(), " +
                                                "geolocation=(), " +
                                                "payment=()"
                                )
                        )
                );

        return http.build();
    }

    // Handles RequestRejectedException from StrictHttpFirewall (e.g. %2F in path)
    // Without this, rejected requests fall through to the container's default error page → 500
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return (request, response, ex) -> {
            response.setStatus(400);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error_code", "AAA_400_BAD_REQUEST");
            body.put("message", "Input contains invalid content.");
            body.put("timestamp", Instant.now().toString());

            response.getWriter().write(objectMapper.writeValueAsString(body));
        };
    }
}