package com.example.ja;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Shared, deterministic playlist/guide rules. No fuzzy station matching. */
public final class JaLiveTvRules {
    private JaLiveTvRules() {}
    public static String attribute(String line, String name) {
        Matcher m=Pattern.compile("(?:^|\\s)"+Pattern.quote(name)+"=\"([^\"]*)\"").matcher(line);
        return m.find()?m.group(1):"";
    }
    public static String name(String line) {
        boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i); if(c=='"')quoted=!quoted;
            if(c==','&&!quoted)return line.substring(i+1).trim();
        }
        return "";
    }
    public static String guideName(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("\\s*\\[(?:geo-blocked|not 24/7)\\]", "")
            .replaceAll("\\s*\\(\\d{3,4}p\\)", "")
            .replaceAll("\\b(?:hd|sd|uhd)\\b", "")
            .replaceAll("[^a-z0-9]", "");
    }
    public static String baseId(String id) {return id.split("@",2)[0];}
    public static long xmlTime(String value) {
        try {return OffsetDateTime.parse(value.trim(),DateTimeFormatter.ofPattern("yyyyMMddHHmmss xx",Locale.ROOT)).toInstant().toEpochMilli();}
        catch(Exception e){return -1;}
    }
    public static double distance(double lat, double lon, double targetLat, double targetLon) {
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(targetLat)||!Double.isFinite(targetLon)
            ||Math.abs(lat)>90||Math.abs(targetLat)>90||Math.abs(lon)>180||Math.abs(targetLon)>180)return Double.POSITIVE_INFINITY;
        double a=Math.pow(Math.sin(Math.toRadians(targetLat-lat)/2),2)
            +Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(targetLat))*Math.pow(Math.sin(Math.toRadians(targetLon-lon)/2),2);
        return 6371.0088*2*Math.asin(Math.sqrt(Math.min(1,a)));
    }
    public static boolean fresh(long fetched,long now,long ttl){return fetched>0&&now>=fetched&&now-fetched<ttl;}
}
