package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.User;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2025-09-19T12:24:36+0200",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.43.0.v20250819-1513, environment: Java 21.0.8 (Eclipse Adoptium)"
)
@Component
public class RegisterUserMapperImpl implements RegisterUserMapper {

    @Override
    public User toUserEntity(RegisterUsersDto request) {
        if ( request == null ) {
            return null;
        }

        User user = new User();

        user.setDateOfBirth( request.getDateOfBirth() );
        user.setEmail( request.getEmail() );
        user.setFirstName( request.getFirstName() );
        user.setGender( request.getGender() );
        user.setIdNumber( request.getIdNumber() );
        user.setLastName( request.getLastName() );
        user.setPassword( request.getPassword() );
        user.setPhoneNumber( request.getPhoneNumber() );

        return user;
    }
}
