package com.logicnativesolution.servemeapi.repository;

import com.logicnativesolution.servemeapi.entities.Service;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {
}
