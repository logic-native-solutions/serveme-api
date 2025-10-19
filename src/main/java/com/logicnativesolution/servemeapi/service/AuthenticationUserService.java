package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
@Service
public class AuthenticationUserService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmailIgnoreCase(email).orElseThrow(
                () -> new UsernameNotFoundException("User not found with email: " + email)
        );

        List<GrantedAuthority> authorities = new ArrayList<>();
        String roleName = user.getRole() != null && user.getRole().getName() != null
                ? user.getRole().getName().trim().toUpperCase()
                : "USER";
        // Ensure Spring Security's ROLE_ prefix convention
        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));

        return new User(
                user.getEmail(),
                user.getPassword(),
                authorities
        );
    }
}
