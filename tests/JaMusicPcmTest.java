package com.example.ja;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public final class JaMusicPcmTest {
    public static void main(String[] args) {
        JaMusicPcm pcm = new JaMusicPcm(); pcm.enable(true); pcm.format(2);
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        for (int i=0; i<1024; i++) { b.putShort((short)32767); b.putShort((short)32767); }
        b.flip(); pcm.capture16(b, 1000);
        if (b.position()!=0 || b.limit()!=4096) throw new AssertionError("Decoder buffer modified");
        if (!pcm.snapshot(1100).startsWith("[255,255,")) throw new AssertionError("Peak capture");
        if (!pcm.snapshot(1600).startsWith("[128,128,")) throw new AssertionError("Paused data must expire");
        pcm.format(2); b.clear(); for(int i=0;i<1024;i++){b.putShort((short)32767);b.putShort((short)-32767);} b.flip(); pcm.capture16(b,2000);
        if (!pcm.snapshot(2100).startsWith("[128,128,")) throw new AssertionError("Stereo must mix");
        pcm.enable(false); pcm.capture16(b,3000);
        if (!pcm.snapshot(3100).startsWith("[128,128,")) throw new AssertionError("Hidden visualizer captured audio");
        if(pcm.snapshot(3100).split(",").length!=1024) throw new AssertionError("Unbounded PCM bridge");
        System.out.println("PCM: peaks, stereo mix, input preservation, expiration and disabled capture passed");
    }
}
