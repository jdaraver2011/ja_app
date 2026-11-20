package com.example.ja;
public final class JaLiveTvGuideRulesTest {
    private static void equal(int a,int b){if(a!=b)throw new AssertionError(a+" != "+b);}
    private static void close(double a,double b){if(Math.abs(a-b)>0.001)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        for(double width:new double[]{120,320,800,1600}){
            double scale=JaLiveTvGuideRules.pixelsPerMinute(width);
            close(scale*120,width);close(scale*30,width/4);close(scale*1440,width*12);
        }
        equal(JaLiveTvGuideRules.remapScroll(800,4,8),1600);
        equal(JaLiveTvGuideRules.remapScroll(1600,8,4),800);
        equal(JaLiveTvGuideRules.remapScroll(0,0,4),0);
        equal(JaLiveTvGuideRules.groupKey(19,0,22,false),JaLiveTvGuideRules.SEARCH);
        equal(JaLiveTvGuideRules.groupKey(19,0,22,true),JaLiveTvGuideRules.STAY);
        equal(JaLiveTvGuideRules.groupKey(20,0,22,true),JaLiveTvGuideRules.FIRST_GROUP);
        equal(JaLiveTvGuideRules.groupKey(20,21,22,false),JaLiveTvGuideRules.STAY);
        equal(JaLiveTvGuideRules.groupKey(21,5,22,false),JaLiveTvGuideRules.STAY);
        equal(JaLiveTvGuideRules.groupKey(22,5,22,false),JaLiveTvGuideRules.GUIDE);
        equal(JaLiveTvGuideRules.groupKey(19,5,22,false),JaLiveTvGuideRules.DEFAULT);
        JaLiveTvGuideRules.MenuKeyGuard guard=new JaLiveTvGuideRules.MenuKeyGuard();
        guard.open(true);
        if(!guard.consume(true,false,2)||!guard.consume(false,true,0))throw new AssertionError("Held key not swallowed");
        if(guard.consume(true,false,0)||guard.consume(false,true,0))throw new AssertionError("Fresh selection blocked");
        guard.open(true);if(guard.consume(true,false,0))throw new AssertionError("Fresh down after focus handoff blocked");
        guard.open(false);if(guard.consume(false,true,0))throw new AssertionError("Touch-opened menu blocked");
        System.out.println("Two-hour guide geometry, focus boundaries and held-menu-key checks passed");
    }
}
