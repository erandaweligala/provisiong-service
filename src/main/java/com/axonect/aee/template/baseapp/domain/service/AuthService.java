package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.AuthCredentialRepository;
import com.axonect.aee.template.baseapp.application.transport.request.entities.CreateCredentialRequest;
import com.axonect.aee.template.baseapp.domain.entities.dto.AuthCredential;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import com.axonect.aee.template.baseapp.domain.util.LogMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String CREATE = "CREATE";
    private static final String DELETE = "DELETE";

    private final AuthCredentialRepository authCredentialRepository;
    private final PasswordEncoder          passwordEncoder;
    private final EventMapper              eventMapper;
    private final KafkaEventPublisher      kafkaEventPublisher;
    // TokenService removed — no tokens to manage with Basic Auth

    // ── Create Credential ──────────────────────────────────────────────────

    public String createCredential(CreateCredentialRequest request) {
        if (authCredentialRepository.findByUsernameAndActiveTrue(request.getUsername()).isPresent()) {
            throw new AAAException(
                    "AUTH_002",
                    "Username '" + request.getUsername() + "' already exists",
                    HttpStatus.CONFLICT
            );
        }

        LocalDateTime now = LocalDateTime.now();

        AuthCredential credential = AuthCredential.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.info("Publishing credential creation event for user '{}'", credential.getUsername());
        publishCredentialCreatedEvent(credential);

        return credential.getUsername();
    }

    // ── Delete (deactivate) Credential ─────────────────────────────────────

    public void deleteCredential(String username) {
        AuthCredential credential = authCredentialRepository
                .findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new AAAException(
                        "AUTH_003",
                        "Active credential not found for username '" + username + "'",
                        HttpStatus.NOT_FOUND
                ));

        credential.setActive(false);
        credential.setUpdatedAt(LocalDateTime.now());

        log.info("Publishing credential deletion event for user '{}'", username);
        publishCredentialDeletedEvent(credential);
    }

    // ── Kafka publish helpers (unchanged) ──────────────────────────────────

    private void publishCredentialCreatedEvent(AuthCredential credential) {
        try {
            DBWriteRequestGeneric dbEvent = eventMapper.toCredentialDBWriteEvent(CREATE, credential);
            PublishResult dbResult = kafkaEventPublisher.publishCredentialDBWriteEvent(dbEvent);

            if (dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing credential creation event for user '{}'",
                        credential.getUsername());
                throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                        "Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            if (!dbResult.isSuccess()) {
                log.warn("Partial failure publishing credential creation event for user '{}'",
                        credential.getUsername());
            }
        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish credential created event for '{}'",
                    credential.getUsername(), e);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                    "Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void publishCredentialDeletedEvent(AuthCredential credential) {
        try {
            DBWriteRequestGeneric dbEvent = eventMapper.toCredentialDBWriteEvent(DELETE, credential);
            PublishResult dbResult = kafkaEventPublisher.publishCredentialDBWriteEvent(dbEvent);

            if (dbResult.isCompleteFailure()) {
                log.error("Complete failure publishing credential deletion event for user '{}'",
                        credential.getUsername());
                throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                        "Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            if (!dbResult.isSuccess()) {
                log.warn("Partial failure publishing credential deletion event for user '{}'",
                        credential.getUsername());
            }
        } catch (AAAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish credential deleted event for '{}'",
                    credential.getUsername(), e);
            throw new AAAException(LogMessages.ERROR_INTERNAL_ERROR,
                    "Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}