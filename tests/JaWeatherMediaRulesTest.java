package com.example.ja;
import java.time.Instant;
import java.util.*;
public class JaWeatherMediaRulesTest {
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        long now=Instant.parse("2026-11-19T21:00:00Z").toEpochMilli();
        List<String> feed=new ArrayList<>();
        for(int i=0;i<=240;i++)feed.add(Instant.ofEpochMilli(now-i*120000L).toString());
        feed.add("invalid");feed.add("2026-11-20T00:00:00Z");feed.add(feed.get(0));
        List<String> frames=JaWeatherMediaRules.frames(String.join(",",feed),now);
        check(frames.size()>=30 && frames.size()<=38);
        check(Instant.parse(frames.get(0)).toEpochMilli()==now-6L*3600000);
        check(Instant.parse(frames.get(frames.size()-1)).toEpochMilli()==now);
        long last=0;
        for(String frame:frames) { long t=Instant.parse(frame).toEpochMilli(); check(t>last);last=t;check(feed.contains(frame)); }
        check(JaWeatherMediaRules.frames("invalid",now).isEmpty());
        check(JaWeatherMediaRules.radarLayer(21,-158).startsWith("hawaii"));
        check(JaWeatherMediaRules.radarLayer(64,-150).startsWith("alaska"));
        check(JaWeatherMediaRules.radarLayer(30,-90).startsWith("conus"));
        String[] bbox=JaWeatherMediaRules.bounds(30,-90,false).split(",");
        check(Double.parseDouble(bbox[0])< -90 && Double.parseDouble(bbox[2])> -90);
        check(Double.parseDouble(bbox[1])<30 && Double.parseDouble(bbox[3])>30);
        boolean rejected=false;try { JaWeatherMediaRules.bounds(Double.NaN,0,false); } catch(IllegalArgumentException e){rejected=true;}check(rejected);
        System.out.println("Six-hour imagery selection and map bounds passed");
    }
}
