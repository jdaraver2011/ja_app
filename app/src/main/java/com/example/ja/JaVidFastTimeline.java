package com.example.ja;

/** Provider events establish progress only after plausible, sustained advancement. */
public final class JaVidFastTimeline {
    private long anchorAt=-1, previousAt=-1;
    private double anchorPosition, previousPosition;
    private boolean confirmed;
    public boolean confirmed() { return confirmed; }
    public boolean sample(long now, double position, double duration, boolean playing, boolean seeked) {
        if (Double.isNaN(position) || Double.isInfinite(position) || Double.isNaN(duration) || Double.isInfinite(duration) || duration<=0 || position<0 || position>duration+2) return false;
        boolean discontinuity=previousAt>=0 && (now<previousAt || position<previousPosition-0.5 ||
            position-previousPosition>(now-previousAt)/1000.0*5+2);
        previousAt=now;previousPosition=position;
        if (!playing || seeked || discontinuity || anchorAt<0) {
            anchorAt=playing?now:-1;anchorPosition=position;return false;
        }
        if (!confirmed && now-anchorAt>=3000 && position-anchorPosition>=2) {
            confirmed=true;return true;
        }
        return false;
    }
}
