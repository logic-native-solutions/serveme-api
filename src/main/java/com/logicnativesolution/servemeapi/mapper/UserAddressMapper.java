package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.AddressDto;
import com.logicnativesolution.servemeapi.entities.Address;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserAddressMapper{
    Address toAddressEntity(AddressDto address);
}
