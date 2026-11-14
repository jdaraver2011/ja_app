package com.example.ja;
public final class JaVidFastTimelineTest {
    static void check(boolean value,String label) { if(!value)throw new AssertionError(label); }
    public static void main(String[] args) {
        JaVidFastTimeline t=new JaVidFastTimeline();
        check(!t.sample(0,20,100,true,false),"single play event");
        check(!t.sample(3000,20,100,true,false),"frozen playback");
        check(t.sample(4000,24,100,true,false),"sustained advancement");
        check(!t.sample(5000,25,100,true,false),"startup emitted once");
        t=new JaVidFastTimeline();
        t.sample(0,0,100,true,false);
        check(!t.sample(3000,60,100,true,false),"seek jump is not playback");
        check(!t.sample(6000,60,100,false,false),"paused event");
        t.sample(7000,60,100,true,false);
        check(t.sample(10000,63,100,true,false),"resume advancement");
        t=new JaVidFastTimeline();
        check(!t.sample(0,Double.NaN,100,true,false),"NaN");
        check(!t.sample(4000,1,0,true,false),"unknown duration");
        t.sample(5000,20,100,true,false);
        check(!t.sample(8000,80,100,true,true),"seeked event");
        check(t.sample(11000,83,100,true,false),"advancement after seek");
        System.out.println("VidFast timeline: all checks passed");
    }
}
