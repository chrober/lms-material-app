/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

/**
 * The play queue shown in Android Auto is a window of the LMS playlist around the current
 * track, as large queues would exceed the media session's binder size limit.
 */
public class PlayQueue {
    public static final int MAX_ITEMS = 100;
    public static final int ITEMS_BEFORE_CURRENT = 10;

    /** First playlist index of the window to show, for a playlist of total tracks. */
    public static int windowStart(int currentIndex, int total) {
        if (total <= MAX_ITEMS) {
            return 0;
        }
        int start = Math.max(0, currentIndex - ITEMS_BEFORE_CURRENT);
        return Math.min(start, total - MAX_ITEMS);
    }
}
