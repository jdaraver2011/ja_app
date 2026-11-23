package com.example.ja;
/** Shared guide geometry and menu-input boundaries. No Android runtime dependency. */
public final class JaLiveTvGuideRules {
    private JaLiveTvGuideRules() {}
    public static final int WINDOW_MINUTES=120;
    public static final int DEFAULT=0, STAY=1, SEARCH=2, FIRST_GROUP=3, GUIDE=4;
    public static double pixelsPerMinute(double viewport){return Math.max(1.0,viewport)/WINDOW_MINUTES;}
    public static int remapScroll(int offset,double oldScale,double newScale){
        if(oldScale<=0||newScale<=0)return Math.max(0,offset);
        return (int)Math.min(Integer.MAX_VALUE,Math.max(0,Math.round(offset/oldScale*newScale)));
    }
    public static int groupKey(int key,int index,int count,boolean header){
        if(key==21)return STAY; // Left stays inside Groups.
        if(key==22)return GUIDE;
        if(key==19&&(header||index<=0))return GUIDE; // Up closes the Groups drawer at its top edge.
        if(header&&key==20)return FIRST_GROUP;
        if(!header&&key==20&&index>=count-1)return STAY;
        return DEFAULT;
    }
    /** A held OK that opened a dialog must not also activate its first action on release. */
    public static final class MenuKeyGuard {
        private boolean swallowRelease;
        public void open(boolean fromRemoteHold){swallowRelease=fromRemoteHold;}
        public boolean consume(boolean down,boolean up,int repeat){
            if(!swallowRelease)return false;
            if(down&&repeat==0){swallowRelease=false;return false;}
            if(up)swallowRelease=false;
            return true;
        }
    }
}
