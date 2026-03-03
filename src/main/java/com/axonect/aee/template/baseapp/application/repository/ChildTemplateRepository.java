package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.ChildTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChildTemplateRepository extends JpaRepository<ChildTemplate, Long> {
    List<ChildTemplate> findBySuperTemplateId(Long superTemplateId);

    @Query("SELECT DISTINCT c.quotaPercentage FROM ChildTemplate c WHERE c.superTemplateId = :superTemplateId AND c.quotaPercentage IS NOT NULL AND c.messageType = 'USAGE'")
    List<Integer> findUsedQuotaPercentagesBySuperTemplateId(@Param("superTemplateId") Long superTemplateId);
}