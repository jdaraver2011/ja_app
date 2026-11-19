package com.example.ja;

import java.time.Instant;
import java.util.*;

public final class JaWeatherMediaRules {
    private JaWeatherMediaRules() {}
    public static List<String> frames(String csv, long now) {
        TreeMap<Long,String> times = new TreeMap<>();
        for (String s : csv.split(",")) try {
            long t = Instant.parse(s.trim()).toEpochMilli();
            if (t >= now - 6L * 3600000 && t <= now + 600000) times.put(t,s.trim());
        } catch (Exception ignored) { }
        List<String> selected = new ArrayList<>();
        long last = Long.MIN_VALUE / 2;
        for (Map.Entry<Long,String> e : times.entrySet()) if (e.getKey() - last >= 600000) {
            selected.add(e.getValue()); last = e.getKey();
        }
        if (!times.isEmpty() && !selected.contains(times.lastEntry().getValue())) selected.add(times.lastEntry().getValue());
        return selected;
    }
    public static String radarLayer(double lat, double lon) {
        if (lat > 50 && lon < -125) return "alaska_base_reflectivity_mosaic";
        if (lat < 30 && lon < -150) return "hawaii_base_reflectivity_mosaic";
        if (lon > 100) return "guam_base_reflectivity_mosaic";
        if (lat < 25 && lon > -70) return "caribbean_base_reflectivity_mosaic";
        return "conus_base_reflectivity_mosaic";
    }
    public static String bounds(double lat, double lon, boolean satellite) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>85 || Math.abs(lon)>180)
            throw new IllegalArgumentException("Invalid map position");
        double halfLat = satellite ? 5 : 1.8;
        double halfLon = halfLat * 1.6 / Math.max(0.3, Math.cos(Math.toRadians(lat)));
        return String.format(Locale.US,"%.5f,%.5f,%.5f,%.5f",Math.max(-180,lon-halfLon),
            Math.max(-85,lat-halfLat),Math.min(180,lon+halfLon),Math.min(85,lat+halfLat));
    }
}
