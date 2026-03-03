package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActionLogRepository extends JpaRepository<ActionLog , Long>, JpaSpecificationExecutor<ActionLog> {


}
