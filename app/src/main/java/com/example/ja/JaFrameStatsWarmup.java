package com.example.ja;
import java.util.*;
/** Session-only samples. Replay requires one fresh, exact document match after playback confirmation. */
public final class JaFrameStatsWarmup {
 public static final class Sample {
  public final long at;public final int frames,width,height;public final double position;
  Sample(long at,int frames,double position,int width,int height){this.at=at;this.frames=frames;this.position=position;this.width=width;this.height=height;}
 }
 private static final class Entry {final String document;final ArrayDeque<Sample> samples=new ArrayDeque<>();Entry(String doc){document=doc;}}
 private final LinkedHashMap<Object,Entry> entries=new LinkedHashMap<>(8,.75f,true);
 public void record(Object port,String document,long now,int frames,double position,int width,int height){
  if(document==null || document.isEmpty() || frames<=0 || width<480 || height<270 ||
    Double.isNaN(position)||Double.isInfinite(position)||position<0)return;
  Entry e=entries.get(port);
  if(e==null || !e.document.equals(document)){e=new Entry(document);entries.put(port,e);}
  Sample last=e.samples.peekLast();
  if(last!=null&&(now<last.at || now-last.at>2500 || last.width!=width || last.height!=height ||
    frames<last.frames || position<last.position || position-last.position>(now-last.at)/1000.0*5+2))e.samples.clear();
  e.samples.add(new Sample(now,frames,position,width,height));
  while(e.samples.size()>10 || (!e.samples.isEmpty()&&now-e.samples.peekFirst().at>10000))e.samples.removeFirst();
  while(entries.size()>8)entries.remove(entries.keySet().iterator().next());
 }
 public List<Sample> matching(String document,long now){
  if(document==null || document.isEmpty())return Collections.emptyList();
  Entry found=null;
  for(Entry e:entries.values()){
   Sample last=e.samples.peekLast();
   if(!e.document.equals(document)||last==null||now<last.at||now-last.at>1500)continue;
   if(found!=null)return Collections.emptyList(); // Same URL in multiple frames is ambiguous.
   found=e;
  }
  if(found==null)return Collections.emptyList();
  ArrayList<Sample> out=new ArrayList<>();
  for(Sample s:found.samples)if(now>=s.at&&now-s.at<=4000)out.add(s);
  // Only the last two cadence intervals: don't reuse earlier pre-roll history.
  return out.size()>3?new ArrayList<>(out.subList(out.size()-3,out.size())):out;
 }
 /** True only after seven recent reports show the same video advancing steadily. */
 public boolean isStablePlayback(Object port,String document,long now){
  Entry entry=entries.get(port);
  if(entry==null||document==null||!document.equals(entry.document))return false;
  ArrayList<Sample> recent=new ArrayList<>(entry.samples);
  if(recent.size()<7)return false;
  List<Sample> last=recent.subList(recent.size()-7,recent.size());
  Sample first=last.get(0),end=last.get(last.size()-1);
  long span=end.at-first.at;
  if(now<end.at||now-end.at>1500||span<5000||span>8000)return false;
  int previousFrames=first.frames;
  long previousAt=first.at;
  for(int index=1;index<last.size();index++){
   Sample sample=last.get(index);
   Sample previous=last.get(index-1);
   if(sample.width!=first.width||sample.height!=first.height||
      sample.at-previousAt>1500||sample.frames<=previousFrames||
      sample.position<=previous.position)return false;
   previousFrames=sample.frames;
   previousAt=sample.at;
  }
  double positionAdvance=end.position-first.position;
  return positionAdvance>=4.0&&positionAdvance<=span/1000.0*2.0+1.0;
 }
 public void remove(Object port){entries.remove(port);}
 public void clear(){entries.clear();}
}
