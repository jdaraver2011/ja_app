package com.example.ja;
import java.time.LocalDate;
public final class JaCatalogRefreshTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        LocalDate day=LocalDate.of(2026,11,13);
        JaCatalogRefresh refresh=new JaCatalogRefresh(day);
        refresh.foreground(day); refresh.tick(day);check(refresh.generation()==0);
        refresh.background();refresh.tick(day.plusDays(1));check(refresh.generation()==0);
        refresh.foreground(day.plusDays(1));check(refresh.generation()==1 && refresh.day().equals(day.plusDays(1)));
        refresh.foreground(day.plusDays(1));check(refresh.generation()==1);
        refresh.background();refresh.foreground(day.plusDays(1));check(refresh.generation()==2);
        refresh.tick(day.plusDays(2));check(refresh.generation()==3);
        refresh.tick(day.plusDays(2));check(refresh.generation()==3);
        refresh.tick(day);check(refresh.generation()==4); // A corrected device clock also refreshes.
        System.out.println("PASS: launch, same-day foreground return, midnight refresh, background deferral and repeated event suppression");
    }
}
