package com.example.ja;

/** Bounded recovery per outage; healthy playback clears transient failures only. */
public final class JaLiveTvRecovery {
    private JaLiveTvRecovery() {}
    public static int next(int current,int[] failures,boolean[] ended) {
        if(failures.length==0||failures.length!=ended.length||current<0||current>=failures.length)return -1;
        if(!ended[current]&&failures[current]<2)return current;
        for(int step=1;step<failures.length;step++) {
            int next=(current+step)%failures.length;
            if(!ended[next]&&failures[next]<2)return next;
        }
        return -1;
    }
    public static long backoff(int failures){return Math.min(8000L,1000L*(1L<<Math.min(3,Math.max(0,failures-1))));}
    public static boolean stalled(boolean foreground,boolean wanted,boolean suppressed,boolean pending,long lastProgress,long now) {
        return foreground&&wanted&&!suppressed&&!pending&&now>=lastProgress&&now-lastProgress>=30000L;
    }
}
