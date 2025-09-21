package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.AddressDto;
import com.logicnativesolution.servemeapi.dto.PaymentDetailsDto;
import com.logicnativesolution.servemeapi.dto.ServiceAreaDto;
import com.logicnativesolution.servemeapi.dto.ServiceDto;
import com.logicnativesolution.servemeapi.service.ProfileService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@AllArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @PostMapping("/address")
    public ResponseEntity<AddressDto> addAddress(@Valid @RequestBody AddressDto addressRequest){
        var userAddress = profileService.addUserAddress(addressRequest);
        return ResponseEntity.ok(userAddress);
    }

    @PostMapping("/service")
    public ResponseEntity<ServiceDto> service(@Valid @RequestBody ServiceDto serviceRequest){
        var userService = profileService.addUserService(serviceRequest);
        return ResponseEntity.ok(userService);
    }

    @PostMapping("/service-area")
    public ResponseEntity<ServiceAreaDto> serviceArea(@Valid @RequestBody ServiceAreaDto serviceAreaRequest){
        var userServiceArea = profileService.addUserServiceArea(serviceAreaRequest);
        return ResponseEntity.ok(userServiceArea);
    }
    @PostMapping("/payment-details")
    public ResponseEntity<PaymentDetailsDto> paymentDetails(@Valid @RequestBody PaymentDetailsDto paymentDetailsRequest){
        var userPaymentsDetails = profileService.addUserPaymentDetails(paymentDetailsRequest);
        return ResponseEntity.ok(userPaymentsDetails);
    }
}
