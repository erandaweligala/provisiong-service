package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo.BucketInfoProjection;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketFlatProjection;
import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import java.util.Optional;

public interface BucketInstanceRepository extends JpaRepository<BucketInstance,Long> {
    Optional<BucketInstance> findFirstByServiceIdOrderByPriorityAsc(Long serviceId);

    @Query(value = """
        SELECT
            bi.BUCKET_ID AS bucketId,
            bi.INITIAL_BALANCE AS initialBalance,
            bi.USAGE AS usage,
            bi.CURRENT_BALANCE AS currentBalance,
            bi.PRIORITY AS priority,
            bi.EXPIRATION AS expiration,
            qp.UPLINK_SPEED AS uplinkSpeed,
            qp.DOWNLINK_SPEED AS downlinkSpeed

        FROM BUCKET_INSTANCE bi
        JOIN BUCKET b ON bi.BUCKET_ID = b.BUCKET_ID
        JOIN QOS_PROFILE qp ON b.QOS_ID = qp.ID

        WHERE bi.SERVICE_ID = :serviceId
        """,
            nativeQuery = true)
    List<BucketInfoProjection> findBucketInfoWithSpeed(@Param("serviceId") Long serviceId);

    /**
     * Find all bucket instances for a given service ID
     */
    List<BucketInstance> findByServiceId(Long serviceId);

    /**
     * Delete all bucket instances for given service IDs
     */
    @Modifying
    @Query("DELETE FROM BucketInstance bi WHERE bi.serviceId IN :serviceIds")
    int deleteByServiceIdIn(@Param("serviceIds") List<Long> serviceIds);

    /**
     * Count bucket instances for given service IDs
     */
    long countByServiceIdIn(List<Long> serviceIds);

    @Query("""
        SELECT new com.axonect.aee.template.baseapp.domain.entities.dto.BucketFlatProjection(
            si.id,
            si.username,
            si.billing,
            si.status,
            si.isGroup,
            si.planId,
            p.planName,
            p.planType,
            p.recurringPeriod,
            b.bucketId,
            b.priority,
            b.initialBalance,
            b.currentBalance,
            b.usage,
            b.updatedAt,
            q.downLink,
            q.upLink
        )
        FROM BucketInstance b
        JOIN ServiceInstance si ON b.serviceId = si.id
        JOIN QOSProfile q       ON b.rule       = q.bngCode
        JOIN Plan p             ON si.planId    = p.planId
        WHERE si.username IN :usernames
        AND b.id = (
            SELECT b2.id
            FROM BucketInstance b2
            WHERE b2.serviceId = si.id
            ORDER BY b2.priority ASC
            FETCH FIRST 1 ROW ONLY
        )
        ORDER BY si.username, si.id
        """)
    List<BucketFlatProjection> findFlatBucketDetailsByUsernames(@Param("usernames") List<String> usernames);
}
