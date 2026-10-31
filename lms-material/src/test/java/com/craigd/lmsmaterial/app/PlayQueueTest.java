package com.craigd.lmsmaterial.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayQueueTest {
    @Test
    public void smallQueueIsShownInFull() {
        assertEquals(0, PlayQueue.windowStart(3, 13));
        assertEquals(0, PlayQueue.windowStart(99, 100));
    }

    @Test
    public void largeQueueStartsJustBeforeCurrentTrack() {
        assertEquals(0, PlayQueue.windowStart(5, 1000));
        assertEquals(490, PlayQueue.windowStart(500, 1000));
    }

    @Test
    public void largeQueueWindowStaysWithinPlaylist() {
        assertEquals(900, PlayQueue.windowStart(995, 1000));
    }
}
