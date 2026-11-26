package com.example.ja;
public final class JaFrameStatsWarmupTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  JaFrameStatsWarmup w=new JaFrameStatsWarmup();String url="https://player.example/video/1";
  for(int i=0;i<4;i++)w.record("a",url,i*1000L,24+i*24,1+i,1920,1080);
  check(w.matching(url,3200).size()==3);
  JaFrameRateSampler s=new JaFrameRateSampler();double measured=Double.NaN;
  for(JaFrameStatsWarmup.Sample v:w.matching(url,3200))measured=s.observe(v.frames,v.position);
  check(Math.abs(measured-24)<.1); // Ready at playback confirmation, without another two-second wait.
  check(w.matching("https://other.example/video/1",3200).isEmpty());
  check(w.matching("https://player.example/video/2",3200).isEmpty());
  check(w.matching(url,5000).isEmpty());
  w.record("b",url,3100,100,4,1920,1080);check(w.matching(url,3200).isEmpty());
  w.remove("b");check(w.matching(url,3200).size()==3);
  w.record("a",url,3500,1,0,1280,720);check(w.matching(url,3600).size()==1);
  w.clear();check(w.matching(url,3600).isEmpty());
  for(int i=0;i<12;i++)w.record(i,"https://player.example/"+i,4000,24,1,1920,1080);
  check(w.matching("https://player.example/0",4100).isEmpty());
  check(w.matching("https://player.example/11",4100).size()==1);
  JaFrameStatsWarmup stable=new JaFrameStatsWarmup();
  for(int i=0;i<7;i++)stable.record("nested","https://embed.example/player",10_000L+i*1000L,
    500+i*60,i,1280,720);
  check(stable.isStablePlayback("nested","https://embed.example/player",16_000L));
  check(!stable.isStablePlayback("nested","https://embed.example/player",18_000L));
  JaFrameStatsWarmup paused=new JaFrameStatsWarmup();
  for(int i=0;i<7;i++)paused.record("paused","https://embed.example/player",20_000L+i*1000L,
    900+i*60,2,1280,720);
  check(!paused.isStablePlayback("paused","https://embed.example/player",26_000L));
  System.out.println("PASS: exact-document warmup, stable nested playback, immediate confirmed cadence, freshness, ambiguity, replacement, disposal and bounds");
 }
}
