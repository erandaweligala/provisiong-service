package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.AuthCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AuthCredentialRepository extends JpaRepository<AuthCredential, Long> {
    Optional<AuthCredential> findByUsernameAndActiveTrue(String username);
}