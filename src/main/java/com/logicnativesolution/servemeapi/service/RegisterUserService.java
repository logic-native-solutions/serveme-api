package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.exception.BadRequestException;
import com.logicnativesolution.servemeapi.mapper.RegisterUserMapper;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;

@Service
@AllArgsConstructor
public class RegisterUserService {
    private final UserRepository userRepository;
    private final RegisterUserMapper registerUserMapper;
    private final PasswordEncoder passwordEncoder;

    public User registerUser(@Valid @RequestBody RegisterUsersDto request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email has already been registered");
        }

        var userDto = registerUserMapper.toUserEntity(request);
        userDto.setPassword(passwordEncoder.encode(request.getPassword()));
        return userRepository.save(userDto);
    }
}
