package com.example.ja;

public class JaWeatherRulesTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void near(Double actual, double expected) { check(actual != null && Math.abs(actual - expected) < 0.001); }
    public static void main(String[] args) {
        check(JaWeatherRules.validZip("00501")); check(JaWeatherRules.validZip("70401"));
        check(!JaWeatherRules.validZip("7041")); check(!JaWeatherRules.validZip("70401-1234"));
        check(!JaWeatherRules.validZip("70a01")); check(!JaWeatherRules.validZip(null));
        near(JaWeatherRules.fahrenheit(0.0,"wmoUnit:degC"),32);
        near(JaWeatherRules.fahrenheit(20.0,"wmoUnit:degC"),68);
        check(JaWeatherRules.fahrenheit(null,"wmoUnit:degC") == null);
        check(JaWeatherRules.fahrenheit(20.0,"unknown") == null);
        near(JaWeatherRules.milesPerHour(16.09344,"wmoUnit:km_h-1"),10);
        near(JaWeatherRules.milesPerHour(1.0,"wmoUnit:m_s-1"),2.236936);
        near(JaWeatherRules.distance(1609.344,"wmoUnit:m",false),1);
        near(JaWeatherRules.distance(304.8,"wmoUnit:m",true),1000);
        near(JaWeatherRules.inchesMercury(101325.0,"wmoUnit:Pa"),29.921252);
        check(JaWeatherRules.direction(360.0).equals("N"));
        check(JaWeatherRules.direction(90.0).equals("E"));
        check(JaWeatherRules.direction(null).equals("—"));
        check(JaWeatherRules.number(null,0,"°F").equals("—"));
        check(JaWeatherRules.number(Double.NaN,0,"°F").equals("—"));
        check(JaWeatherRules.number(0.0,0,"°F").equals("0°F"));
        long now = java.time.Instant.parse("2026-11-19T15:00:00Z").toEpochMilli();
        check(JaWeatherRules.activeActualAlert("Actual","Alert","2026-11-19T16:00:00Z",now));
        check(!JaWeatherRules.activeActualAlert("Test","Alert","2026-11-19T16:00:00Z",now));
        check(!JaWeatherRules.activeActualAlert("Exercise","Alert","2026-11-19T16:00:00Z",now));
        check(!JaWeatherRules.activeActualAlert("Actual","Cancel","2026-11-19T16:00:00Z",now));
        check(!JaWeatherRules.activeActualAlert("Actual","Alert","2026-11-19T14:00:00Z",now));
        check(!JaWeatherRules.activeActualAlert("Actual","Alert","invalid",now));
        check(JaWeatherRules.cacheFresh(1000,1000));
        check(JaWeatherRules.cacheFresh(1000,900999));
        check(!JaWeatherRules.cacheFresh(1000,901000));
        check(!JaWeatherRules.cacheFresh(1000,999));
        check(JaWeatherRules.refreshDelay(1000,841000) == 60000);
        check(JaWeatherRules.refreshDelay(1000,901000) == 900000);
        System.out.println("Weather unit and ZIP tests passed");
    }
}
