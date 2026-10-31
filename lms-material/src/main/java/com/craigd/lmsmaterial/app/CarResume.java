/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import androidx.annotation.NonNull;

/**
 * What the active player was playing when the car disconnected ("Resume after reconnecting"), so that it can be
 * played from there on the next connect, if the player hasn't moved on since.
 */
public class CarResume {
    public final String player;
    public final long trackId;
    public final int index; // playlist index of the track when saved
    public final long time; // position in ms

    public CarResume(String player, long trackId, int index, long time) {
        this.player = player;
        this.trackId = trackId;
        this.index = index;
        this.time = time;
    }

    public String serialize() {
        return player + "|" + trackId + "|" + index + "|" + time;
    }

    /** The saved state, or null if there is none or it can't be read. */
    public static CarResume parse(String value) {
        if (null==value) {
            return null;
        }
        String[] parts = value.split("\\|");
        if (4!=parts.length || parts[0].isEmpty()) {
            return null;
        }
        try {
            CarResume resume = new CarResume(parts[0], Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]));
            return resume.trackId > 0 && resume.index >= 0 && resume.time >= 0 ? resume : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Played on this far past the saved position, the player was used after the car disconnected. Allows for it
     * playing on a little after the state was saved, until the disconnect paused it.
     */
    public static final long PLAYED_ON_MS = 15000;

    /**
     * Why the player has moved on since the state was saved (the current queue index, track ID and position, 0 if
     * stopped), or null if it hasn't and the saved position should be played.
     */
    public static String movedOn(CarResume resume, int index, long trackId, long time) {
        if (trackId != resume.trackId) {
            return "another track is current";
        }
        if (index != resume.index) {
            return "the track is at another queue index";
        }
        if (time > resume.time + PLAYED_ON_MS) {
            return "the track was played on";
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "player:" + player + ", trackId:" + trackId + ", index:" + index + ", time:" + Utils.timeStr(time);
    }
}
