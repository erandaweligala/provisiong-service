package com.axonect.aee.template.baseapp.application.repository;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserEntity;
import com.axonect.aee.template.baseapp.domain.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String>, JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByUserName(String userName);
    Optional<UserEntity> findByUserId(String userId);
    boolean existsByUserName(String userName);
    boolean existsByRequestId(String requestId);
    Page<UserEntity> findByStatus(UserStatus userStatus, Pageable pageable);
    Page<UserEntity> findByGroupId(String groupId, Pageable pageable);
    Optional<UserEntity> findFirstByGroupId(String groupId);
    Page<UserEntity> findByGroupIdAndStatus(String groupId, UserStatus userStatus, Pageable pageable);
    boolean existsByGroupId(String groupId);
    boolean existsByUserId(String userId);

    @Query(value = "SELECT GROUP_ID FROM AAA_USER WHERE USER_NAME = :username", nativeQuery = true)
    String findGroupIdByUsername(@Param("username") String username);

    // NEW: Projection query for getUserList()
    @Query("SELECT u.userName FROM UserEntity u")
    List<String> findAllUserNames();


    /**
     * Find all users in a specific group
     * @param groupId the group ID to search
     * @return List of users in the group
     */
    List<UserEntity> findAllByGroupId(String groupId);

    @Query(value = """
    SELECT u.*, t.TEMPLATE_NAME 
    FROM AAA_USER u 
    LEFT JOIN SUPER_TEMPLATE_TABLE t ON u.TEMPLATE_ID = t.ID 
    WHERE u.USER_NAME = :userName
    """, nativeQuery = true)
    Optional<Object[]> findByUserNameWithTemplate(@Param("userName") String userName);

    @Query(value = """
    SELECT u.*, t.TEMPLATE_NAME 
    FROM AAA_USER u 
    LEFT JOIN SUPER_TEMPLATE_TABLE t ON u.TEMPLATE_ID = t.ID 
    WHERE u.USER_ID = :userId
    """, nativeQuery = true)
    Optional<Object[]> findByUserIdWithTemplate(@Param("userId") String userId);

    boolean existsByTemplateId(Long templateId);
}
