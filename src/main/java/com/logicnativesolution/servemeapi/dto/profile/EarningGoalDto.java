package com.logicnativesolution.servemeapi.dto.profile;

import lombok.Data;

/**
 * DTO for Provider Earning Goal payloads.
 * amount: integer minor units (>=0)
 * currency: lowercase ISO currency code (3-5 chars)
 * period: "week" | "month"
 * startDate: optional ISO date (YYYY-MM-DD)
 */
@Data
public class EarningGoalDto {
    private Integer amount; // minor units
    private String currency;
    private String period; // week | month
    private String startDate; // YYYY-MM-DD or null
}
