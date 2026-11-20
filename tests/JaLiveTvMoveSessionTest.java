package com.example.ja;
import java.util.*;
public final class JaLiveTvMoveSessionTest {
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        List<String> stored=Arrays.asList("a","hidden","b","missing","c");
        equal(JaLiveTvMoveSession.displayed(stored,Arrays.asList("c","b","a","new")),Arrays.asList("a","b","c","new"));
        JaLiveTvMoveSession move=new JaLiveTvMoveSession();
        move.begin(Arrays.asList("a","b","c"),"b");move.move(-1);
        equal(move.channel(),"b");equal(move.order(),Arrays.asList("b","a","c"));
        equal(stored,Arrays.asList("a","hidden","b","missing","c")); // draft never mutates persisted data
        move.clear();equal(move.channel(),null);equal(move.order(),Collections.emptyList());
        move.begin(Arrays.asList("a","b","c"),"c");move.move(1);equal(move.order(),Arrays.asList("a","b","c"));
        move.move(-1);move.move(-1);equal(move.order(),Arrays.asList("c","a","b"));
        move.begin(Arrays.asList("x","y"),"x");equal(move.channel(),"c"); // second pickup cannot destroy a draft
        equal(JaLiveTvMoveSession.merge(stored,Arrays.asList("a","hidden","b","c"),Arrays.asList("c","a","b")),Arrays.asList("c","hidden","a","missing","b"));
        equal(JaLiveTvMoveSession.merge(Collections.emptyList(),Arrays.asList("a","c"),Arrays.asList("c","b","a")),Arrays.asList("c","b","a")); // outage during movement
        List<String> news=JaLiveTvMoveSession.merge(Collections.emptyList(),Arrays.asList("a","b"),Arrays.asList("b","a"));
        equal(JaLiveTvMoveSession.displayed(Collections.emptyList(),Arrays.asList("a","b")),Arrays.asList("a","b")); // other groups independent
        equal(news,Arrays.asList("b","a"));
        try{JaLiveTvMoveSession.merge(stored,Arrays.asList("a","b"),Arrays.asList("a","a"));throw new AssertionError("Duplicate accepted");}catch(IllegalArgumentException expected){}
        move.clear();move.begin(Arrays.asList("a","b"),"nope");equal(move.channel(),null);
        System.out.println("IPTV channel move/save/cancel, filtered order, outage and group isolation checks passed");
    }
}
