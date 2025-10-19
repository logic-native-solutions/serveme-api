package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.controller.HomeController;
import com.logicnativesolution.servemeapi.dto.*;
import com.logicnativesolution.servemeapi.dto.profile.EditProfileDto;
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
    private final HomeController homeController;
    private final RegisterUserService registerUserService;



    public void editProfile(EditProfileDto editProfileDto){
        var user = userRepository.findByEmail(homeController.getCurrentUserEmail()).orElse(null);

        if (user == null)
            throw new RuntimeException("User not found");

        if (user.getEmail().equals(editProfileDto.getEmail()))
            throw new RuntimeException("Email cannot be same as current email");
        if (user.getFirstName().equals(editProfileDto.getFirstName()))
            throw new RuntimeException("First name cannot be same as current first name");
        if (user.getLastName().equals(editProfileDto.getLastName()))
            throw new RuntimeException("Last name cannot be same as current last name");
        if (user.getPhoneNumber().equals(editProfileDto.getPhone()))
            throw new RuntimeException("Phone cannot be same as current phone number");

        user.setFirstName(registerUserService.capitalize(editProfileDto.getFirstName()).trim());
        user.setLastName(registerUserService.capitalize(editProfileDto.getLastName()).trim());
        user.setEmail(editProfileDto.getEmail().toLowerCase().trim());
        user.setPhoneNumber(editProfileDto.getPhone());

        userRepository.save(user);
    }

    public AddressDto addUserAddress(AddressDto addressRequest) {
        var user = userRepository.findByEmail(homeController.getCurrentUserEmail()).orElse(null);

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
