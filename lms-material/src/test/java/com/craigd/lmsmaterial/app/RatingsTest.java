package com.craigd.lmsmaterial.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RatingsTest {
    @Test
    public void unratedShowsFiveHollowStars() {
        assertEquals("☆☆☆☆☆", Ratings.stars(0));
    }

    @Test
    public void showsFullAndHalfStars() {
        assertEquals("★★★★☆", Ratings.stars(80));
        assertEquals("★★★½☆", Ratings.stars(70));
        assertEquals("★★★★★", Ratings.stars(100));
    }

    @Test
    public void cyclesThroughRatings() {
        assertEquals(5, Ratings.nextStars(0));
        assertEquals(4, Ratings.nextStars(100));
        assertEquals(3, Ratings.nextStars(80));
        assertEquals(0, Ratings.nextStars(20));
    }
}
