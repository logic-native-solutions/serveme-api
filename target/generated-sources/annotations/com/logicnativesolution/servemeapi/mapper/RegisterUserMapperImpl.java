package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.entities.User;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2025-09-16T11:27:07+0200",
    comments = "version: 1.6.3, compiler: javac, environment: Java 24.0.2 (Oracle Corporation)"
)
@Component
public class RegisterUserMapperImpl implements RegisterUserMapper {

    @Override
    public User toUserEntity(RegisterUsersDto request) {
        if ( request == null ) {
            return null;
        }

        User user = new User();

        user.setIdNumber( request.getIdNumber() );
        user.setFirstName( request.getFirstName() );
        user.setLastName( request.getLastName() );
        user.setEmail( request.getEmail() );
        user.setPhoneNumber( request.getPhoneNumber() );
        user.setDateOfBirth( request.getDateOfBirth() );
        user.setGender( request.getGender() );
        user.setPassword( request.getPassword() );

        return user;
    }
}
