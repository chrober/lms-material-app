package com.craigd.lmsmaterial.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DailyOrderTest {
    private static final List<String> IDS = Arrays.asList("101", "102", "103", "104", "105", "106", "107", "108", "109", "110");

    @Test
    public void sameDaySameOrder() {
        assertEquals(LmsBrowseHelper.dailyOrder(IDS, "2026-10-31"), LmsBrowseHelper.dailyOrder(new ArrayList<>(IDS), "2026-10-31"));
    }

    @Test
    public void otherDayOtherOrder() {
        assertNotEquals(LmsBrowseHelper.dailyOrder(IDS, "2026-10-31"), LmsBrowseHelper.dailyOrder(IDS, "2026-11-01"));
    }

    @Test
    public void droppingAnAlbumKeepsTheOthersInOrder() {
        List<String> all = LmsBrowseHelper.dailyOrder(IDS, "2026-10-31");
        List<String> fewer = new ArrayList<>(IDS);
        fewer.remove("105");
        List<String> expected = new ArrayList<>(all);
        expected.remove("105");
        assertEquals(expected, LmsBrowseHelper.dailyOrder(fewer, "2026-10-31"));
    }

    @Test
    public void notTheInputOrder() {
        assertNotEquals(IDS, LmsBrowseHelper.dailyOrder(IDS, "2026-10-31"));
    }
}
