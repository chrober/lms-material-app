package com.craigd.lmsmaterial.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarResumeTest {
    @Test
    public void serializeAndParse() {
        CarResume resume = CarResume.parse(new CarResume("cd:ef:d8:ee:07:69", 1234, 5, 61500).serialize());
        assertEquals("cd:ef:d8:ee:07:69", resume.player);
        assertEquals(1234, resume.trackId);
        assertEquals(5, resume.index);
        assertEquals(61500, resume.time);
    }

    @Test
    public void parseInvalid() {
        assertNull(CarResume.parse(null));
        assertNull(CarResume.parse(""));
        assertNull(CarResume.parse("p|1|2"));
        assertNull(CarResume.parse("p|x|2|3"));
        assertNull(CarResume.parse("p|0|2|3"));
        assertNull(CarResume.parse("p|1|-1|3"));
    }

    private static final CarResume SAVED = new CarResume("p", 9, 2, 60000);

    @Test
    public void notMovedOn() {
        assertNull(CarResume.movedOn(SAVED, 2, 9, 60000));
        assertNull(CarResume.movedOn(SAVED, 2, 9, 0)); // restarted player, stopped at 0:00
        assertNull(CarResume.movedOn(SAVED, 2, 9, 60000 + CarResume.PLAYED_ON_MS)); // played on until paused
    }

    @Test
    public void movedOn() {
        assertNotNull(CarResume.movedOn(SAVED, 3, 10, 0)); // next track
        assertNotNull(CarResume.movedOn(SAVED, 3, 9, 60000)); // same track, queue changed
        assertNotNull(CarResume.movedOn(SAVED, 2, 10, 60000)); // another track at that index
        assertNotNull(CarResume.movedOn(SAVED, 2, 9, 60001 + CarResume.PLAYED_ON_MS));
    }

    @Test
    public void wasPlaying() {
        assertTrue(CarResume.wasPlaying("play", -1));
        assertTrue(CarResume.wasPlaying("pause", 47000)); // car switched off: paused before the disconnect
        assertTrue(CarResume.wasPlaying("pause", CarResume.PAUSED_BEFORE_DISCONNECT_MS));
    }

    @Test
    public void wasNotPlaying() {
        assertFalse(CarResume.wasPlaying("stop", 0)); // queue played to its end (2026-11-02), Stop, Power off
        assertFalse(CarResume.wasPlaying("stop", -1));
        assertFalse(CarResume.wasPlaying("pause", CarResume.PAUSED_BEFORE_DISCONNECT_MS + 1)); // paused a while
        assertFalse(CarResume.wasPlaying("pause", -1)); // not seen playing
        assertFalse(CarResume.wasPlaying(null, 0));
    }

    @Test
    public void sincePlayedOnSameTrack() {
        // Car 2026-11-05: last status 198 s before the disconnect at 0:01, paused by the car at 2:30, 49 s before
        long since = CarResume.sincePlayedOn(198000, 3, 1000, 300000, 3, 150000);
        assertEquals(49000, since);
        assertTrue(CarResume.wasPlaying("pause", since));
        assertFalse(CarResume.wasPlaying("pause", 198000));
    }

    @Test
    public void sincePlayedOnNextTrack() {
        // 60 s left of track 3 at the last status, then 100 s into track 4: paused 40 s ago
        assertEquals(40000, CarResume.sincePlayedOn(200000, 3, 240000, 300000, 4, 100000));
    }

    @Test
    public void sincePlayedOnUnknown() {
        // A jump, a seek back, or more played than time went by: keep the time since the status
        assertEquals(200000, CarResume.sincePlayedOn(200000, 3, 1000, 300000, 7, 50000));
        assertEquals(200000, CarResume.sincePlayedOn(200000, 3, 100000, 300000, 3, 50000));
        assertEquals(200000, CarResume.sincePlayedOn(200000, 3, 0, 300000, 3, 250000));
        assertEquals(200000, CarResume.sincePlayedOn(200000, 3, 1000, 0, 4, 50000));
    }
}
