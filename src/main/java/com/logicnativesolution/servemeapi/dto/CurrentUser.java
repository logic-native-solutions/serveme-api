package com.logicnativesolution.servemeapi.dto;

import com.logicnativesolution.servemeapi.entities.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Component
public class CurrentUser {
    private User user;
}
