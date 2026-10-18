package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HawkerServletTest {

    private static List<HawkerServlet.Closure> find(LocalDate from, LocalDate to) {
        return HawkerServlet.findClosures(TestData.HAWKER_JSON, from, to);
    }

    @Test
    void todayListsOnlyTheLongRunningWorks() {
        LocalDate d = LocalDate.of(2026, 10, 17);
        List<HawkerServlet.Closure> r = find(d, d);
        assertEquals(1, r.size());
        assertEquals("Long Works Centre", r.get(0).name());
    }

    @Test
    void fourteenDayWindowFromMidOctober() {
        LocalDate d = LocalDate.of(2026, 10, 17);
        assertEquals(1, find(d, d.plusDays(14)).size());
    }

    @Test
    void sevenDecemberListsTwoSortedByStartDate() {
        LocalDate dec = LocalDate.of(2026, 12, 7);
        List<HawkerServlet.Closure> r = find(dec, dec);
        assertEquals(2, r.size());
        assertEquals("Long Works Centre", r.get(0).name());
        assertEquals("Adam Road Food Centre", r.get(1).name());
        assertEquals("Q4 cleaning", r.get(1).type());
    }

    @Test
    void blankEndDateCountsAsOneDay() {
        LocalDate jun = LocalDate.of(2026, 6, 8);
        List<HawkerServlet.Closure> r = find(jun, jun);
        assertEquals(1, r.size());
        assertEquals(r.get(0).start(), r.get(0).end());
    }

    @Test
    void tbcEndDateCountsAsOneDay() {
        LocalDate sep = LocalDate.of(2026, 9, 7);
        assertEquals(1, find(sep, sep).size());
    }

    @Test
    void lastDayOfARangeIsIncluded() {
        LocalDate mar = LocalDate.of(2026, 3, 31);
        List<HawkerServlet.Closure> r = find(mar, mar);
        assertEquals(1, r.size());
        assertTrue(r.get(0).remarks().isEmpty(), "\"NA\" remarks become empty");
    }

    @Test
    void otherWorksRangeInThePast() {
        LocalDate old = LocalDate.of(2024, 5, 20);
        List<HawkerServlet.Closure> r = find(old, old);
        assertEquals(1, r.size());
        assertTrue(r.get(0).remarks().contains("Repairs"));
    }

    @Test
    void recordsWithoutReadableDatesGiveAClearError() {
        LocalDate d = LocalDate.of(2026, 10, 17);
        assertThrows(IllegalStateException.class,
                () -> HawkerServlet.findClosures("{\"result\":{\"records\":[{\"name\":\"X\"}]}}", d, d));
    }
}