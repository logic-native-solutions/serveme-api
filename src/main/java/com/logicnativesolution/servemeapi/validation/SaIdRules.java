package com.logicnativesolution.servemeapi.validation;

public class SaIdRules {
    public static boolean isValidSouthAfricanId(String id) {
        if (id == null || !id.matches("\\d{13}")) return false;
        int sum = 0;
        boolean doubleIt = false;
        for (int i = id.length() - 1; i >= 0; i--) {
            int d = id.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
