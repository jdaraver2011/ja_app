package com.example.ja;
/** Read-only interval counters. Seeks, replaced videos and long pauses reset the window. */
public final class JaPlaybackWindow {
    public static final class Result {
        public final long elapsedMs;
        public final int frames,dropped;
        Result(long elapsed,int frames,int dropped){this.elapsedMs=elapsed;this.frames=frames;this.dropped=dropped;}
        public double droppedPercent(){return 100.0*dropped/frames;}
        public double droppedFps(){return elapsedMs>0?dropped*1000.0/elapsedMs:0.0;}
        public double presentedFps(){return elapsedMs>0?Math.max(0,frames-dropped)*1000.0/elapsedMs:0.0;}
    }
    private long anchor=-1,lastAt=-1;
    private int anchorFrames,anchorDropped,lastFrames,lastDropped,width,height;
    private double lastPosition;
    public Result sample(long now,int total,int dropped,int w,int h,double position) {
        if(total<=0 || dropped<0 || dropped>total || w<480 || h<270 ||
            Double.isNaN(position) || Double.isInfinite(position) || position<0)return null;
        boolean reset=anchor<0 || now<lastAt || now-lastAt>5000 || w!=width || h!=height ||
            total<lastFrames || dropped<lastDropped || position<lastPosition-.5 ||
            (lastAt>=0 && position-lastPosition>(now-lastAt)/1000.0*5+2);
        lastAt=now;lastFrames=total;lastDropped=dropped;width=w;height=h;lastPosition=position;
        if(reset){anchor=now;anchorFrames=total;anchorDropped=dropped;return null;}
        if(now-anchor<30000)return null;
        int frames=total-anchorFrames,missed=dropped-anchorDropped;
        Result result=frames>0 && missed>=0 && missed<=frames ? new Result(now-anchor,frames,missed) : null;
        anchor=now;anchorFrames=total;anchorDropped=dropped;
        return result;
    }
}
