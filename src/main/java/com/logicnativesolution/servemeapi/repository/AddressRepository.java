package com.logicnativesolution.servemeapi.repository;

import com.logicnativesolution.servemeapi.entities.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {
}
