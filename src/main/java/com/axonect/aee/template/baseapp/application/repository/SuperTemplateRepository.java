package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.SuperTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SuperTemplateRepository extends JpaRepository<SuperTemplate, Long>, JpaSpecificationExecutor<SuperTemplate> {
    boolean existsByTemplateName(String templateName);
    boolean existsByIsDefault(Boolean isDefault);
    SuperTemplate findByTemplateName(String templateName);
    Optional<SuperTemplate> findByIsDefault(Boolean isDefault);
}