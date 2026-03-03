package com.axonect.aee.template.baseapp.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())  // Disable CSRF for API endpoints
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/user/**").permitAll()
                        .requestMatchers("/api/services/**").permitAll() // Allow all requests to /api/user/**
                        .requestMatchers("/api/bng/**").permitAll()
                        .requestMatchers("/api/logs/**").permitAll()
                        .requestMatchers("/api/vendor-configs/**").permitAll()
                        .requestMatchers("/api/notification-templates/**").permitAll()
                        .requestMatchers("/api/entity-metadata/**").permitAll()
                        .anyRequest().authenticated()  // Require authentication for other endpoints
                );
        return http.build();
    }
}
