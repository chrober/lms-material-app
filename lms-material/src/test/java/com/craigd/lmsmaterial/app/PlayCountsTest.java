package com.craigd.lmsmaterial.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayCountsTest {
    /** A library of tracks (album, play count), served in pages like LMS's titles query. */
    static class Library implements LmsBrowseHelper.TrackPages {
        final int[][] tracks;
        int failAtPage = -1;
        int pages = 0;
        Integer countAfterFirstPage = null; // a scan changing the library while it is read

        Library(int[]... tracks) {
            this.tracks = tracks;
        }

        @Override
        public JSONObject page(int start, int count) throws Exception {
            if (pages++ == failAtPage) {
                return null;
            }
            JSONObject result = new JSONObject();
            result.put("count", pages > 1 && null!=countAfterFirstPage ? countAfterFirstPage : tracks.length);
            JSONArray loop = new JSONArray();
            for (int i = start; i < Math.min(tracks.length, start + count); i++) {
                loop.put(new JSONObject().put("album_id", String.valueOf(tracks[i][0])).put("playcount", tracks[i][1]));
            }
            result.put("titles_loop", loop);
            return result;
        }
    }

    // Album 1: unplayed. Album 2: its only played track is on the last page. Album 3: unplayed.
    private static Library library() {
        return new Library(new int[]{1, 0}, new int[]{1, 0}, new int[]{2, 0}, new int[]{2, 0}, new int[]{2, 0},
                new int[]{3, 0}, new int[]{2, 4});
    }

    @Test
    public void readsALibraryLargerThanAPage() throws Exception {
        Library library = library();
        Map<String, Integer> plays = LmsBrowseHelper.playCountsByAlbum(library, 3);
        assertEquals(3, library.pages);
        assertEquals(Integer.valueOf(4), plays.get("2"));
        assertEquals(Arrays.asList("1", "3"), LmsBrowseHelper.unplayed(plays));
    }

    @Test
    public void onePageIsEnoughForASmallLibrary() throws Exception {
        Library library = library();
        assertEquals(Arrays.asList("1", "3"), LmsBrowseHelper.unplayed(LmsBrowseHelper.playCountsByAlbum(library, 100)));
        assertEquals(1, library.pages);
    }

    @Test
    public void aFailedPageGivesNoResult() throws Exception {
        // Without the last page album 2 would look unplayed.
        Library library = library();
        library.failAtPage = 2;
        assertNull(LmsBrowseHelper.playCountsByAlbum(library, 3));
    }

    @Test
    public void aLibraryChangingWhileReadGivesNoResult() throws Exception {
        Library library = library();
        library.countAfterFirstPage = 8;
        assertNull(LmsBrowseHelper.playCountsByAlbum(library, 3));
    }

    @Test
    public void anEmptyLibrary() throws Exception {
        Library library = new Library();
        assertEquals(0, LmsBrowseHelper.playCountsByAlbum(library, 3).size());
    }
}
