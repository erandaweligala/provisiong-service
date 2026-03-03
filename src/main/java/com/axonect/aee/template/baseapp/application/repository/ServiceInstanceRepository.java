package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.ServiceInfoProjection;
import com.axonect.aee.template.baseapp.domain.entities.dto.ServiceInstance;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

import java.util.List;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance,Long> {
    boolean existsByUsernameAndPlanId(String userName, String planId);

    boolean existsByRequestId(@NotBlank(message = "Request ID is mandatory") String requestId);

    @Modifying
    @Query("UPDATE ServiceInstance si SET si.status = 'Inactive', si.updatedAt = CURRENT_TIMESTAMP WHERE si.username = :username AND si.planId = :planId AND si.status IN ('Active', 'Suspended')")
    int updateStatusToInactiveByUsernameAndPlanId(@Param("username") String username, @Param("planId") String planId);
    Optional<ServiceInstance> findFirstByUsernameAndPlanIdOrderByExpiryDateAsc(String username, String planId);


    @Query(
            value = "SELECT " +
                    "si.ID AS serviceId, " +
                    "si.USERNAME AS username, " +
                    "si.STATUS AS status, " +
                    "si.PLAN_ID AS planId, " +
                    "si.PLAN_TYPE AS planType, " +
                    "si.RECURRING_FLAG AS recurringFlag, " +
                    "si.NEXT_CYCLE_START_DATE AS nextCycleStartDate, " +
                    "si.EXPIRY_DATE AS expiryDate, " +
                    "si.SERVICE_START_DATE AS serviceStartDate, " +
                    "si.CYCLE_START_DATE AS currentCycleStartDate, " +
                    "si.CYCLE_END_DATE AS currentCycleEndDate, " +
                    "si.IS_GROUP AS isGroup " +
                    "FROM SERVICE_INSTANCE si " +
                    "WHERE si.USERNAME IN (:usernames) " +
                    "AND (:serviceId IS NULL OR si.ID = :serviceId) " +
                    "AND (:status IS NULL OR si.STATUS = :status) " +
                    "AND (:planId IS NULL OR si.PLAN_ID = :planId) " +
                    "AND (:planType IS NULL OR si.PLAN_TYPE = :planType) " +
                    "AND (:recurringFlag IS NULL OR si.RECURRING_FLAG = :recurringFlag)",

            countQuery = "SELECT COUNT(*) FROM SERVICE_INSTANCE si " +
                    "WHERE si.USERNAME IN (:usernames) " +
                    "AND (:serviceId IS NULL OR si.ID = :serviceId) " +
                    "AND (:status IS NULL OR si.STATUS = :status) " +
                    "AND (:planId IS NULL OR si.PLAN_ID = :planId) " +
                    "AND (:planType IS NULL OR si.PLAN_TYPE = :planType) " +
                    "AND (:recurringFlag IS NULL OR si.RECURRING_FLAG = :recurringFlag)",

            nativeQuery = true
    )
    Page<ServiceInfoProjection> searchServiceInfo(
            @Param("usernames") List<String> usernames,
            @Param("serviceId") String serviceId,
            @Param("status") String status,
            @Param("planId") String planId,
            @Param("planType") String planType,
            @Param("recurringFlag") Boolean recurringFlag,
            Pageable pageable
    );

    /**
     * Find all service instances for a given username
     */
    List<ServiceInstance> findByUsername(String username);

    /**
     * Delete all service instances for a given username
     */
    @Modifying
    @Query("DELETE FROM ServiceInstance si WHERE si.username = :username")
    int deleteByUsername(@Param("username") String username);

    /**
     * Count service instances for a given username
     */
    long countByUsername(String username);
}
