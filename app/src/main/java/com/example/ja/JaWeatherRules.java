package com.example.ja;

import java.util.Locale;

/** Unit conversion and validation shared by current-condition displays. */
public final class JaWeatherRules {
    private JaWeatherRules() {}
    public static boolean validZip(String zip) { return zip != null && zip.matches("[0-9]{5}"); }
    public static boolean activeActualAlert(String status, String messageType, String expires, long nowMillis) {
        if (!"Actual".equals(status) || "Cancel".equals(messageType)) return false;
        try { return java.time.Instant.parse(expires).toEpochMilli() > nowMillis; }
        catch (Exception e) { return false; }
    }
    public static Double fahrenheit(Double value, String unit) {
        if (value == null || !Double.isFinite(value)) return null;
        if (unit.endsWith(":degC")) return value * 9 / 5 + 32;
        return unit.endsWith(":degF") ? value : null;
    }
    public static Double milesPerHour(Double value, String unit) {
        if (value == null || !Double.isFinite(value)) return null;
        if (unit.endsWith(":km_h-1")) return value / 1.609344;
        if (unit.endsWith(":m_s-1")) return value * 2.2369362921;
        if (unit.endsWith(":kn")) return value * 1.150779448;
        return unit.endsWith(":mi_h-1") ? value : null;
    }
    public static Double distance(Double value, String unit, boolean feet) {
        if (value == null || !Double.isFinite(value)) return null;
        double meters;
        if (unit.endsWith(":m")) meters = value;
        else if (unit.endsWith(":km")) meters = value * 1000;
        else if (unit.endsWith(":ft")) meters = value * 0.3048;
        else if (unit.endsWith(":mi")) meters = value * 1609.344;
        else return null;
        return feet ? meters / 0.3048 : meters / 1609.344;
    }
    public static Double inchesMercury(Double value, String unit) {
        if (value == null || !Double.isFinite(value)) return null;
        if (unit.endsWith(":Pa")) return value / 3386.389;
        if (unit.endsWith(":hPa")) return value / 33.86389;
        return unit.endsWith(":inHg") ? value : null;
    }
    public static String number(Double value, int decimals, String suffix) {
        if (value == null || !Double.isFinite(value)) return "—";
        return String.format(Locale.US, "%1$." + decimals + "f", value) + suffix;
    }
    public static String direction(Double degrees) {
        if (degrees == null || !Double.isFinite(degrees)) return "—";
        String[] points = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        double normalized = (degrees % 360 + 360) % 360;
        return points[(int)Math.round(normalized / 22.5) % 16];
    }
}
