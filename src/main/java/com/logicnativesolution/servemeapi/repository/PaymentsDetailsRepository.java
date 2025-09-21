package com.logicnativesolution.servemeapi.repository;

import com.logicnativesolution.servemeapi.entities.PaymentsDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentsDetailsRepository extends JpaRepository<PaymentsDetails, UUID> {
}
