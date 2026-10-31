/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

/**
 * Track ratings as stored by LMS (and the Ratings Light plugin): 0..100, where 20 = one star
 * and 0 = unrated. Half stars (e.g. 70) are shown as "½".
 */
public class Ratings {
    public static final int MAX_STARS = 5;
    private static final char FULL = '★';
    private static final char EMPTY = '☆';
    private static final char HALF = '½';

    public static int clamp(int rating) {
        return Math.max(0, Math.min(100, rating));
    }

    public static String stars(int rating) {
        rating = clamp(rating);
        int full = rating / 20;
        boolean half = rating % 20 >= 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_STARS; i++) {
            sb.append(i < full ? FULL : (i == full && half ? HALF : EMPTY));
        }
        return sb.toString();
    }

    /** Nearest whole number of stars, as shown on the rating button. */
    public static int roundedStars(int rating) {
        return Math.round(clamp(rating) / 20.0f);
    }

    /** Rating button order: Unrated -> 5 -> 4 -> 3 -> 2 -> 1 -> Unrated. */
    public static int nextStars(int rating) {
        int stars = roundedStars(rating);
        return 0 == stars ? MAX_STARS : stars - 1;
    }
}
