package com.example.ja;
import java.util.*;
public final class JaLiveTvOrderTest {
    private static void equal(Object actual,Object expected){if(!actual.equals(expected))throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        Set<String> favorites=new HashSet<>(Arrays.asList("a","b","c"));
        equal(JaLiveTvOrder.reconcile(Collections.emptyList(),favorites,Arrays.asList("b","a","c")),Arrays.asList("b","a","c"));
        List<String> reordered=JaLiveTvOrder.move(Arrays.asList("b","a","c"),"a",-1);
        equal(reordered,Arrays.asList("a","b","c"));
        equal(JaLiveTvOrder.reconcile(reordered,favorites,Arrays.asList("c","b")),Arrays.asList("a","b","c")); // outage preserves order
        equal(JaLiveTvOrder.reconcile(reordered,new HashSet<>(Arrays.asList("b","c","d")),Arrays.asList("d","c","b")),Arrays.asList("b","c","d"));
        equal(JaLiveTvOrder.reconcile(Arrays.asList("b","b","a"),favorites,Arrays.asList("c","b")),Arrays.asList("b","a","c"));
        equal(JaLiveTvOrder.move(reordered,"a",-1),reordered);
        equal(JaLiveTvOrder.move(reordered,"c",1),reordered);
        equal(JaLiveTvOrder.move(reordered,"missing",1),reordered);
        equal(JaLiveTvOrder.move(reordered,"b",1),Arrays.asList("a","c","b"));
        System.out.println("IPTV favorites migration, reorder, outage and boundary checks passed");
    }
}
