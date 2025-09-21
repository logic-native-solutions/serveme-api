package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.dto.AddressDto;
import com.logicnativesolution.servemeapi.dto.PaymentDetailsDto;
import com.logicnativesolution.servemeapi.dto.ServiceAreaDto;
import com.logicnativesolution.servemeapi.dto.ServiceDto;
import com.logicnativesolution.servemeapi.mapper.ServiceMapper;
import com.logicnativesolution.servemeapi.mapper.UserAddressMapper;
import com.logicnativesolution.servemeapi.repository.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class ProfileService {
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final UserAddressMapper userAddressMapper;
    private final ServiceMapper serviceMapper;
    private final ServiceRepository serviceRepository;
    private final ServiceAreaRepository serviceAreaRepository;
    private final PaymentsDetailsRepository paymentsDetailsRepository;


    public AddressDto addUserAddress(AddressDto addressRequest) {
        var user = userRepository.findById(addressRequest.getUserId()).orElse(null);

        if(user == null) {
            throw new RuntimeException("User not found");
        }
        addressRepository.save(userAddressMapper.toAddressEntity(addressRequest));
        return addressRequest;
    }

    public ServiceDto addUserService(ServiceDto serviceRequest) {
        //search the user by id and add the service

        serviceRepository.save(serviceMapper.toServiceEntity(serviceRequest));
        return serviceRequest;
    }

    public ServiceAreaDto addUserServiceArea(ServiceAreaDto serviceAreaRequest) {
        serviceAreaRepository.save(serviceMapper.toServiceAreaEntity(serviceAreaRequest));
        return serviceAreaRequest;

    }

    public PaymentDetailsDto addUserPaymentDetails(PaymentDetailsDto paymentDetailsRequest) {
        paymentsDetailsRepository.save(serviceMapper.toPaymentsDetailsEntity(paymentDetailsRequest));

        return paymentDetailsRequest;
    }
}
