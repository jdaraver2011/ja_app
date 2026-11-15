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
  while(e.samples.size()>6 || (!e.samples.isEmpty()&&now-e.samples.peekFirst().at>4000))e.samples.removeFirst();
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
 public void remove(Object port){entries.remove(port);}
 public void clear(){entries.clear();}
}
