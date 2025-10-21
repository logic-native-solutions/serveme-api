package com.logicnativesolution.servemeapi.util;

/**
 * Minimal Geohash encoder (base32) for server-side proximity features.
 * No external dependencies. Precision 5–8 recommended for city-level radius queries.
 */
public final class GeohashUtil {
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private GeohashUtil() {}

    public static String encode(double latitude, double longitude, int precision) {
        if (precision <= 0) precision = 7; // sensible default
        double[] lat = { -90.0, 90.0 };
        double[] lon = { -180.0, 180.0 };
        StringBuilder hash = new StringBuilder();
        int bit = 0;
        int ch = 0;
        boolean evenBit = true;

        while (hash.length() < precision) {
            if (evenBit) {
                double mid = (lon[0] + lon[1]) / 2;
                if (longitude >= mid) { ch |= 1 << (4 - bit); lon[0] = mid; }
                else { lon[1] = mid; }
            } else {
                double mid = (lat[0] + lat[1]) / 2;
                if (latitude >= mid) { ch |= 1 << (4 - bit); lat[0] = mid; }
                else { lat[1] = mid; }
            }

            evenBit = !evenBit;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }
}
