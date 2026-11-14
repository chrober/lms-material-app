package com.craigd.lmsmaterial.app;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QueueCopyTest {
    /**
     * One player's queue, handled as LMS 9.2 does: playlistcontrol looks tracks up with "id IN (...)" and sorts
     * them back into the given order, so a repeated ID is added once, at its last position.
     */
    static class FakeLms implements QueueCopy.Lms {
        final List<String> queue = new ArrayList<>();
        int index = -1;
        boolean playing = false;
        int shuffle = 0;
        int maxLength = 2500;
        int failAt = -1; // fail the request with this number (0 = first)
        int requests = 0;

        @Override
        public JSONObject request(String playerId, String... command) throws Exception {
            if (requests++ == failAt) {
                throw new Exception("timeout");
            }
            JSONObject result = new JSONObject();
            String cmd = String.join(" ", command);
            if (cmd.equals("status 0 0")) {
                result.put("playlist_tracks", queue.size());
            } else if (cmd.equals("playlist clear")) {
                queue.clear();
                index = -1;
                playing = false;
            } else if (cmd.startsWith("playlist shuffle ")) {
                shuffle = Integer.parseInt(command[2]);
            } else if (cmd.startsWith("playlistcontrol cmd:add track_id:")) {
                List<String> ids = Arrays.asList(command[2].substring("track_id:".length()).split(","));
                Map<String, Integer> order = new LinkedHashMap<>();
                for (int i = 0; i < ids.size(); i++) {
                    order.put(ids.get(i), i);
                }
                List<String> found = new ArrayList<>(order.keySet());
                found.sort((a, b) -> order.get(a) - order.get(b));
                for (String id : found) {
                    if (queue.size() < maxLength) {
                        queue.add("track:" + id);
                    }
                }
            } else if (cmd.startsWith("playlist add ")) {
                if (queue.size() < maxLength) {
                    queue.add(command[2]);
                }
            } else if (cmd.startsWith("playlist index ")) {
                int i = Integer.parseInt(command[2]);
                if (i < 0 || i >= queue.size()) {
                    throw new Exception("bad index");
                }
                index = i;
                playing = command.length < 5 || !"1".equals(command[4]);
            } else {
                throw new Exception("unexpected command: " + cmd);
            }
            return result;
        }
    }

    private static QueueCopy.Entry track(String id) {
        return new QueueCopy.Entry(id, "file:///music/" + id + ".flac#0-100");
    }

    private static QueueCopy.Entry url(String url) {
        return new QueueCopy.Entry(null, url);
    }

    private static QueueCopy.Snapshot snapshot(int index, QueueCopy.Entry... entries) {
        return new QueueCopy.Snapshot(Arrays.asList(entries), index);
    }

    private static FakeLms lmsWith(String... queue) {
        FakeLms lms = new FakeLms();
        lms.queue.addAll(Arrays.asList(queue));
        lms.index = queue.length > 0 ? 0 : -1;
        return lms;
    }

    @Test
    public void addCommandsSplitRunsAtRepeatsAndUrls() {
        List<String[]> commands = QueueCopy.addCommands(Arrays.asList(track("1"), track("2"), track("1"),
                url("http://radio/stream"), track("3"), track("3")), "-1");
        assertEquals(5, commands.size());
        assertEquals("playlistcontrol cmd:add track_id:1,2 library_id:-1", String.join(" ", commands.get(0)));
        assertEquals("playlistcontrol cmd:add track_id:1 library_id:-1", String.join(" ", commands.get(1)));
        assertEquals("playlist add http://radio/stream", String.join(" ", commands.get(2)));
        assertEquals("playlistcontrol cmd:add track_id:3 library_id:-1", String.join(" ", commands.get(3)));
        assertEquals("playlistcontrol cmd:add track_id:3 library_id:-1", String.join(" ", commands.get(4)));
    }

    @Test
    public void addCommandsLimitTheRunLength() {
        List<QueueCopy.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            entries.add(track(String.valueOf(i)));
        }
        assertEquals(3, QueueCopy.addCommands(entries, "-1").size());
    }

    @Test
    public void keepsOrderAndRepeatedTracks() {
        FakeLms lms = lmsWith("track:9");
        QueueCopy.Snapshot source = snapshot(3, track("1"), track("2"), track("1"), track("3"), track("2"));
        assertTrue(QueueCopy.copy(lms, "p", source, snapshot(0, track("9")), "-1"));
        assertEquals(Arrays.asList("track:1", "track:2", "track:1", "track:3", "track:2"), lms.queue);
        assertEquals(3, lms.index);
        assertTrue(lms.playing);
    }

    @Test
    public void keepsNonLibraryEntriesInPlace() {
        FakeLms lms = lmsWith();
        QueueCopy.Snapshot source = snapshot(1, track("1"), url("http://radio/stream"), track("2"));
        assertTrue(QueueCopy.copy(lms, "p", source, snapshot(0), "-1"));
        assertEquals(Arrays.asList("track:1", "http://radio/stream", "track:2"), lms.queue);
        assertEquals(1, lms.index);
    }

    @Test
    public void turnsShuffleOffToKeepThePlayOrder() {
        FakeLms lms = lmsWith("track:9");
        lms.shuffle = 1;
        assertTrue(QueueCopy.copy(lms, "p", snapshot(0, track("1"), track("2")), snapshot(0, track("9")), "-1"));
        assertEquals(0, lms.shuffle);
    }

    @Test
    public void restoresTheOldQueueWhenARequestFails() {
        QueueCopy.Snapshot source = snapshot(0, track("1"), track("2"), track("1"), url("http://radio/stream"));
        QueueCopy.Snapshot old = snapshot(2, track("7"), track("8"), track("7"), url("http://other/stream"));
        // Fail each request of the copy in turn (clear, shuffle, 3 adds, status, index): the old queue must be back.
        for (int failAt = 0; failAt < 7; failAt++) {
            FakeLms lms = lmsWith("track:7", "track:8", "track:7", "http://other/stream");
            lms.failAt = failAt;
            assertFalse("failing request " + failAt, QueueCopy.copy(lms, "p", source, old, "-1"));
            assertEquals("failing request " + failAt,
                    Arrays.asList("track:7", "track:8", "track:7", "http://other/stream"), lms.queue);
            assertEquals(2, lms.index);
            assertFalse(lms.playing);
        }
    }

    @Test
    public void restoresTheOldQueueWhenLmsDoesNotTakeAllEntries() {
        // LMS's maximum queue length: it silently adds fewer.
        FakeLms lms = lmsWith("track:7", "track:8");
        lms.maxLength = 3;
        QueueCopy.Snapshot source = snapshot(0, track("1"), track("2"), track("3"), track("4"));
        assertFalse(QueueCopy.copy(lms, "p", source, snapshot(1, track("7"), track("8")), "-1"));
        assertEquals(Arrays.asList("track:7", "track:8"), lms.queue);
        assertEquals(1, lms.index);
    }

    @Test
    public void anEmptyOldQueueStaysEmptyAfterAFailure() {
        FakeLms lms = lmsWith();
        lms.failAt = 2;
        assertFalse(QueueCopy.copy(lms, "p", snapshot(0, track("1"), track("2")), snapshot(0), "-1"));
        assertTrue(lms.queue.isEmpty());
    }

    @Test
    public void nothingToCopy() {
        FakeLms lms = lmsWith("track:9");
        assertFalse(QueueCopy.copy(lms, "p", snapshot(0), snapshot(0, track("9")), "-1"));
        assertEquals(Arrays.asList("track:9"), lms.queue);
        assertEquals(0, lms.requests);
    }
}
