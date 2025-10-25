package com.logicnativesolution.servemeapi.service;


import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class HomeService {
    private final UserRepository userRepository;

    public User getCurrentAuthenticatedUser(Authentication authentication) {
        String currentUserEmail = authentication.getName();
        return userRepository
                .findByEmail(currentUserEmail)
                .orElseThrow(
                        () -> new UsernameNotFoundException("User not found: " + currentUserEmail)
                );
    }

}