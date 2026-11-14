package com.example.ja;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class JaVidSrcRulesTest {
 private static void check(boolean v){if(!v)throw new AssertionError();}
 private static void rejected(int kind,String value)throws Exception{
  try { JaVidSrcRules.key(kind,value);throw new AssertionError(value); }catch(IOException expected){}
 }
 public static void main(String[] args)throws Exception{
  long now=1_800_000_000_000L;
  check(JaVidSrcRules.due(0,0,now));
  check(!JaVidSrcRules.due(now-1000,0,now));
  check(JaVidSrcRules.due(now-JaVidSrcRules.DAY_MS,0,now));
  check(!JaVidSrcRules.due(0,now+JaVidSrcRules.RETRY_MS,now));
  check(JaVidSrcRules.due(0,now+JaVidSrcRules.RETRY_MS,now+JaVidSrcRules.RETRY_MS));
  check(JaVidSrcRules.due(now+1000,0,now));
  check(!JaVidSrcRules.safeCount(100,0));check(!JaVidSrcRules.safeCount(100,49));check(JaVidSrcRules.safeCount(100,50));
  check(JaVidSrcRules.quality(" cam ").equals("CAM"));
  check(JaVidSrcRules.quality("1080p").equals("1080P"));
  check(!JaVidSrcRules.quality("SD").equals("CAM"));check(!JaVidSrcRules.quality("DVD").equals("CAM"));
  check(JaVidSrcRules.quality("error code: 1010").isEmpty());check(JaVidSrcRules.quality(null).isEmpty());
  check(JaVidSrcRules.key(1,"354912").id==354912);
  var ep=JaVidSrcRules.key(3,"314541_1x3");check(ep.id==314541&&ep.season==1&&ep.episode==3);
  check(JaVidSrcRules.key(3,"1399_0x1").season==0);
  for(String bad:new String[]{"<html>","error code: 1010","tt12345","-1","0","2147483648"})rejected(1,bad);
  for(String bad:new String[]{"1399","1399_1x0","1399_-1x1","1399_1x2147483648"})rejected(3,bad);
  List<Integer> parsed=new ArrayList<>();
  check(JaVidSrcRules.read(new ByteArrayInputStream("\uFEFF2\n3\r\n\n5\n".getBytes(StandardCharsets.UTF_8)),1,k->parsed.add(k.id))==3);
  check(parsed.equals(List.of(2,3,5)));
  try{JaVidSrcRules.read(new ByteArrayInputStream(new byte[0]),1,k->{});throw new AssertionError();}catch(IOException expected){}
  // A partially valid feed must fail rather than silently accepting its prefix.
  try{JaVidSrcRules.read(new ByteArrayInputStream("2\n3\n<html>blocked".getBytes(StandardCharsets.UTF_8)),1,k->{});throw new AssertionError();}catch(IOException expected){}
  if(args.length>0){for(int kind=1;kind<=3;kind++){
   String name=kind==1?"movie_tmdb.txt":kind==2?"tv_tmdb.txt":"eps_tmdb.txt";
   try(InputStream input=new FileInputStream(args[0]+"/"+name)){
    long count=JaVidSrcRules.read(input,kind,k->{});System.out.println(name+": "+count+" valid entries");
   }
  }}
  System.out.println("PASS: daily expiry, retries, clock correction, quality classification, ID/episode parsing and incomplete-feed rejection");
 }
}
