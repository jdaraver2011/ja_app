package com.example.ja;
public final class JaPlaybackWindowTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  JaPlaybackWindow w=new JaPlaybackWindow();JaPlaybackWindow.Result result=null;
  for(int i=0;i<=30;i++)result=w.sample(i*1000L,100+i*24,2+i,1920,1080,i);
  check(result!=null&&result.frames==720&&result.dropped==30&&result.elapsedMs==30000);
  check(Math.abs(result.droppedPercent()-100.0*30/720)<.00001);
  check(Math.abs(result.droppedFps()-1.0)<.00001);
  check(Math.abs(result.presentedFps()-23.0)<.00001);
  check(w.sample(31000,850,33,1920,1080,600)==null);
  for(int i=1;i<30;i++)check(w.sample(31000+i*1000L,850+i*24,33,1920,1080,600+i)==null);
  check(w.sample(61000,1570,33,1920,1080,630)!=null);
  check(w.sample(62000,20,0,1920,1080,0)==null);
  check(w.sample(70000,44,0,1920,1080,1)==null);
  check(w.sample(71000,68,0,1280,720,2)==null);
  check(w.sample(72000,92,0,1280,720,Double.NaN)==null);
  check(w.sample(72000,92,100,1280,720,3)==null);
  System.out.println("PASS: interval deltas, dropped percentage, seeks, replacements, pause gaps and invalid counters");
 }
}
