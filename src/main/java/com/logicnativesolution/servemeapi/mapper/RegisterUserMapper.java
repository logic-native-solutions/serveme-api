package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.user.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.Role;
import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.RoleRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class RegisterUserMapper {
    // Inject RoleRepository so MapStruct-generated mapper can resolve role strings to Role entities
    @Autowired
    protected RoleRepository roleRepository;

    // MapStruct will use the 'map(String)' helper to convert the incoming role string to a Role entity
    @Mapping(target = "role", source = "role")
    public abstract User toUserEntity(RegisterUsersDto request);

    // Helper used by MapStruct for role conversion
    protected Role map(String role) {
        if (role == null || role.trim().isEmpty()) {
            return null;
        }
        // Defensive: if repository is not yet injected for some reason, avoid NPE and let caller set role later
        if (roleRepository == null) {
            return null;
        }
        return roleRepository.findByName(role.trim().toUpperCase());
    }
}
