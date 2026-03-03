package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserToMac;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserToMacRepository extends JpaRepository<UserToMac, Long> {

    List<UserToMac> findByUserName(String userName);

    // Case-insensitive check using LOWER function
    @Query("SELECT COUNT(m) > 0 FROM UserToMac m WHERE LOWER(m.macAddress) = LOWER(:macAddress)")
    boolean existsByMacAddress(@Param("macAddress") String macAddress);

    @Query("SELECT COUNT(m) > 0 FROM UserToMac m WHERE LOWER(m.macAddress) = LOWER(:macAddress) AND m.userName != :userName")
    boolean existsByMacAddressAndUserNameNot(@Param("macAddress") String macAddress, @Param("userName") String userName);

    @Transactional
    void deleteByUserName(String userName);

    // SOLUTION: Get all MAC addresses, let service handle case-insensitive comparison
    // This is simpler and database-agnostic
    @Query("SELECT m FROM UserToMac m WHERE m.macAddress IN :macAddresses")
    List<UserToMac> findByMacAddressIn(@Param("macAddresses") List<String> macAddresses);

    @Query("SELECT m FROM UserToMac m WHERE m.macAddress IN :macAddresses AND m.userName != :userName")
    List<UserToMac> findByMacAddressInAndUserNameNot(@Param("macAddresses") List<String> macAddresses, @Param("userName") String userName);
}