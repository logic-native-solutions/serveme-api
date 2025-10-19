package com.logicnativesolution.servemeapi.repository;

import com.logicnativesolution.servemeapi.entities.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);

    // case-insensitive for safer lookups during login/token mint
    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByIdNumber(String idNumber);
}