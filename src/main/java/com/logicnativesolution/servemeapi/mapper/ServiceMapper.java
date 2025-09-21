package com.logicnativesolution.servemeapi.mapper;

import com.logicnativesolution.servemeapi.dto.PaymentDetailsDto;
import com.logicnativesolution.servemeapi.dto.ServiceAreaDto;
import com.logicnativesolution.servemeapi.dto.ServiceDto;
import com.logicnativesolution.servemeapi.entities.PaymentsDetails;
import com.logicnativesolution.servemeapi.entities.Service;
import com.logicnativesolution.servemeapi.entities.ServiceArea;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ServiceMapper {
    Service toServiceEntity(ServiceDto serviceDtoRequest);
    ServiceArea toServiceAreaEntity(ServiceAreaDto serviceAreaDtoRequest);
    PaymentsDetails toPaymentsDetailsEntity(PaymentDetailsDto paymentsDetailsDtoRequest);
}
