package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.BngEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BngRepository extends JpaRepository<BngEntity, String>,
        JpaSpecificationExecutor<BngEntity> {
    boolean existsByBngId(String bngId);
    boolean existsByBngName(String bngName);
    Optional<BngEntity> findByBngName(String bngName);
}