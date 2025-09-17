package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.exception.BadRequestException;
import com.logicnativesolution.servemeapi.mapper.RegisterUserMapper;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class RegisterUserService {
    private final UserRepository userRepository;
    private final RegisterUserMapper registerUserMapper;
    private final PasswordEncoder passwordEncoder;

    public User registerUser(RegisterUsersDto request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email has already been registered");
        }

        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new BadRequestException("Phone number has already been registered");
        }

        var userDto = registerUserMapper.toUserEntity(sanitizeData(request));
        userDto.setPassword(passwordEncoder.encode(request.getPassword()));

        return userDto;
    }

    private RegisterUsersDto sanitizeData(RegisterUsersDto request) {
        request.setEmail(request.getEmail().trim().toLowerCase());
        request.setFirstName(capitalize(request.getFirstName().trim()));
        request.setLastName(capitalize(request.getLastName().trim()));
        return request;
    }

    private String capitalize(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }
}


