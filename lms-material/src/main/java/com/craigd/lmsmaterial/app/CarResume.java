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
     * Paused at most this long before the car disconnected, the player still counts as playing then: switching the
     * car off pauses it, and Android Auto stays connected about a minute longer (seen 47-53 s: it takes the audio focus, or sends
     * pause).
     */
    public static final long PAUSED_BEFORE_DISCONNECT_MS = 80000;

    /**
     * Whether the player was playing when the car disconnected, from LMS's mode then and how long ago it was last
     * seen playing (ms, negative if not seen). Only then is the state saved: a stopped player (the queue played to
     * its end, Stop, or Power off) or one paused for a while isn't resumed.
     */
    /**
     * How long ago (ms) a player that LMS now has paused last played, when the last status seen still said playing.
     * The status push for the pause can be missed (CometD went quiet after a network change, car 2026-11-05: paused
     * 49 s before the disconnect, but last seen playing 198 s before), and the time since that status then overstates
     * it. Worked out from how far it played on since that status: on the same track the position difference, on the
     * next one the rest of the last-seen track plus the position. Otherwise (a jump, a seek back) sinceStatus.
     *
     * @param sinceStatus ms since the last status (playing) was received
     * @param lastIndex, lastTime, lastDuration that status' queue index, position and track duration (ms)
     * @param index, time LMS's queue index and position now (ms)
     */
    public static long sincePlayedOn(long sinceStatus, int lastIndex, long lastTime, long lastDuration, int index, long time) {
        long playedOn;
        if (index == lastIndex) {
            playedOn = time - lastTime;
        } else if (index == lastIndex + 1 && lastDuration > 0 && lastTime <= lastDuration) {
            playedOn = lastDuration - lastTime + time;
        } else {
            return sinceStatus;
        }
        if (playedOn < 0 || playedOn > sinceStatus) {
            return sinceStatus;
        }
        return sinceStatus - playedOn;
    }

    /**
     * False if the user paused the player (through this app) and it is still paused: the car's own pause at
     * switch-off doesn't count as the user's, so only this rules it out.
     */
    public static boolean wasPlaying(String mode, boolean pausedByUser) {
        return "play".equals(mode) || !pausedByUser;
    }

    public static boolean wasPlaying(String mode, long sincePlaying) {
        if ("play".equals(mode)) {
            return true;
        }
        return "pause".equals(mode) && sincePlaying >= 0 && sincePlaying <= PAUSED_BEFORE_DISCONNECT_MS;
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
