package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.Bucket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BucketRepository extends JpaRepository<Bucket,String> {

    Optional<Bucket> findByBucketId(String bucketId);
}
