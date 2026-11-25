package com.example.ja;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Shared, deterministic playlist/guide rules. No fuzzy station matching. */
public final class JaLiveTvRules {
    private JaLiveTvRules() {}
    private static final DateTimeFormatter XML_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss xx",Locale.ROOT);
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
    public static Set<String> guideKeys(String id,String name) {
        Set<String> keys=new LinkedHashSet<>();keys.add(guideName(name));
        String[] parts=id.split("@",2);
        String station=parts.length>1?parts[1]:"";
        if(!station.matches("[WK][A-Z0-9]{2,5}"))station=parts[0].replaceFirst("\\.us$", "");
        if(station.matches("[WK][A-Z0-9]{2,5}")) {
            keys.add(guideName(station));keys.add(guideName(station+"-DT"));
        }
        return keys;
    }
    public static String plutoId(String value) {
        try {
            java.net.URI uri=new java.net.URI(value);String host=uri.getHost();String path=uri.getPath();
            if(host==null||path==null)return "";
            String regex;
            if(host.equals("jmp2.uk"))regex="^/plu-([0-9a-f]{24})\\.m3u8$";
            else if(host.equals("i.mjh.nz"))regex="^/PlutoTV/([0-9a-f]{24})\\.m3u8$";
            else if(host.equals("pluto.tv")||host.endsWith(".pluto.tv"))regex="/channel/([0-9a-f]{24})(?:/|$)";
            else return "";
            Matcher m=Pattern.compile(regex).matcher(path);return m.find()?m.group(1):"";
        }catch(Exception e){return "";}
    }
    public static boolean hasGuide(long start,long end,String title,long now,long horizon) {
        if(start<0||end<=start||end<=now||start>=horizon||title==null||title.trim().isEmpty())return false;
        String label=title.trim().toLowerCase(Locale.ROOT);
        return !Arrays.asList("tba","to be announced","no information","no information available","no program information","no programme information","no data","no data available","guide unavailable").contains(label);
    }
    private static final Map<String,String> CATEGORIES;
    static {
        Map<String,String> map=new LinkedHashMap<>();
        String[][] values={{"News","news"},{"Sports","sports"},{"Movies","movies"},{"Series","series"},
            {"Entertainment","entertainment"},{"Documentaries","documentary"},{"Kids","kids"},{"Animation","animation"},
            {"Comedy","comedy"},{"Music","music"},{"Cooking","cooking"},{"Travel","travel"},{"Science","science"},
            {"Education","education"},{"Business","business"},{"Weather","weather"},{"Classic TV","classic"},{"Auto and Motoring","auto"}};
        for(String[] value:values)map.put(value[0],value[1]);
        CATEGORIES=Collections.unmodifiableMap(map);
    }
    public static List<String> views() {
        List<String> result=new ArrayList<>();result.add("Favorites");result.add("With EPG");result.add("United States");
        result.addAll(CATEGORIES.keySet());
        return result;
    }
    /** Adds source-country and playlist group-title filters without duplicating channel records. */
    public static List<String> views(Set<String> availableCategories) {
        List<String> result=views();
        result.add(3,"United Kingdom");
        Set<String> known=new HashSet<>(CATEGORIES.values());
        known.add("country-us");known.add("country-uk");known.add("test");
        Set<String> extras=new TreeSet<>();
        if(availableCategories!=null)for(String value:availableCategories)
            if(value!=null&&!value.isBlank()&&!known.contains(value))extras.add(value);
        Set<String> labels=new HashSet<>();for(String view:result)labels.add(view.toLowerCase(Locale.ROOT));
        if(extras.remove("daddylive-usa"))result.add(3,"DaddyLive USA");
        for(String value:extras){String label=displayCategory(value);if(labels.add(label.toLowerCase(Locale.ROOT)))result.add(label);}
        return result;
    }
    private static String displayCategory(String value) {
        String[] words=value.replace('_','-').split("-");StringBuilder out=new StringBuilder();
        for(String word:words)if(!word.isBlank()){
            if(out.length()>0)out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
    public static String category(String view){
        if("Favorites".equals(view)||"With EPG".equals(view))return "";
        if("United States".equals(view))return "country-us";
        if("United Kingdom".equals(view))return "country-uk";
        String known=CATEGORIES.get(view);if(known!=null)return known;
        return view==null?"":view.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");
    }
    public static boolean categoryMatches(String view,Set<String> categories) {
        String key=category(view);return key.isEmpty()||categories.contains(key);
    }
    public static String baseId(String id) {return id.split("@",2)[0];}
    public static long xmlTime(String value) {
        try {return OffsetDateTime.parse(value.trim(),XML_TIME_FORMAT).toInstant().toEpochMilli();}
        catch(Exception e){return -1;}
    }
    public static double distance(double lat, double lon, double targetLat, double targetLon) {
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(targetLat)||!Double.isFinite(targetLon)
            ||Math.abs(lat)>90||Math.abs(targetLat)>90||Math.abs(lon)>180||Math.abs(targetLon)>180)return Double.POSITIVE_INFINITY;
        double a=Math.pow(Math.sin(Math.toRadians(targetLat-lat)/2),2)
            +Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(targetLon))*Math.pow(Math.sin(Math.toRadians(lon-targetLon)/2),2);
        return 6371.0088*2*Math.asin(Math.sqrt(Math.min(1,a)));
    }
    public static boolean fresh(long fetched,long now,long ttl){return fetched>0&&now>=fetched&&now-fetched<ttl;}
}
