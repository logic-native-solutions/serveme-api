package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@AllArgsConstructor
@Service
public class CurrentAuthUser {
    private final HomeService homeService;
    private final UserRepository userRepository;

    public User getAuthUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUser = homeService.getCurrentAuthenticatedUser(auth);
        return userRepository.findByEmail(authenticatedUser.getEmail()).orElse(null);
    }
}
