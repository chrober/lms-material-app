package com.craigd.lmsmaterial.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
}
