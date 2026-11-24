/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces a player's queue with a list of entries (library tracks or URLs), keeping their order and repeated
 * tracks, and puts the player's old queue back if that fails.
 *
 * LMS's "playlistcontrol cmd:add track_id:a,b,c" looks the tracks up with "id IN (...)" and sorts them back into
 * the given order, so a track listed twice is added once. So tracks are added in runs without repeats.
 *
 * The old queue is restored from a snapshot the caller read (see Snapshot), not from a playlist saved in LMS:
 * LMS drops cue sheet tracks (file URLs ending in #start-end) when it loads a saved playlist.
 */
final class QueueCopy {
    interface Lms {
        /** Sends a command to LMS and returns its "result" object (null if there is none). */
        JSONObject request(String playerId, String... command) throws Exception;
    }

    /** Whether the copy is still wanted: false after a Pause or another player being selected. */
    interface Wanted {
        boolean get();
    }

    enum Result {
        NOT_COPIED, // failed or no longer wanted; the player's queue is as it was
        COPIED,     // copied, its current entry selected but not playing (no longer wanted once it was under way)
        PLAYING     // copied and playing
    }

    static final class Entry {
        final String trackId; // library track, or null
        final String url;

        Entry(String trackId, String url) {
            this.trackId = trackId;
            this.url = url;
        }
    }

    /** A player's queue: its entries in play order (as LMS lists them, shuffled if shuffle is on), and the current one. */
    static final class Snapshot {
        final List<Entry> entries;
        final int index;

        Snapshot(List<Entry> entries, int index) {
            this.entries = entries;
            this.index = index;
        }
    }

    static final int MAX_RUN = 100;

    // A queue read with a limit on the number of entries: does it hold the whole queue of queueLength tracks?
    // A longer queue can't be copied, or put back after a failed copy, in full.
    static boolean isComplete(Snapshot snapshot, int queueLength) {
        return null!=snapshot && snapshot.entries.size() >= queueLength;
    }

    private QueueCopy() {
    }

    /**
     * The commands that add the entries to the end of the queue, in order: runs of library tracks without a
     * repeated track in one playlistcontrol command each, other entries by URL.
     */
    static List<String[]> addCommands(List<Entry> entries, String libraryId) {
        List<String[]> commands = new ArrayList<>();
        List<String> run = new ArrayList<>();
        Set<String> inRun = new HashSet<>();
        for (Entry entry : entries) {
            if (null==entry.trackId || inRun.contains(entry.trackId) || run.size() >= MAX_RUN) {
                addRun(commands, run, libraryId);
                inRun.clear();
            }
            if (null==entry.trackId) {
                commands.add(new String[]{"playlist", "add", entry.url});
            } else {
                run.add(entry.trackId);
                inRun.add(entry.trackId);
            }
        }
        addRun(commands, run, libraryId);
        return commands;
    }

    private static void addRun(List<String[]> commands, List<String> run, String libraryId) {
        if (!run.isEmpty()) {
            commands.add(new String[]{"playlistcontrol", "cmd:add", "track_id:" + String.join(",", run), "library_id:" + libraryId});
            run.clear();
        }
    }

    /**
     * Replaces the queue of playerId (currently old) with the source's entries and plays its current one. Returns
     * NOT_COPIED if that failed; the old queue is then put back (not playing). Either way the queue is left in play
     * order with shuffle off: switching shuffle on again would reshuffle it.
     *
     * The copy is checked to be still wanted twice. Before the queue is touched: if not, nothing is changed.
     * Once it has been replaced: if not, the copy is kept (a Pause stops the sound, it doesn't undo the copy) but
     * its current entry isn't played.
     */
    static Result copy(Lms lms, String playerId, Snapshot source, Snapshot old, String libraryId, Wanted wanted) {
        if (source.entries.isEmpty()) {
            return Result.NOT_COPIED;
        }
        if (!wanted.get()) {
            Utils.debug("Copy queue: no longer wanted, the queue of " + playerId + " is left as it was");
            return Result.NOT_COPIED;
        }
        try {
            fill(lms, playerId, source.entries, libraryId);
            if (wanted.get()) {
                lms.request(playerId, "playlist", "index", String.valueOf(source.index));
                return Result.PLAYING;
            }
            Utils.debug("Copy queue: no longer wanted once copied, not playing it on " + playerId);
            lms.request(playerId, "playlist", "index", String.valueOf(source.index), "0", "1");
            return Result.COPIED;
        } catch (Exception e) {
            Utils.debug("Copy queue: failed (" + e.getMessage() + "), restoring the queue of " + playerId);
        }
        try {
            fill(lms, playerId, old.entries, libraryId);
            if (!old.entries.isEmpty()) {
                // Make it the current track without playing it
                lms.request(playerId, "playlist", "index", String.valueOf(old.index), "0", "1");
            }
        } catch (Exception e) {
            Utils.debug("Copy queue: couldn't restore the queue of " + playerId + ": " + e.getMessage());
        }
        return Result.NOT_COPIED;
    }

    // Replaces the queue with the entries, in this order (shuffle off), and checks that LMS has them all.
    private static void fill(Lms lms, String playerId, List<Entry> entries, String libraryId) throws Exception {
        lms.request(playerId, "playlist", "clear");
        lms.request(playerId, "playlist", "shuffle", "0");
        for (String[] command : addCommands(entries, libraryId)) {
            lms.request(playerId, command);
        }
        JSONObject status = lms.request(playerId, "status", "0", "0");
        int count = null==status ? -1 : status.optInt("playlist_tracks", -1);
        if (count!=entries.size()) {
            throw new Exception(count + " entries in the queue, " + entries.size() + " expected");
        }
    }
}
