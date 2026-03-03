package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.VendorConfig;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorConfigRepository extends JpaRepository<VendorConfig, Long>,
        JpaSpecificationExecutor<VendorConfig> {

    /**
     * Check if a vendor config exists with the given vendorId and attributeId
     * Used for duplicate validation during create
     */
    boolean existsByVendorIdAndAttributeId(String vendorId, String attributeId);

    /**
     * Check if a vendor config exists with the given vendorId and attributeName
     * Used for duplicate validation during create
     */
    boolean existsByVendorIdAndAttributeName(String vendorId, String attributeName);

    /**
     * Find a vendor config by vendorId and attributeId
     * Useful for update operations
     */
    Optional<VendorConfig> findByVendorIdAndAttributeId(String vendorId, String attributeId);

    /**
     * Find a vendor config by vendorId and attributeName
     * Useful for update operations
     */
    Optional<VendorConfig> findByVendorIdAndAttributeName(String vendorId, String attributeName);

    /**
     * Check if any vendor config exists for a given vendorId
     * Useful for validation
     */
    boolean existsByVendorId(String vendorId);

    /**
     * Check if an active vendor config exists for a given vendorId
     */
    boolean existsByVendorIdAndIsActiveTrue(String vendorId);

    /**
     * Find all vendor configs by vendorId
     */
    List<VendorConfig> findByVendorId(String vendorId);

    /**
     * Find all vendor configs by vendorId with sorting
     */
    List<VendorConfig> findByVendorId(String vendorId, Sort sort);
}