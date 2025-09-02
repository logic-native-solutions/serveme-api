package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RegisterUserMapper {
    User toUserEntity(RegisterUsersDto request);
}
