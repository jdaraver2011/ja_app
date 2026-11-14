package com.example.ja;

import java.time.LocalDate;

/** One new catalogue generation per background return or foreground date change. */
public final class JaCatalogRefresh {
    private LocalDate day;
    private long generation;
    private boolean foreground = true;
    public JaCatalogRefresh(LocalDate initialDay) { day = initialDay; }
    public LocalDate day() { return day; }
    public long generation() { return generation; }
    public void background() { foreground = false; }
    public void foreground(LocalDate now) {
        if (!foreground || !day.equals(now)) generation++;
        day = now;
        foreground = true;
    }
    public void tick(LocalDate now) {
        if (foreground && !day.equals(now)) {
            day = now;
            generation++;
        }
    }
}
