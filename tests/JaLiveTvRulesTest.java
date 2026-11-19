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
        check(JaLiveTvRules.distance(30,-90,30,-90)<0.001);
        check(JaLiveTvRules.distance(30,-90,30.5,-91)<JaLiveTvRules.distance(30,-90,40,-74));
        check(Double.isInfinite(JaLiveTvRules.distance(Double.NaN,0,0,0)));
        check(JaLiveTvRules.fresh(100,199,100)&&!JaLiveTvRules.fresh(100,200,100)&&!JaLiveTvRules.fresh(200,100,100));
        System.out.println("Live TV rules passed");
    }
}
