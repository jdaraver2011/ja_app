package com.example.ja;
public final class JaLiveTvRulesTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        String line="#EXTINF:-1 tvg-id=\"WWL.us@HD\" tvg-logo=\"https://a/x,y.png\",WWL (720p)";
        check(JaLiveTvRules.name(line).equals("WWL (720p)"));
        check(JaLiveTvRules.attribute(line,"tvg-id").equals("WWL.us@HD"));
        check(JaLiveTvRules.baseId("WWL.us@HD").equals("WWL.us"));
        check(JaLiveTvRules.guideName("WWL (720p)").equals(JaLiveTvRules.guideName("WWL HD")));
        check(!JaLiveTvRules.guideName("ABC New Orleans").equals(JaLiveTvRules.guideName("ABC Los Angeles")));
        check(JaLiveTvRules.xmlTime("20261119120000 -0600")==JaLiveTvRules.xmlTime("20261119180000 +0000"));
        check(JaLiveTvRules.xmlTime("bad")==-1);
        check(JaLiveTvRules.views().get(0).equals("All Favorites"));
        check(JaLiveTvRules.views().get(1).equals("With EPG"));
        check(JaLiveTvRules.views().get(2).equals("United States"));
        check(JaLiveTvRules.views().size()==21);
        String[] nflTeams={"Arizona Cardinals","Atlanta Falcons","Baltimore Ravens","Buffalo Bills",
            "Carolina Panthers","Chicago Bears","Cincinnati Bengals","Cleveland Browns","Dallas Cowboys",
            "Denver Broncos","Detroit Lions","Green Bay Packers","Houston Texans","Indianapolis Colts",
            "Jacksonville Jaguars","Kansas City Chiefs","Las Vegas Raiders","Los Angeles Chargers",
            "Los Angeles Rams","Miami Dolphins","Minnesota Vikings","New England Patriots","New Orleans Saints",
            "New York Giants","New York Jets","Philadelphia Eagles","Pittsburgh Steelers","San Francisco 49ers",
            "Seattle Seahawks","Tampa Bay Buccaneers","Tennessee Titans","Washington Commanders"};
        check(nflTeams.length==32);
        for(String team:nflTeams)check(JaLiveTvRules.isNflChannelName(team+" vs opponent"));
        check(JaLiveTvRules.isNflChannelName("NFL Network HD"));
        check(JaLiveTvRules.isNflChannelName("KC Chiefs at Buffalo Bills"));
        check(!JaLiveTvRules.isNflChannelName("NFLX Network"));
        check(!JaLiveTvRules.isNflChannelName("Boise State Broncos at Colorado State Rams"));
        check(!JaLiveTvRules.isNflChannelName("International cricket"));
        check(JaLiveTvRules.isNcaafEvent("American Football","Boise State Broncos at Colorado State Rams"));
        check(JaLiveTvRules.isNcaafEvent("American Football","Baylor Bears at Houston Cougars"));
        check(!JaLiveTvRules.isNcaafEvent("American Football","Green Bay Packers at Los Angeles Rams"));
        check(!JaLiveTvRules.isNcaafEvent("American Football","NFL Network"));
        java.util.List<String> ntvGroups=JaLiveTvRules.groupsForSource("NTV",
            new java.util.HashSet<>(java.util.Arrays.asList("ntv-sport-nfl","ntv-sport-ncaaf")));
        check(JaLiveTvRules.category("NCAAF").equals("ntv-sport-ncaaf"));
        check(ntvGroups.indexOf("NCAAF")==ntvGroups.indexOf("NFL")+1);
        check(JaLiveTvRules.category("Documentaries").equals("documentary"));
        check(JaLiveTvRules.category("Auto and Motoring").equals("auto"));
        java.util.Set<String> groups=new java.util.HashSet<>(java.util.Arrays.asList("news","weather"));
        check(JaLiveTvRules.categoryMatches("News",groups)&&JaLiveTvRules.categoryMatches("Weather",groups));
        check(!JaLiveTvRules.categoryMatches("Sports",groups));
        check(JaLiveTvRules.categoryMatches("Favorites",java.util.Collections.emptySet()));
        check(JaLiveTvRules.hasGuide(100,200,"Current",150,1000));
        check(JaLiveTvRules.hasGuide(300,400,"Upcoming",150,1000));
        check(!JaLiveTvRules.hasGuide(100,150,"Expired",150,1000));
        check(!JaLiveTvRules.hasGuide(100,200," ",150,1000));
        check(!JaLiveTvRules.hasGuide(100,200,"TBA",150,1000));
        check(!JaLiveTvRules.hasGuide(100,200,"No information available",150,1000));
        check(!JaLiveTvRules.hasGuide(1000,1100,"Beyond grid",150,1000));
        check(!JaLiveTvRules.hasGuide(200,100,"Malformed",150,1000));
        check(JaLiveTvRules.plutoId("https://jmp2.uk/plu-60fb299b79498900070b29e0.m3u8").equals("60fb299b79498900070b29e0"));
        check(JaLiveTvRules.plutoId("https://example.com/plu-60fb299b79498900070b29e0.m3u8").isEmpty());
        check(JaLiveTvRules.plutoId("https://jmp2.uk/x?plu-60fb299b79498900070b29e0.m3u8").isEmpty());
        check(JaLiveTvRules.guideKeys("NBC.us@WDSU","NBC WDSU").contains("wdsudt"));
        check(!JaLiveTvRules.guideKeys("NBC.us@WDSU","NBC WDSU").contains("wdsudt2"));
        check(!JaLiveTvRules.guideKeys("CBSNewsMiami.us@SD","CBS News Miami").contains("wfordt"));
        check(JaLiveTvRules.distance(30,-90,30,-90)<0.001);
        check(JaLiveTvRules.distance(30,-90,30.5,-91)<JaLiveTvRules.distance(30,-90,40,-74));
        check(Double.isInfinite(JaLiveTvRules.distance(Double.NaN,0,0,0)));
        check(JaLiveTvRules.fresh(100,199,100)&&!JaLiveTvRules.fresh(100,200,100)&&!JaLiveTvRules.fresh(200,100,100));
        System.out.println("Live TV rules passed");
    }
}
