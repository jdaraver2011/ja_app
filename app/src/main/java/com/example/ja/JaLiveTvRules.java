package com.example.ja;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Shared, deterministic playlist/guide rules. No fuzzy station matching. */
public final class JaLiveTvRules {
    private JaLiveTvRules() {}
    private static final DateTimeFormatter XML_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss xx",Locale.ROOT);
    private static final Pattern NFL_TEAM_OR_NETWORK = Pattern.compile(
        "(?i)(?:^|[^a-z0-9])(?:arizona\\s+cardinals|atlanta\\s+falcons|baltimore\\s+ravens|buffalo\\s+bills|"+
        "carolina\\s+panthers|chicago\\s+bears|cincinnati\\s+bengals|cleveland\\s+browns|dallas\\s+cowboys|"+
        "denver\\s+broncos|detroit\\s+lions|green\\s+bay\\s+packers|houston\\s+texans|indianapolis\\s+colts|"+
        "jacksonville\\s+jaguars|kansas\\s+city\\s+chiefs|las\\s+vegas\\s+raiders|los\\s+angeles\\s+chargers|"+
        "los\\s+angeles\\s+rams|miami\\s+dolphins|minnesota\\s+vikings|new\\s+england\\s+patriots|"+
        "new\\s+orleans\\s+saints|new\\s+york\\s+giants|new\\s+york\\s+jets|philadelphia\\s+eagles|"+
        "pittsburgh\\s+steelers|san\\s+francisco\\s+49ers|seattle\\s+seahawks|tampa\\s+bay\\s+buccaneers|"+
        "tennessee\\s+titans|washington\\s+commanders|cardinals|falcons|ravens|bills|panthers|bears|bengals|"+
        "browns|cowboys|broncos|lions|packers|texans|colts|jaguars|chiefs|raiders|chargers|rams|dolphins|vikings|"+
        "patriots|saints|giants|jets|eagles|steelers|49ers|seahawks|buccaneers|titans|commanders|nfl[\\s_-]*network)"
        + "(?:$|[^a-z0-9])");
    public static boolean isNflChannelName(String value) {
        return value != null && NFL_TEAM_OR_NETWORK.matcher(value).find();
    }
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
        List<String> result=new ArrayList<>();result.add("All Favorites");result.add("With EPG");result.add("United States");
        result.addAll(CATEGORIES.keySet());
        return result;
    }
    /** Adds source-country and playlist group-title filters without duplicating channel records. */
    public static List<String> views(Set<String> availableCategories) {
        List<String> result=views();
        result.add(3,"United Kingdom");
        Set<String> known=new HashSet<>(CATEGORIES.values());
        known.addAll(Arrays.asList("country-us","country-uk","test","source-iptv-org","source-daddylive","source-ntv"));
        Set<String> extras=new TreeSet<>();
        if(availableCategories!=null)for(String value:availableCategories)
            if(value!=null&&!value.isBlank()&&!known.contains(value))extras.add(value);
        Set<String> labels=new HashSet<>();for(String view:result)labels.add(view.toLowerCase(Locale.ROOT));
        if(extras.remove("daddylive-usa"))result.add(3,"DaddyLive USA");
        if(extras.remove("ntv-usa"))result.add(Math.min(4,result.size()),"NTV USA");
        if(extras.remove("ntv-nfl-usa"))result.add(Math.min(5,result.size()),"NTV NFL USA");
        if(extras.remove("ntv-basketball-usa"))result.add(Math.min(6,result.size()),"NTV Basketball USA");
        if(extras.remove("ntv-baseball-usa"))result.add(Math.min(7,result.size()),"NTV Baseball USA");
        String[][] ntvGroups={{"ntv-server-raptor","Raptor"},{"ntv-server-falcon","Falcon"},
            {"ntv-server-phoenix","Phoenix"},{"ntv-server-titan","Titan"},
            {"ntv-server-scorpion","Scorpion"},{"ntv-server-other","Other Servers"},
            {"ntv-sport-all","All Sports"},{"ntv-sport-soccer","Soccer"},
            {"ntv-sport-nfl","NFL"},{"ntv-sport-ncaa","NCAA"},{"ntv-sport-basketball","Basketball"},
            {"ntv-sport-golf","Golf"},{"ntv-sport-motorsport","Motorsport"},{"ntv-sport-ufc","UFC"},
            {"ntv-sport-wwe","WWE"},{"ntv-sport-mma","MMA"},{"ntv-sport-badminton","Badminton"}};
        for(String[] group:ntvGroups)if(extras.remove(group[0]))result.add(group[1]);
        extras.remove("ntv-sport-baseball");extras.remove("ntv-channel");
        for(String value:extras){String label=displayCategory(value);if(labels.add(label.toLowerCase(Locale.ROOT)))result.add(label);}
        return result;
    }
    public static List<String> sources() {
        return Arrays.asList("Favorites","iptv-org.com","DaddyLive TV","NTV");
    }
    public static String sourceCategory(String source) {
        if("iptv-org.com".equals(source))return "source-iptv-org";
        if("DaddyLive TV".equals(source))return "source-daddylive";
        if("NTV".equals(source))return "source-ntv";
        return "";
    }
    public static boolean sourceMatches(String source,Set<String> categories) {
        if("Favorites".equals(source))return true;
        String key=sourceCategory(source);
        return !key.isEmpty()&&categories.contains(key);
    }
    public static boolean isNtvAdultOnly(String name,Collection<String> categories) {
        if(name!=null&&Pattern.compile("(?i)(?:^|[^a-z0-9])18\\s*\\+").matcher(name).find())return true;
        if(categories==null)return false;
        for(String value:categories)if(value!=null){
            String category=value.toLowerCase(Locale.ROOT).replace('_','-');
            if(category.equals("18-plus")||category.startsWith("18-plus-")||category.equals("adult")
                ||category.equals("adult-only")||category.startsWith("adult-only-"))return true;
        }
        return false;
    }
    public static List<String> groupsForSource(String source,Set<String> availableCategories) {
        if("Favorites".equals(source))return Collections.singletonList("All Favorites");
        List<String> groups=new ArrayList<>(Collections.singletonList("With EPG"));
        Set<String> cats=availableCategories==null?Collections.emptySet():availableCategories;
        if("iptv-org.com".equals(source)) {
            if(cats.contains("country-us"))groups.add("United States");
            if(cats.contains("country-uk"))groups.add("United Kingdom");
            addExtraGroups(groups,cats,setOf("source-iptv-org","country-us","country-uk","test"));
        } else if("DaddyLive TV".equals(source)) {
            if(cats.contains("daddylive-usa"))groups.add("DaddyLive USA");
        } else if("NTV".equals(source)) {
            groups.addAll(Arrays.asList("Raptor","Falcon","Phoenix","Titan","Scorpion","Other Servers",
                "All Sports","Soccer","NFL","NCAA","Basketball","Golf","Motorsport","UFC","WWE","MMA","Badminton",
                "NFL USA","Basketball USA","Baseball USA"));
            if(cats.contains("ntv-adult-only"))groups.add("Adult Only");
            addExtraGroups(groups,cats,setOf("source-ntv","country-us","country-uk","test",
                "ntv-adult-only","18-plus","adult","adult-only","ntv-server-raptor","ntv-server-falcon","ntv-server-phoenix","ntv-server-titan",
                "ntv-server-scorpion","ntv-server-other","ntv-sport-all","ntv-sport-soccer",
                "ntv-sport-nfl","ntv-sport-ncaa","ntv-sport-basketball","ntv-sport-golf",
                "ntv-sport-motorsport","ntv-sport-ufc","ntv-sport-wwe","ntv-sport-mma",
                "ntv-sport-badminton","ntv-sport-baseball","ntv-sport-baseball-usa","ntv-channel"));
        }
        return groups;
    }
    private static Set<String> setOf(String... values) {return new HashSet<>(Arrays.asList(values));}
    private static void addExtraGroups(List<String> groups,Set<String> categories,Set<String> excluded) {
        Set<String> extras=new TreeSet<>(categories);extras.removeAll(excluded);
        for(String value:extras)groups.add(displayCategory(value));
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
        if("Favorites".equals(view)||"All Favorites".equals(view)||"With EPG".equals(view))return "";
        if("United States".equals(view))return "country-us";
        if("United Kingdom".equals(view))return "country-uk";
        if("DaddyLive USA".equals(view))return "daddylive-usa";
        if("Raptor".equals(view))return "ntv-server-raptor";
        if("Falcon".equals(view))return "ntv-server-falcon";
        if("Phoenix".equals(view))return "ntv-server-phoenix";
        if("Titan".equals(view))return "ntv-server-titan";
        if("Scorpion".equals(view))return "ntv-server-scorpion";
        if("Other Servers".equals(view))return "ntv-server-other";
        if("All Sports".equals(view))return "ntv-sport-all";
        if("Soccer".equals(view))return "ntv-sport-soccer";
        if("NFL".equals(view))return "ntv-sport-nfl";
        if("NCAA".equals(view))return "ntv-sport-ncaa";
        if("Basketball".equals(view))return "ntv-sport-basketball";
        if("Golf".equals(view))return "ntv-sport-golf";
        if("Motorsport".equals(view))return "ntv-sport-motorsport";
        if("UFC".equals(view))return "ntv-sport-ufc";
        if("WWE".equals(view))return "ntv-sport-wwe";
        if("MMA".equals(view))return "ntv-sport-mma";
        if("Badminton".equals(view))return "ntv-sport-badminton";
        if("Baseball USA".equals(view))return "ntv-sport-baseball-usa";
        if("Adult Only".equals(view))return "ntv-adult-only";
        if("NFL USA".equals(view))return "ntv-nfl-usa";
        if("Basketball USA".equals(view))return "ntv-basketball-usa";
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
