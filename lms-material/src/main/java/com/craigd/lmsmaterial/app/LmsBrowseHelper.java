/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.media.utils.MediaConstants;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class LmsBrowseHelper {
    public static final String ACTIVE_LIBRARY_PREF_KEY = "active_library";
    public static final String ROOT_ID = "__ROOT__";
    public static final String ARTISTS_GROUP_ID = "__ARTISTS_GROUP__";
    public static final String RELEASES_GROUP_ID = "__RELEASES_GROUP__";
    public static final String ALBUM_ARTISTS_ID = "__ALBUM_ARTISTS__";
    public static final String ALL_ARTISTS_ID = "__ALL_ARTISTS__";
    public static final String NEW_ARTISTS_ID = "__NEW_ARTISTS__";
    public static final String ALBUMS_NEW_ID = "__ALBUMS_NEW__";
    public static final String ALBUMS_RANDOM_ID = "__ALBUMS_RANDOM__";
    public static final String ALBUMS_RECENTLY_PLAYED_ID = "__ALBUMS_RECENTLY_PLAYED__";
    public static final String ALBUMS_REDISCOVER_ID = "__ALBUMS_REDISCOVER__";
    public static final String ALBUMS_UNPLAYED_ID = "__ALBUMS_UNPLAYED__";
    public static final String ALBUMS_ALPHA_ID = "__ALBUMS_ALPHA__";
    public static final String ALBUMS_BY_YEAR_ID = "__ALBUMS_BY_YEAR__";
    public static final String FAVORITES_ID = "__FAVORITES__";
    public static final String PLAYLISTS_ID = "__PLAYLISTS__";
    public static final String PLAYERS_ID = "__PLAYERS__";
    public static final String LIBRARIES_ID = "__LIBRARIES__";
    public static final String COPY_QUEUE_ID = "__COPY_QUEUE__";
    public static final String SHOW_UNPLAYED_PREF_KEY = "auto_show_unplayed";
    public static final String REDISCOVER_MONTHS_PREF_KEY = "auto_rediscover_months";
    private static final String DEFAULT_REDISCOVER_MONTHS = "12";
    private static final String COPY_QUEUE_PREFIX = "copyqueue/"; // + ID of the player to copy the queue from
    public static final String ALBUM_CLICK_PREF_KEY = "auto_album_click";
    public static final String ALBUM_CLICK_TRACKS = "tracks";
    public static final String ALBUM_CLICK_PLAY = "play";
    // "Add to queue" row in an album's or playlist's track list (+ "album/<id>" or "playlist/<id>"). A row rather
    // than a custom browse action: Android Auto (17.9) gives the app none.
    private static final String QUEUE_ADD_PREFIX = "queueadd/";
    private static final String ALBUM_TRACK_PREFIX = "albumtrack/";
    private static final String LETTERS_PREFIX = "letters/"; // + A-Z list ID: pick a letter of that list
    private static final String ALL_LIBRARIES_ID = "-1"; // LMS: no library restriction

    private static final String LIST_IMAGE_SIZE = "_300x300_f";
    private static final int BROWSE_LIMIT = 10000;
    private static final int PAGE_SIZE = 500;
    // Letters with more show this many, after a "Show all" entry. The whole A-Z list goes to Android Auto in
    // one binder transaction (~0.5KB per item), which fails - leaving an endless spinner - above ~500KB.
    private static final int GROUP_PREVIEW_COUNT = 15;
    private static final int INDEX_FETCH_LIMIT = 25000;
    private static final int SEARCH_LIMIT = 50;
    private static final int TIMEOUT_MS = 15000;
    private static final int INDEX_TIMEOUT_MS = 45000;
    private static final int REDISCOVER_PAGE_SIZE = 100;
    // Unplayed Albums needs play counts, from one query over all tracks (~4MB for 55k tracks), so they are cached.
    private static final long PLAY_COUNTS_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final int PLAY_COUNTS_TRACK_LIMIT = 200000;

    private static final String LABEL_TOKENS = "BROWSE_BY_ARTIST,BROWSE_BY_ALBUMARTIST,BROWSE_BY_ALL_ARTISTS,BROWSE_BY_ALBUM,BROWSE_NEW_MUSIC,FAVORITES,SAVED_PLAYLISTS,PLAYERS,PLUGIN_MATERIAL_SKIN_NEW_ARTISTS,PLUGIN_MATERIAL_SKIN_RANDOM_MIX,ARTISTS,ALBUMS,SONGS,LIBRARY,MORE,YEAR";

    private final JsonRpc rpc;
    private final SharedPreferences prefs;
    private final String packageName;
    private final Context context;
    private Integer groupByReleaseType = null;
    private Map<String, String> labels = null;
    private List<GroupIndex> albumArtistIndex = null;
    private List<GroupIndex> allArtistIndex = null;
    private List<GroupIndex> albumsAlphaIndex = null;
    private volatile AlbumTracks lastAlbumTracks = null;
    // Rediscover / Unplayed Albums, built once a day (per library and setting), in an order fixed for the day
    private final Map<String, DailyList> dailyLists = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object playCountsLock = new Object();
    private UnplayedAlbums unplayedAlbums = null;

    private static final String[] RELEASE_TYPE_ORDER = {"ALBUM", "EP", "BOXSET", "BESTOF", "COMPILATION", "SINGLE", "APPEARANCE"};

    private static class AlbumTracks {
        final String albumId;
        final List<String> trackIds;

        AlbumTracks(String albumId, List<String> trackIds) {
            this.albumId = albumId;
            this.trackIds = trackIds;
        }
    }

    private static class UnplayedAlbums {
        final String libraryId;
        final long time;
        final List<String> ids;

        UnplayedAlbums(String libraryId, long time, List<String> ids) {
            this.libraryId = libraryId;
            this.time = time;
            this.ids = ids;
        }
    }

    private static class GroupIndex {
        final String key;
        final int offset;
        final int count;

        GroupIndex(String key, int offset, int count) {
            this.key = key;
            this.offset = offset;
            this.count = count;
        }
    }

    public LmsBrowseHelper(Context context) {
        rpc = new JsonRpc(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        packageName = context.getPackageName();
        this.context = context.getApplicationContext();
    }

    public void reset() {
        groupByReleaseType = null;
        labels = null;
        albumArtistIndex = null;
        allArtistIndex = null;
        albumsAlphaIndex = null;
        lastAlbumTracks = null;
        dailyLists.clear();
        synchronized (playCountsLock) {
            unplayedAlbums = null;
        }
    }

    private boolean shouldGroupByReleaseType() {
        if (null==groupByReleaseType) {
            groupByReleaseType = 0;
            try {
                JSONObject resp = rpc.sendMessageSync("", new String[]{"pref", "ignoreReleaseTypes", "?"}, TIMEOUT_MS);
                if (null!=resp) {
                    JSONObject result = resp.optJSONObject("result");
                    if (null!=result && "1".equals(result.optString("_p2", "0"))) {
                        return false;
                    }
                }
                resp = rpc.sendMessageSync("", new String[]{"pref", "groupArtistAlbumsByReleaseType", "?"}, TIMEOUT_MS);
                if (null!=resp) {
                    JSONObject result = resp.optJSONObject("result");
                    if (null!=result) {
                        groupByReleaseType = Integer.parseInt(result.optString("_p2", "0"));
                    }
                }
            } catch (Exception e) {
                Utils.debug("Failed to query release type prefs");
            }
        }
        return groupByReleaseType > 0;
    }

    private String releaseTypeHeader(String type) {
        switch (type) {
            case "ALBUM": return "Albums";
            case "EP": return "EPs";
            case "SINGLE": return "Singles";
            case "COMPILATION": return "Compilations";
            case "BOXSET": return "Box Sets";
            case "BESTOF": return "Best Of";
            case "APPEARANCE": return "Appearances";
            default: return type.substring(0, 1).toUpperCase(Locale.ROOT) +
                    type.substring(1).toLowerCase(Locale.ROOT);
        }
    }

    private void ensureLabelsLoaded() {
        if (null!=labels) return;
        labels = new HashMap<>();
        labels.put("BROWSE_BY_ARTIST", "Artists");
        labels.put("BROWSE_BY_ALBUMARTIST", "Album Artists");
        labels.put("BROWSE_BY_ALL_ARTISTS", "All Artists");
        labels.put("BROWSE_BY_ALBUM", "Albums");
        labels.put("BROWSE_NEW_MUSIC", "New Music");
        labels.put("FAVORITES", "Favorites");
        labels.put("SAVED_PLAYLISTS", "Playlists");
        labels.put("PLAYERS", "Players");
        labels.put("NEW_ARTISTS", "New Artists");
        labels.put("RANDOM_ALBUMS", "Random Albums");
        labels.put("RECENTLY_PLAYED_ALBUMS", "Recently Played Albums");
        labels.put("REDISCOVER_ALBUMS", context.getString(R.string.rediscover_albums));
        labels.put("UNPLAYED_ALBUMS", context.getString(R.string.unplayed_albums));
        labels.put("ARTISTS", "Artists");
        labels.put("ALBUMS", "Albums");
        labels.put("SONGS", "Songs");
        labels.put("LIBRARY", "Libraries");
        labels.put("ALL_TRACKS", "All");
        labels.put("MORE", "More");
        labels.put("YEAR", "Year");
        try {
            JSONObject resp = rpc.sendMessageSync("", new String[]{"getstring", LABEL_TOKENS}, TIMEOUT_MS);
            if (null!=resp) {
                JSONObject result = resp.optJSONObject("result");
                if (null!=result) {
                    Iterator<String> keys = result.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = result.optString(key, "");
                        if (!value.isEmpty()) {
                            labels.put(key, value);
                        }
                    }
                    String newArtists = result.optString("PLUGIN_MATERIAL_SKIN_NEW_ARTISTS", "");
                    if (!newArtists.isEmpty()) {
                        labels.put("NEW_ARTISTS", newArtists);
                    }
                    String randomMix = result.optString("PLUGIN_MATERIAL_SKIN_RANDOM_MIX", "");
                    if (!randomMix.isEmpty()) {
                        labels.put("RANDOM_ALBUMS", randomMix);
                    }
                }
            }
        } catch (Exception e) {
            Utils.debug("Failed to load labels from server");
        }
    }

    private String getLabel(String key) {
        String val = labels.get(key);
        return null!=val ? val : key;
    }

    private String getLibraryId() {
        return prefs.getString(ACTIVE_LIBRARY_PREF_KEY, null);
    }

    private void addLibraryParam(List<String> params) {
        String lib = getLibraryId();
        if (null!=lib) {
            params.add("library_id:" + lib);
        }
    }

    public List<MediaBrowserCompat.MediaItem> loadChildren(String parentMediaId) {
        if (ROOT_ID.equals(parentMediaId)) {
            return loadRoot();
        } else if (ARTISTS_GROUP_ID.equals(parentMediaId)) {
            return loadArtistsGroup();
        } else if (RELEASES_GROUP_ID.equals(parentMediaId)) {
            return loadReleasesGroup();
        } else if (parentMediaId.startsWith(LETTERS_PREFIX)) {
            return loadLetters(parentMediaId.substring(LETTERS_PREFIX.length()));
        } else if (ALBUM_ARTISTS_ID.equals(parentMediaId)) {
            return loadArtistsGrouped(ALBUM_ARTISTS_ID, true);
        } else if (parentMediaId.startsWith(ALBUM_ARTISTS_ID + ":")) {
            return loadArtistGroupPage(parentMediaId, ALBUM_ARTISTS_ID, true);
        } else if (ALL_ARTISTS_ID.equals(parentMediaId)) {
            return loadArtistsGrouped(ALL_ARTISTS_ID, false);
        } else if (parentMediaId.startsWith(ALL_ARTISTS_ID + ":")) {
            return loadArtistGroupPage(parentMediaId, ALL_ARTISTS_ID, false);
        } else if (NEW_ARTISTS_ID.equals(parentMediaId) || parentMediaId.startsWith(NEW_ARTISTS_ID + "/")) {
            return loadNewArtists(parseOffset(parentMediaId, NEW_ARTISTS_ID));
        } else if (ALBUMS_ALPHA_ID.equals(parentMediaId)) {
            return loadAlbumsAlphaGrouped();
        } else if (parentMediaId.startsWith(ALBUMS_ALPHA_ID + ":")) {
            return loadAlbumsAlphaPage(parentMediaId);
        } else if (ALBUMS_BY_YEAR_ID.equals(parentMediaId)) {
            return loadAlbumsByYearGrouped();
        } else if (parentMediaId.startsWith(ALBUMS_BY_YEAR_ID + ":")) {
            return loadAlbumsByYearPage(parentMediaId);
        } else if (ALBUMS_NEW_ID.equals(parentMediaId) || parentMediaId.startsWith(ALBUMS_NEW_ID + "/")) {
            return loadAlbums("sort:new", parseOffset(parentMediaId, ALBUMS_NEW_ID));
        } else if (ALBUMS_RANDOM_ID.equals(parentMediaId) || parentMediaId.startsWith(ALBUMS_RANDOM_ID + "/")) {
            return loadAlbums("sort:random", parseOffset(parentMediaId, ALBUMS_RANDOM_ID));
        } else if (ALBUMS_RECENTLY_PLAYED_ID.equals(parentMediaId) || parentMediaId.startsWith(ALBUMS_RECENTLY_PLAYED_ID + "/")) {
            return loadAlbums("sort:recentlyplayed", parseOffset(parentMediaId, ALBUMS_RECENTLY_PLAYED_ID));
        } else if (ALBUMS_REDISCOVER_ID.equals(parentMediaId) || parentMediaId.startsWith(ALBUMS_REDISCOVER_ID + "/")) {
            return loadShuffledAlbums(ALBUMS_REDISCOVER_ID, parseOffset(parentMediaId, ALBUMS_REDISCOVER_ID));
        } else if (ALBUMS_UNPLAYED_ID.equals(parentMediaId) || parentMediaId.startsWith(ALBUMS_UNPLAYED_ID + "/")) {
            return loadShuffledAlbums(ALBUMS_UNPLAYED_ID, parseOffset(parentMediaId, ALBUMS_UNPLAYED_ID));
        } else if (FAVORITES_ID.equals(parentMediaId)) {
            return loadFavorites();
        } else if (PLAYLISTS_ID.equals(parentMediaId)) {
            return loadPlaylists();
        } else if (PLAYERS_ID.equals(parentMediaId)) {
            return loadPlayers();
        } else if (LIBRARIES_ID.equals(parentMediaId)) {
            return loadLibraries();
        } else if (COPY_QUEUE_ID.equals(parentMediaId)) {
            return loadCopyQueueSources();
        } else if (parentMediaId.startsWith("artist/")) {
            return loadArtistAlbums(parentMediaId.substring(7));
        } else if (parentMediaId.startsWith("album/")) {
            return loadAlbumTracks(parentMediaId.substring(6));
        } else if (parentMediaId.startsWith("playlist/")) {
            return loadPlaylistTracks(parentMediaId.substring(9));
        } else if (parentMediaId.startsWith("favorite_folder/")) {
            return loadFavoriteFolder(parentMediaId.substring(16));
        }
        return new ArrayList<>();
    }

    private int parseOffset(String mediaId, String prefix) {
        if (mediaId.length() > prefix.length() + 1) {
            try {
                return Integer.parseInt(mediaId.substring(prefix.length() + 1));
            } catch (NumberFormatException e) {
                Utils.debug("Failed to parse offset from: " + mediaId);
            }
        }
        return 0;
    }

    private List<MediaBrowserCompat.MediaItem> loadRoot() {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        for (String tab : AutoTabOrder.parse(prefs.getString(AutoTabOrder.PREF_KEY, null))) {
            switch (tab) {
                case AutoTabOrder.ALBUMS:
                    items.add(buildBrowsableItem(RELEASES_GROUP_ID, getLabel("BROWSE_BY_ALBUM"), drawableUri(R.drawable.ic_release)));
                    break;
                case AutoTabOrder.ARTISTS:
                    items.add(buildBrowsableItem(ARTISTS_GROUP_ID, getLabel("BROWSE_BY_ARTIST"), drawableUri(R.drawable.ic_artist)));
                    break;
                case AutoTabOrder.FAVORITES:
                    items.add(buildBrowsableItem(FAVORITES_ID, getLabel("FAVORITES"), drawableUri(R.drawable.ic_favorite)));
                    break;
                case AutoTabOrder.PLAYLISTS:
                    items.add(buildBrowsableItem(PLAYLISTS_ID, getLabel("SAVED_PLAYLISTS"), drawableUri(R.drawable.ic_playlist)));
                    break;
                case AutoTabOrder.PLAYERS:
                    items.add(buildBrowsableItem(PLAYERS_ID, getLabel("PLAYERS"), drawableUri(R.drawable.ic_speaker)));
                    break;
                case AutoTabOrder.LIBRARIES: {
                    // Name a selected library on the tab, so a filtered library can't go unnoticed.
                    String label = getLabel("LIBRARY");
                    String lib = getLibraryId();
                    JSONArray libraries = null==lib ? null : fetchLibraries();
                    String name = null==libraries ? null : libraryName(libraries, lib);
                    if (null!=name) {
                        label += " \u00b7 " + name;
                    }
                    items.add(buildBrowsableItem(LIBRARIES_ID, label, drawableUri(R.drawable.ic_library)));
                    break;
                }
                case AutoTabOrder.COPY_QUEUE:
                    items.add(buildBrowsableItem(COPY_QUEUE_ID, context.getString(R.string.auto_tab_copy_queue), drawableUri(R.drawable.ic_copy_queue)));
                    break;
            }
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistsGroup() {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        items.add(buildBrowsableItem(ALBUM_ARTISTS_ID, getLabel("BROWSE_BY_ALBUMARTIST"), drawableUri(R.drawable.ic_album_artist)));
        items.add(buildBrowsableItem(ALL_ARTISTS_ID, getLabel("BROWSE_BY_ALL_ARTISTS"), drawableUri(R.drawable.ic_artist)));
        items.add(buildBrowsableItem(NEW_ARTISTS_ID, getLabel("NEW_ARTISTS"), drawableUri(R.drawable.ic_artist_new)));
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadReleasesGroup() {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        items.add(buildBrowsableItem(ALBUMS_NEW_ID, getLabel("BROWSE_NEW_MUSIC"), drawableUri(R.drawable.ic_new_releases)));
        items.add(buildBrowsableItem(ALBUMS_RANDOM_ID, getLabel("RANDOM_ALBUMS"), drawableUri(R.drawable.ic_dice_release)));
        items.add(buildBrowsableItem(ALBUMS_RECENTLY_PLAYED_ID, getLabel("RECENTLY_PLAYED_ALBUMS"), drawableUri(R.drawable.ic_history)));
        items.add(buildBrowsableItem(ALBUMS_REDISCOVER_ID, getLabel("REDISCOVER_ALBUMS"), drawableUri(R.drawable.ic_rediscover)));
        if (showUnplayed()) {
            items.add(buildBrowsableItem(ALBUMS_UNPLAYED_ID, getLabel("UNPLAYED_ALBUMS"), drawableUri(R.drawable.ic_unplayed)));
        }
        items.add(buildBrowsableItem(ALBUMS_ALPHA_ID, getLabel("BROWSE_BY_ALBUM") + " (A-Z)", drawableUri(R.drawable.ic_release)));
        items.add(buildBrowsableItem(ALBUMS_BY_YEAR_ID, getLabel("BROWSE_BY_ALBUM") + " (" + getLabel("YEAR") + ")", drawableUri(R.drawable.ic_release)));
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistsGrouped(String baseId, boolean albumArtistsOnly) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add("0");
            params.add(String.valueOf(INDEX_FETCH_LIMIT));
            if (albumArtistsOnly) params.add("role_id:5");
            params.add("tags:s");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), INDEX_TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("artists_loop");
            if (null==loop) return items;

            List<GroupIndex> index = albumArtistsOnly ? albumArtistIndex : allArtistIndex;
            if (null==index) {
                index = buildLetterIndexFromLoop(loop);
                if (albumArtistsOnly) {
                    albumArtistIndex = index;
                } else {
                    allArtistIndex = index;
                }
            }

            items.add(buildLettersItem(baseId));
            // Each letter is its own section; large ones start with an entry for the full list.
            for (GroupIndex group : index) {
                int displayCount = group.count > GROUP_PREVIEW_COUNT + 1 ? GROUP_PREVIEW_COUNT : group.count;
                if (displayCount < group.count) {
                    items.add(buildShowAllItem(baseId + ":" + group.key, group, "artists"));
                }
                for (int i = 0; i < displayCount && (group.offset + i) < loop.length(); i++) {
                    JSONObject artist = loop.getJSONObject(group.offset + i);
                    String id = artist.optString("id", "");
                    String name = artist.optString("artist", "");
                    Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + id + "/image_300x300_f");
                    items.add(buildBrowsableItemWithGroup("artist/" + id, name, artUri, group.key));
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to load grouped artists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumsAlphaGrouped() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add("0");
            params.add(String.valueOf(INDEX_FETCH_LIMIT));
            params.add("sort:album");
            params.add("tags:ajlsy");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), INDEX_TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            if (null==albumsAlphaIndex) {
                albumsAlphaIndex = buildLetterIndexFromLoop(loop);
            }

            items.add(buildLettersItem(ALBUMS_ALPHA_ID));
            // Each letter is its own section; large ones start with an entry for the full list.
            for (GroupIndex group : albumsAlphaIndex) {
                int displayCount = group.count > GROUP_PREVIEW_COUNT + 1 ? GROUP_PREVIEW_COUNT : group.count;
                if (displayCount < group.count) {
                    items.add(buildShowAllItem(ALBUMS_ALPHA_ID + ":" + group.key, group, "albums"));
                }
                for (int i = 0; i < displayCount && (group.offset + i) < loop.length(); i++) {
                    JSONObject album = loop.getJSONObject(group.offset + i);
                    String id = album.optString("id", "");
                    String title = album.optString("album", "");
                    String artist = album.optString("artist", "");
                    int year = album.optInt("year", 0);
                    if (year > 0) {
                        title = title + " (" + year + ")";
                    }
                    Uri artUri = albumArtUri(album);
                    items.add(buildAlbumItemWithGroup(id, title, artist, artUri, group.key));
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums alphabetically grouped", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumsByYearGrouped() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("years");
            params.add("0");
            params.add("10000");
            params.add("hasAlbums:1");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("years_loop");
            if (null==loop) return items;

            for (int i = loop.length() - 1; i >= 0; i--) {
                JSONObject entry = loop.getJSONObject(i);
                int year = entry.optInt("year", 0);
                String yearKey = year > 0 ? String.valueOf(year) : "Unknown";
                items.add(buildBrowsableItem(ALBUMS_BY_YEAR_ID + ":" + yearKey, yearKey, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load years", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistGroupPage(String mediaId, String baseId, boolean albumArtistsOnly) {
        String suffix = mediaId.substring(baseId.length() + 1);
        int slashPos = suffix.indexOf('/');
        String letter = slashPos < 0 ? suffix : suffix.substring(0, slashPos);
        int pageOffset = 0;
        if (slashPos >= 0) {
            try {
                pageOffset = Integer.parseInt(suffix.substring(slashPos + 1));
            } catch (NumberFormatException e) {
                Utils.debug("Failed to parse page offset from: " + mediaId);
            }
        }

        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        List<GroupIndex> index = albumArtistsOnly ? albumArtistIndex : allArtistIndex;
        GroupIndex group = findGroup(index, letter);
        if (null==group) return items;

        int absoluteOffset = group.offset + pageOffset;
        int remaining = group.count - pageOffset;
        int fetchCount = Math.min(PAGE_SIZE, remaining);

        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add(String.valueOf(absoluteOffset));
            params.add(String.valueOf(fetchCount));
            if (albumArtistsOnly) params.add("role_id:5");
            params.add("tags:s");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("artists_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject artist = loop.getJSONObject(i);
                String id = artist.optString("id", "");
                String name = artist.optString("artist", "");
                Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + id + "/image_300x300_f");
                items.add(buildBrowsableItem("artist/" + id, name, null, artUri));
            }

            int nextPageOffset = pageOffset + PAGE_SIZE;
            if (nextPageOffset < group.count) {
                items.add(buildBrowsableItem(baseId + ":" + letter + "/" + nextPageOffset,
                        "► " + getLabel("MORE") + " (" + nextPageOffset + "/" + group.count + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load artist group page", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumsAlphaPage(String mediaId) {
        String suffix = mediaId.substring(ALBUMS_ALPHA_ID.length() + 1);
        int slashPos = suffix.indexOf('/');
        String letter = slashPos < 0 ? suffix : suffix.substring(0, slashPos);
        int pageOffset = 0;
        if (slashPos >= 0) {
            try {
                pageOffset = Integer.parseInt(suffix.substring(slashPos + 1));
            } catch (NumberFormatException e) {
                Utils.debug("Failed to parse page offset from: " + mediaId);
            }
        }

        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        GroupIndex group = findGroup(albumsAlphaIndex, letter);
        if (null==group) return items;

        int absoluteOffset = group.offset + pageOffset;
        int remaining = group.count - pageOffset;
        int fetchCount = Math.min(PAGE_SIZE, remaining);

        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add(String.valueOf(absoluteOffset));
            params.add(String.valueOf(fetchCount));
            params.add("sort:album");
            params.add("tags:ajlsy");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject album = loop.getJSONObject(i);
                String id = album.optString("id", "");
                String title = album.optString("album", "");
                String artist = album.optString("artist", "");
                int year = album.optInt("year", 0);
                if (year > 0) {
                    title = title + " (" + year + ")";
                }
                Uri artUri = albumArtUri(album);
                items.add(buildAlbumItem(id, title, artist, artUri));
            }

            int nextPageOffset = pageOffset + PAGE_SIZE;
            if (nextPageOffset < group.count) {
                items.add(buildBrowsableItem(ALBUMS_ALPHA_ID + ":" + letter + "/" + nextPageOffset,
                        "► " + getLabel("MORE") + " (" + nextPageOffset + "/" + group.count + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums alpha page", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumsByYearPage(String mediaId) {
        String suffix = mediaId.substring(ALBUMS_BY_YEAR_ID.length() + 1);
        int slashPos = suffix.indexOf('/');
        String yearKey = slashPos < 0 ? suffix : suffix.substring(0, slashPos);
        int pageOffset = 0;
        if (slashPos >= 0) {
            try {
                pageOffset = Integer.parseInt(suffix.substring(slashPos + 1));
            } catch (NumberFormatException e) {
                Utils.debug("Failed to parse page offset from: " + mediaId);
            }
        }

        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add(String.valueOf(pageOffset));
            params.add(String.valueOf(PAGE_SIZE));
            params.add("sort:album");
            params.add("year:" + ("Unknown".equals(yearKey) ? "0" : yearKey));
            params.add("tags:ajlsy");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject album = loop.getJSONObject(i);
                String id = album.optString("id", "");
                String title = album.optString("album", "");
                String artist = album.optString("artist", "");
                int year = album.optInt("year", 0);
                if (year > 0) {
                    title = title + " (" + year + ")";
                }
                Uri artUri = albumArtUri(album);
                items.add(buildAlbumItem(id, title, artist, artUri));
            }

            int total = result.optInt("count", 0);
            int nextPageOffset = pageOffset + PAGE_SIZE;
            if (nextPageOffset < total) {
                items.add(buildBrowsableItem(ALBUMS_BY_YEAR_ID + ":" + yearKey + "/" + nextPageOffset,
                        "► " + getLabel("MORE") + " (" + nextPageOffset + "/" + total + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums by year page", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadNewArtists(int offset) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add(String.valueOf(offset));
            params.add(String.valueOf(PAGE_SIZE));
            params.add("sort:new");
            params.add("tags:4s");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("artists_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject artist = loop.getJSONObject(i);
                String id = artist.optString("id", "");
                String name = artist.optString("artist", "");
                Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + id + "/image_300x300_f");
                items.add(buildBrowsableItem("artist/" + id, name, null, artUri));
            }

            int total = result.optInt("count", 0);
            int nextOffset = offset + PAGE_SIZE;
            if (nextOffset < total) {
                items.add(buildBrowsableItem(NEW_ARTISTS_ID + "/" + nextOffset,
                        "► " + getLabel("MORE") + " (" + nextOffset + "/" + total + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load new artists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbums(String sortParam, int offset) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add(String.valueOf(offset));
            params.add(String.valueOf(PAGE_SIZE));
            if (null!=sortParam) {
                params.add(sortParam);
            }
            params.add("tags:ajlsy");
            addLibraryParam(params);

            JSONObject resp = rpc.sendMessageSync("",
                    params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                items.add(buildAlbumItem(loop.getJSONObject(i)));
            }

            String baseId = "sort:new".equals(sortParam) ? ALBUMS_NEW_ID
                    : "sort:recentlyplayed".equals(sortParam) ? ALBUMS_RECENTLY_PLAYED_ID
                    : ALBUMS_RANDOM_ID;
            int total = result.optInt("count", 0);
            int nextOffset = offset + PAGE_SIZE;
            if (nextOffset < total) {
                items.add(buildBrowsableItem(baseId + "/" + nextOffset,
                        "► " + getLabel("MORE") + " (" + nextOffset + "/" + total + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums", e);
        }
        return items;
    }

    // An album from an "albums" query with tags ajlsy.
    private MediaBrowserCompat.MediaItem buildAlbumItem(JSONObject album) {
        String title = album.optString("album", "");
        int year = album.optInt("year", 0);
        if (year > 0) {
            title = title + " (" + year + ")";
        }
        return buildAlbumItem(album.optString("id", ""), title, album.optString("artist", ""), albumArtUri(album));
    }

    private static final class DailyList {
        final String key;
        final List<String> ids;

        DailyList(String key, List<String> ids) {
            this.key = key;
            this.ids = ids;
        }
    }

    /**
     * Rediscover Albums (all albums, played or not, without a play in the last N months, from LMS's per-track last
     * played time) or Unplayed Albums (no plays), in random order. Built once a day, like the order: unlike Random,
     * the list stays the same through the day (and app restarts), and its "More" pages follow that order.
     */
    private List<MediaBrowserCompat.MediaItem> loadShuffledAlbums(String listId, int offset) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            String day = String.format(Locale.US, "%tF", new Date());
            int months = rediscoverMonths();
            String key = listId + "|" + getLibraryId() + "|" + months + "|" + day;
            DailyList list = dailyLists.get(listId);
            if (null==list || !key.equals(list.key)) {
                List<String> source;
                if (ALBUMS_UNPLAYED_ID.equals(listId)) {
                    source = unplayedAlbumIds();
                } else {
                    Set<String> played = albumsPlayedSince(months);
                    source = null==played ? null : albumIds(null, BROWSE_LIMIT);
                    if (null!=source) {
                        source.removeAll(played);
                    }
                }
                if (null==source) return items;
                list = new DailyList(key, dailyOrder(source, day));
                dailyLists.put(listId, list);
                Utils.debug(listId + ": " + list.ids.size() + " albums");
            }
            List<String> ids = list.ids;
            if (offset >= ids.size()) return items;
            List<String> page = ids.subList(offset, Math.min(offset + REDISCOVER_PAGE_SIZE, ids.size()));
            List<String> params = new ArrayList<>(Arrays.asList("albums", "0", String.valueOf(page.size()),
                    "album_id:" + String.join(",", page), "tags:ajlsy"));
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            JSONObject result = null==resp ? null : resp.optJSONObject("result");
            JSONArray loop = null==result ? null : result.optJSONArray("albums_loop");
            if (null==loop) return items;
            // LMS returns them sorted by name: put them back in the shuffled order.
            Map<String, JSONObject> byId = new HashMap<>();
            for (int i = 0; i < loop.length(); i++) {
                JSONObject album = loop.getJSONObject(i);
                byId.put(album.optString("id", ""), album);
            }
            for (String id : page) {
                JSONObject album = byId.get(id);
                if (null!=album) {
                    items.add(buildAlbumItem(album));
                }
            }
            int nextOffset = offset + REDISCOVER_PAGE_SIZE;
            if (nextOffset < ids.size()) {
                items.add(buildBrowsableItem(listId + "/" + nextOffset,
                        "► " + getLabel("MORE") + " (" + nextOffset + "/" + ids.size() + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load " + listId, e);
        }
        return items;
    }

    private int rediscoverMonths() {
        try {
            return Integer.parseInt(prefs.getString(REDISCOVER_MONTHS_PREF_KEY, DEFAULT_REDISCOVER_MONTHS));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_REDISCOVER_MONTHS);
        }
    }

    /**
     * The order of a daily list: random, the same all day, and an album dropping out (or added) doesn't reorder the
     * others. Each ID is ranked by a hash of the day and the ID.
     */
    static List<String> dailyOrder(Collection<String> ids, String day) {
        long seed = day.hashCode();
        List<String> ordered = new ArrayList<>(ids);
        ordered.sort(Comparator.comparingLong((String id) -> mix64(seed * 1000003L + id.hashCode())).thenComparing(id -> id));
        return ordered;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /**
     * The active library's albums with a track played in the last months, or null. LMS keeps each track's last play
     * time (tracks_persistent.lastplayed) but has no query tag returning it, so it's used as a raw SQL condition;
     * play data is only joined when the condition mentions playcount.
     */
    private Set<String> albumsPlayedSince(int months) {
        Calendar since = Calendar.getInstance();
        since.add(Calendar.MONTH, -months);
        try {
            List<String> params = new ArrayList<>(Arrays.asList("titles", "0", String.valueOf(PLAY_COUNTS_TRACK_LIMIT),
                    "search:sql=tracks_persistent.playcount > 0 AND tracks_persistent.lastplayed > " + since.getTimeInMillis() / 1000,
                    "tags:e"));
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), INDEX_TIMEOUT_MS);
            JSONObject result = null==resp ? null : resp.optJSONObject("result");
            if (null==result) return null;
            JSONArray loop = result.optJSONArray("titles_loop");
            Set<String> albums = new HashSet<>();
            for (int i = 0; null!=loop && i < loop.length(); i++) {
                String album = loop.getJSONObject(i).optString("album_id", "");
                if (!album.isEmpty()) {
                    albums.add(album);
                }
            }
            Utils.debug("Played in the last " + months + " months: " + (null==loop ? 0 : loop.length()) + " tracks, " + albums.size() + " albums");
            return albums;
        } catch (Exception e) {
            Utils.error("Failed to fetch recently played albums", e);
            return null;
        }
    }

    // IDs of the active library's albums, in the given sort order; tags:x keeps the reply small (~120 bytes per album).
    private List<String> albumIds(String sortParam, int count) throws Exception {
        List<String> params = new ArrayList<>(Arrays.asList("albums", "0", String.valueOf(count), "tags:x"));
        if (null!=sortParam) {
            params.add(sortParam);
        }
        addLibraryParam(params);
        JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
        JSONObject result = null==resp ? null : resp.optJSONObject("result");
        JSONArray loop = null==result ? null : result.optJSONArray("albums_loop");
        List<String> ids = new ArrayList<>();
        for (int i = 0; null!=loop && i < loop.length(); i++) {
            ids.add(loop.getJSONObject(i).optString("id", ""));
        }
        return ids;
    }

    private boolean showUnplayed() {
        return prefs.getBoolean(SHOW_UNPLAYED_PREF_KEY, true);
    }

    /** Fetch the play counts in the background if they are missing or old, so Unplayed Albums opens quickly. */
    public void prefetchPlayCounts() {
        if (showUnplayed()) {
            new Thread(this::unplayedAlbumIds, "PlayCounts").start();
        }
    }

    // The active library's albums without plays, fetched at most once a day (or when the library changes), or null.
    // Has its own lock, not the browse executor: a slow fetch only holds up the list that needs it.
    private List<String> unplayedAlbumIds() {
        synchronized (playCountsLock) {
            String lib = getLibraryId();
            long now = SystemClock.elapsedRealtime();
            if (null!=unplayedAlbums && Objects.equals(lib, unplayedAlbums.libraryId) && now - unplayedAlbums.time < PLAY_COUNTS_MAX_AGE_MS) {
                return unplayedAlbums.ids;
            }
            try {
                List<String> params = new ArrayList<>(Arrays.asList("titles", "0", String.valueOf(PLAY_COUNTS_TRACK_LIMIT), "tags:Oe"));
                addLibraryParam(params);
                JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), INDEX_TIMEOUT_MS);
                JSONObject result = null==resp ? null : resp.optJSONObject("result");
                JSONArray loop = null==result ? null : result.optJSONArray("titles_loop");
                if (null!=loop) {
                    Map<String, Integer> plays = new LinkedHashMap<>();
                    for (int i = 0; i < loop.length(); i++) {
                        JSONObject track = loop.getJSONObject(i);
                        String album = track.optString("album_id", "");
                        if (!album.isEmpty()) {
                            plays.merge(album, track.optInt("playcount", 0), Integer::sum);
                        }
                    }
                    List<String> ids = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : plays.entrySet()) {
                        if (0==e.getValue()) {
                            ids.add(e.getKey());
                        }
                    }
                    Utils.debug("Play counts: " + loop.length() + " tracks, " + plays.size() + " albums, " + ids.size() + " unplayed");
                    unplayedAlbums = new UnplayedAlbums(lib, now, ids);
                }
            } catch (Exception e) {
                Utils.error("Failed to fetch play counts", e);
            }
            return null==unplayedAlbums ? null : unplayedAlbums.ids;
        }
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistAlbums(String artistId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            boolean groupReleases = shouldGroupByReleaseType();
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("artist_id:" + artistId);
            params.add(groupReleases ? "tags:ajlsywW" : "tags:ajlsy");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            if (groupReleases) {
                Map<String, List<MediaBrowserCompat.MediaItem>> groups = new LinkedHashMap<>();
                for (String type : RELEASE_TYPE_ORDER) {
                    groups.put(type, new ArrayList<>());
                }

                for (int i = 0; i < loop.length(); i++) {
                    JSONObject album = loop.getJSONObject(i);
                    String id = album.optString("id", "");
                    String title = album.optString("album", "");
                    int year = album.optInt("year", 0);
                    if (year > 0) {
                        title = title + " (" + year + ")";
                    }
                    Uri artUri = albumArtUri(album);

                    String releaseType = album.optString("release_type", "ALBUM").toUpperCase(Locale.ROOT);
                    boolean isCompilation = album.optInt("compilation", 0) == 1 && "ALBUM".equals(releaseType);
                    String group = isCompilation ? "COMPILATION" : releaseType;

                    if (!groups.containsKey(group)) {
                        groups.put(group, new ArrayList<>());
                    }
                    groups.get(group).add(buildAlbumItemWithGroup(id, title, null, artUri, releaseTypeHeader(group)));
                }

                for (String type : RELEASE_TYPE_ORDER) {
                    List<MediaBrowserCompat.MediaItem> groupItems = groups.get(type);
                    if (null!=groupItems && !groupItems.isEmpty()) {
                        items.addAll(groupItems);
                    }
                }
                for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : groups.entrySet()) {
                    if (Arrays.asList(RELEASE_TYPE_ORDER).contains(entry.getKey())) continue;
                    if (!entry.getValue().isEmpty()) {
                        items.addAll(entry.getValue());
                    }
                }
            } else {
                for (int i = 0; i < loop.length(); i++) {
                    JSONObject album = loop.getJSONObject(i);
                    String id = album.optString("id", "");
                    String title = album.optString("album", "");
                    int year = album.optInt("year", 0);
                    if (year > 0) {
                        title = title + " (" + year + ")";
                    }
                    Uri artUri = albumArtUri(album);
                    items.add(buildAlbumItem(id, title, null, artUri));
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to load artist albums", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumTracks(String albumId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("titles");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("album_id:" + albumId);
            params.add("tags:acdltN");
            params.add("sort:tracknum");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("titles_loop");
            if (null==loop) return items;

            // Tapping a track queues the whole album (exactly the tracks listed, in this order) and
            // starts playback at that track - so remember the list for playMediaId().
            List<String> trackIds = new ArrayList<>();
            if (loop.length() > 0) {
                items.add(buildPlayableItem(ALBUM_TRACK_PREFIX + albumId + "/0", context.getString(R.string.play_album), null, drawableUri(R.drawable.ic_play)));
                items.add(buildQueueAddItem("album/" + albumId));
            }
            for (int i = 0; i < loop.length(); i++) {
                JSONObject track = loop.getJSONObject(i);
                trackIds.add(track.optString("id", ""));
                String title = track.optString("title", "");
                String trackNum = track.optString("tracknum", "");
                if (!trackNum.isEmpty()) {
                    title = trackNum + ". " + title;
                }
                String coverId = track.optString("coverid", "");
                Uri artUri = coverId.isEmpty() ? null : resolveImageUri("/music/" + coverId + "/cover");
                items.add(buildPlayableItem(ALBUM_TRACK_PREFIX + albumId + "/" + i, title, track.optString("artist", ""), artUri));
            }
            lastAlbumTracks = new AlbumTracks(albumId, trackIds);
        } catch (Exception e) {
            Utils.error("Failed to load album tracks", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadFavorites() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"favorites", "items", "0", String.valueOf(BROWSE_LIMIT), "want_url:1"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("loop_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject item = loop.getJSONObject(i);
                items.add(buildFavoriteMediaItem(item));
            }
        } catch (Exception e) {
            Utils.error("Failed to load favorites", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadFavoriteFolder(String folderId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"favorites", "items", "0", String.valueOf(BROWSE_LIMIT), "item_id:" + folderId, "want_url:1"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("loop_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject item = loop.getJSONObject(i);
                items.add(buildFavoriteMediaItem(item));
            }
        } catch (Exception e) {
            Utils.error("Failed to load favorite folder", e);
        }
        return items;
    }

    private MediaBrowserCompat.MediaItem buildFavoriteMediaItem(JSONObject item) {
        String id = item.optString("id", "");
        String name = item.optString("name", "");
        String image = item.optString("image", item.optString("icon", item.optString("icon-id", "")));
        boolean hasItems = item.optInt("hasitems", 0) > 0;
        String url = item.optString("url", "");

        if (hasItems) {
            boolean isDefaultIcon = image.isEmpty() || image.startsWith("html/images/favorites");
            Uri iconUri = isDefaultIcon ? drawableUri(R.drawable.ic_folder_favorite) : resolveImageUri(image);
            return buildBrowsableItem("favorite_folder/" + id, name, iconUri);
        }

        if (url.contains("contributor.name")) {
            String artistId = lookupArtistIdByName(name);
            if (null!=artistId) {
                Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + artistId + "/image_300x300_f");
                return buildBrowsableItem("artist/" + artistId, name, artUri);
            }
        }

        if (url.contains("album.title")) {
            String albumId = lookupAlbumIdByName(name);
            if (null!=albumId) {
                return buildAlbumItem(albumId, name, null, resolveImageUri(image));
            }
        }

        return buildPlayableItem("favorite/" + id, name, null, resolveImageUri(image));
    }

    private String lookupArtistIdByName(String name) {
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"artists", "0", "1", "search:" + name, "tags:s"}, TIMEOUT_MS);
            if (null!=resp) {
                JSONObject result = resp.optJSONObject("result");
                if (null!=result) {
                    JSONArray loop = result.optJSONArray("artists_loop");
                    if (null!=loop && loop.length() > 0) {
                        return loop.getJSONObject(0).optString("id", null);
                    }
                }
            }
        } catch (Exception e) {
            Utils.debug("Failed to lookup artist: " + name);
        }
        return null;
    }

    private String lookupAlbumIdByName(String name) {
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"albums", "0", "1", "search:" + name, "tags:j"}, TIMEOUT_MS);
            if (null!=resp) {
                JSONObject result = resp.optJSONObject("result");
                if (null!=result) {
                    JSONArray loop = result.optJSONArray("albums_loop");
                    if (null!=loop && loop.length() > 0) {
                        return loop.getJSONObject(0).optString("id", null);
                    }
                }
            }
        } catch (Exception e) {
            Utils.debug("Failed to lookup album: " + name);
        }
        return null;
    }

    private List<MediaBrowserCompat.MediaItem> loadPlaylists() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"playlists", "0", String.valueOf(BROWSE_LIMIT), "tags:su"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("playlists_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject pl = loop.getJSONObject(i);
                String id = pl.optString("id", "");
                String name = pl.optString("playlist", "");
                items.add(buildBrowsablePlayableItem("playlist/" + id, name, null, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load playlists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadPlaylistTracks(String playlistId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"playlists", "tracks", "0", String.valueOf(BROWSE_LIMIT), "playlist_id:" + playlistId, "tags:adlN"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("playlisttracks_loop");
            if (null==loop) return items;

            items.add(buildPlayableItem("playlist/" + playlistId, "\u25B6 Play All", null, null));
            items.add(buildQueueAddItem("playlist/" + playlistId));
            for (int i = 0; i < loop.length(); i++) {
                JSONObject track = loop.getJSONObject(i);
                String id = track.optString("id", "");
                String title = track.optString("title", "");
                String artist = track.optString("artist", "");
                items.add(buildPlayableItem("track/" + id, title, artist, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load playlist tracks", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadPlayers() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"players", "0", "100"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("players_loop");
            if (null==loop) return items;

            String currentPlayer = MainActivity.activePlayer;
            String restricted = PlayerAvailability.restrictedPlayer(prefs);
            for (int i = 0; i < loop.length(); i++) {
                JSONObject player = loop.getJSONObject(i);
                String id = player.optString("playerid", "");
                String name = player.optString("name", "");
                if (!PlayerAvailability.isAllowed(restricted, id, name)) {
                    continue;
                }
                boolean isConnected = player.optInt("connected", 0) == 1;
                boolean isActive = id.equals(currentPlayer);
                if (isActive) {
                    MainActivity.activePlayerName = name;
                }
                String title = (isActive ? "\u2713 " : "") + name;
                String subtitle = isConnected ? "Connected" : "Disconnected";
                items.add(buildPlayableItem("player/" + id, title, subtitle, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load players", e);
        }
        return items;
    }

    // The other connected players with something in their queue, by name, showing what each is playing. Playable
    // items, so nothing is copied by Android Auto preloading the list.
    private List<MediaBrowserCompat.MediaItem> loadCopyQueueSources() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("", new String[]{"players", "0", "100"}, TIMEOUT_MS);
            JSONObject result = null==resp ? null : resp.optJSONObject("result");
            JSONArray loop = null==result ? null : result.optJSONArray("players_loop");
            List<JSONObject> players = new ArrayList<>();
            for (int i = 0; null!=loop && i < loop.length(); i++) {
                players.add(loop.getJSONObject(i));
            }
            players.sort((a, b) -> a.optString("name", "").compareToIgnoreCase(b.optString("name", "")));
            for (JSONObject player : players) {
                String id = player.optString("playerid", "");
                if (id.isEmpty() || id.equals(MainActivity.activePlayer) || 1!=player.optInt("connected", 0)) {
                    continue;
                }
                JSONObject status = rpc.sendMessageSync(id, new String[]{"status", "-", "1", "tags:au"}, TIMEOUT_MS);
                status = null==status ? null : status.optJSONObject("result");
                if (null==status || status.optInt("playlist_tracks", 0) <= 0) {
                    continue;
                }
                JSONArray tracks = status.optJSONArray("playlist_loop");
                JSONObject track = null==tracks || tracks.length() == 0 ? null : tracks.getJSONObject(0);
                JSONObject info = null==track ? null : libraryTrackInfo(track);
                if (null==info) {
                    info = track;
                }
                String subtitle = ("play".equals(status.optString("mode")) ? "\u25B6 " : "\u23F8 ") +
                        (null==info ? "" : info.optString("title", "") +
                                (info.optString("artist", "").isEmpty() ? "" : " \u00b7 " + info.optString("artist", "")));
                items.add(buildPlayableItem(COPY_QUEUE_PREFIX + id, player.optString("name", id), subtitle, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load players to copy the queue from", e);
        }
        if (items.isEmpty()) {
            items.add(buildBrowsableItem(COPY_QUEUE_ID + "/none", context.getString(R.string.copy_queue_none), null));
        }
        return items;
    }

    /**
     * The library track (id, title, artist) of a queue entry with tags u, or null if it isn't one. Syncing players
     * (e.g. the Material skin's queue transfer) turns cue sheet tracks into temporary "tmp://<file>#start-end"
     * entries without metadata and with negative IDs; the same URL with file:// finds the library track again.
     */
    private JSONObject libraryTrackInfo(JSONObject entry) throws Exception {
        String url = entry.optString("url", "");
        if (url.startsWith("tmp://")) {
            url = "file://" + url.substring(6);
        } else if (entry.optLong("id", 0) <= 0) {
            return null;
        }
        JSONObject resp = rpc.sendMessageSync("", new String[]{"songinfo", "0", "20", "url:" + url, "tags:at"}, TIMEOUT_MS);
        JSONObject result = null==resp ? null : resp.optJSONObject("result");
        JSONArray loop = null==result ? null : result.optJSONArray("songinfo_loop");
        if (null==loop) return null;
        // songinfo returns one field per entry
        JSONObject info = new JSONObject();
        for (int i = 0; i < loop.length(); i++) {
            JSONObject field = loop.getJSONObject(i);
            for (Iterator<String> it = field.keys(); it.hasNext(); ) {
                String key = it.next();
                info.put(key, field.get(key));
            }
        }
        return info.optLong("id", 0) > 0 ? info : null;
    }

    /**
     * Copy another player's queue, current track and position to the active player and play it there. Done track
     * by track rather than with the Material skin's queue transfer, whose sync/unsync leaves cue sheet tracks as
     * metadata-less "tmp://" copies; such entries in the source queue are mapped back to library tracks. Entries
     * that aren't library tracks (e.g. radio) are copied by URL.
     */
    private boolean copyQueue(String fromId) {
        String toId = MainActivity.activePlayer;
        if (Utils.isEmpty(toId) || toId.equals(fromId)) {
            return false;
        }
        try {
            JSONObject status = rpc.sendMessageSync(fromId, new String[]{"status", "0", String.valueOf(BROWSE_LIMIT), "tags:u"}, TIMEOUT_MS);
            status = null==status ? null : status.optJSONObject("result");
            JSONArray loop = null==status ? null : status.optJSONArray("playlist_loop");
            if (null==loop || loop.length() == 0) return false;
            int index = status.optInt("playlist_cur_index", 0);
            double time = status.optDouble("time", 0);
            // Each entry: a library track ID, or a URL
            List<String> trackIds = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            boolean allTracks = true;
            for (int i = 0; i < loop.length(); i++) {
                JSONObject entry = loop.getJSONObject(i);
                JSONObject info = libraryTrackInfo(entry);
                trackIds.add(null==info ? null : String.valueOf(info.optLong("id")));
                urls.add(entry.optString("url", ""));
                allTracks &= null!=info;
            }
            Utils.debug("Copying the queue of " + fromId + " to " + toId + ": " + loop.length() + " entries" +
                    (allTracks ? "" : ", not all library tracks") + ", at " + index + "/" + time);
            if (allTracks) {
                // Waited for, so the seek below comes after the load. No library filter: copy exactly that queue.
                rpc.sendMessageSync(toId, new String[]{"playlistcontrol", "cmd:load", "track_id:" + String.join(",", trackIds),
                        "play_index:" + index, "library_id:" + ALL_LIBRARIES_ID}, TIMEOUT_MS);
            } else {
                rpc.sendMessageSync(toId, new String[]{"playlist", "clear"}, TIMEOUT_MS);
                for (int i = 0; i < trackIds.size(); i++) {
                    rpc.sendMessageSync(toId, null!=trackIds.get(i)
                            ? new String[]{"playlistcontrol", "cmd:add", "track_id:" + trackIds.get(i), "library_id:" + ALL_LIBRARIES_ID}
                            : new String[]{"playlist", "add", urls.get(i)}, TIMEOUT_MS);
                }
                rpc.sendMessageSync(toId, new String[]{"playlist", "index", String.valueOf(index)}, TIMEOUT_MS);
            }
            if (time > 1) {
                // Seek once the track has started, or LMS ignores it.
                Thread.sleep(1000);
                rpc.sendMessageSync(toId, new String[]{"time", String.valueOf(time)}, TIMEOUT_MS);
            }
            return true;
        } catch (Exception e) {
            Utils.error("Failed to copy the queue of " + fromId, e);
            return false;
        }
    }

    private List<MediaBrowserCompat.MediaItem> loadLibraries() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"libraries"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("folder_loop");
            if (null==loop) return items;

            String currentLib = getLibraryId();
            boolean hasSelection = (null!=currentLib);

            items.add(buildPlayableItem("library/__ALL__",
                    (!hasSelection ? "\u2713 " : "") + getLabel("ALL_TRACKS"), null, null));

            for (int i = 0; i < loop.length(); i++) {
                JSONObject lib = loop.getJSONObject(i);
                String id = lib.optString("id", "");
                String name = lib.optString("name", "");
                boolean isActive = id.equals(currentLib);
                String title = (isActive ? "\u2713 " : "") + name;
                items.add(buildPlayableItem("library/" + id, title, null, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load libraries", e);
        }
        return items;
    }

    /**
     * Make sure the active library is one LMS knows about, else select "All": a library that was since
     * removed or rebuilt would show none as selected while still filtering the lists. Returns true if the
     * selection was changed.
     */
    public boolean ensureLibrarySelected() {
        String currentLib = getLibraryId();
        if (null==currentLib) {
            return false;
        }
        JSONArray libraries = fetchLibraries();
        if (null==libraries) {
            return false;
        }
        if (null!=libraryName(libraries, currentLib)) {
            return false;
        }
        Utils.debug("Unknown library " + currentLib + ", selecting all");
        switchLibrary("__ALL__");
        return true;
    }

    private JSONArray fetchLibraries() {
        return fetchLibraries(rpc);
    }

    /** LMS's libraries, or null if LMS couldn't be asked. */
    public static JSONArray fetchLibraries(JsonRpc rpc) {
        try {
            JSONObject resp = rpc.sendMessageSync("", new String[]{"libraries"}, TIMEOUT_MS);
            JSONObject result = null==resp ? null : resp.optJSONObject("result");
            if (null==result) return null;
            JSONArray loop = result.optJSONArray("folder_loop");
            return null==loop ? new JSONArray() : loop;
        } catch (Exception e) {
            Utils.debug("Failed to load libraries: " + e.getMessage());
            return null;
        }
    }

    public static boolean isKnownLibrary(JSONArray libraries, String id) {
        return null!=libraryName(libraries, id);
    }

    /**
     * The library ID to store in the shared "active_library" pref: null for All (and for anything that isn't
     * a plain LMS library ID).
     */
    public static String normalizeLibraryId(String id) {
        if (null==id) return null;
        id = id.trim();
        if (id.isEmpty() || ALL_LIBRARIES_ID.equals(id) || "0".equals(id) || "__ALL__".equals(id) ||
                !id.matches("[A-Za-z0-9_.-]{1,64}")) {
            return null;
        }
        return id;
    }

    private static String libraryName(JSONArray libraries, String id) {
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.optJSONObject(i);
            if (null!=lib && id.equals(lib.optString("id", ""))) {
                return lib.optString("name", id);
            }
        }
        return null;
    }

    public List<MediaBrowserCompat.MediaItem> search(String query) {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            String artistsGroup = getLabel("ARTISTS");
            String albumsGroup = getLabel("ALBUMS");
            String songsGroup = getLabel("SONGS");

            List<String> artistParams = new ArrayList<>();
            artistParams.add("artists");
            artistParams.add("0");
            artistParams.add(String.valueOf(SEARCH_LIMIT));
            artistParams.add("search:" + query);
            artistParams.add("tags:s");
            addLibraryParam(artistParams);
            JSONObject artistResp = rpc.sendMessageSync("", artistParams.toArray(new String[0]), TIMEOUT_MS);
            if (null!=artistResp) {
                JSONObject result = artistResp.optJSONObject("result");
                if (null!=result) {
                    JSONArray loop = result.optJSONArray("artists_loop");
                    if (null!=loop) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject artist = loop.getJSONObject(i);
                            String id = artist.optString("id", "");
                            String name = artist.optString("artist", "");
                            Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + id + "/image_300x300_f");
                            items.add(buildBrowsableItemWithGroup("artist/" + id, name, artUri, artistsGroup));
                        }
                    }
                }
            }

            List<String> albumParams = new ArrayList<>();
            albumParams.add("albums");
            albumParams.add("0");
            albumParams.add(String.valueOf(SEARCH_LIMIT));
            albumParams.add("search:" + query);
            albumParams.add("tags:ajlsy");
            addLibraryParam(albumParams);
            JSONObject albumResp = rpc.sendMessageSync("", albumParams.toArray(new String[0]), TIMEOUT_MS);
            if (null!=albumResp) {
                JSONObject result = albumResp.optJSONObject("result");
                if (null!=result) {
                    JSONArray loop = result.optJSONArray("albums_loop");
                    if (null!=loop) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject album = loop.getJSONObject(i);
                            String id = album.optString("id", "");
                            String title = album.optString("album", "");
                            String artist = album.optString("artist", "");
                            int year = album.optInt("year", 0);
                            if (year > 0) {
                                title = title + " (" + year + ")";
                            }
                            Uri artUri = albumArtUri(album);
                            items.add(buildAlbumItemWithGroup(id, title, artist, artUri, albumsGroup));
                        }
                    }
                }
            }

            List<String> trackParams = new ArrayList<>();
            trackParams.add("titles");
            trackParams.add("0");
            trackParams.add(String.valueOf(SEARCH_LIMIT));
            trackParams.add("search:" + query);
            trackParams.add("tags:adlsy");
            addLibraryParam(trackParams);
            JSONObject trackResp = rpc.sendMessageSync("", trackParams.toArray(new String[0]), TIMEOUT_MS);
            if (null!=trackResp) {
                JSONObject result = trackResp.optJSONObject("result");
                if (null!=result) {
                    JSONArray loop = result.optJSONArray("titles_loop");
                    if (null!=loop) {
                        for (int i = 0; i < loop.length(); i++) {
                            JSONObject track = loop.getJSONObject(i);
                            String id = track.optString("id", "");
                            String title = track.optString("title", "");
                            String artist = track.optString("artist", "");
                            String albumName = track.optString("album", "");
                            int year = track.optInt("year", 0);
                            String coverId = track.optString("coverid", track.optString("artwork_track_id", id));
                            Uri artUri = resolveImageUri("/music/" + coverId + "/cover");
                            StringBuilder subtitle = new StringBuilder();
                            if (!artist.isEmpty()) {
                                subtitle.append(artist);
                            }
                            if (!albumName.isEmpty()) {
                                if (subtitle.length() > 0) subtitle.append(" \u2014 ");
                                subtitle.append(albumName);
                                if (year > 0) subtitle.append(" (").append(year).append(")");
                            }
                            items.add(buildPlayableItemWithGroup("track/" + id, title, subtitle.length() > 0 ? subtitle.toString() : null, artUri, songsGroup));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to search", e);
        }
        return items;
    }

    public boolean playMediaId(String mediaId) {
        if (mediaId.startsWith(ALBUM_TRACK_PREFIX)) {
            String[] parts = mediaId.substring(ALBUM_TRACK_PREFIX.length()).split("/");
            if (parts.length != 2) {
                return false;
            }
            AlbumTracks tracks = lastAlbumTracks;
            if (null!=tracks && tracks.albumId.equals(parts[0])) {
                sendLoadCommand("track_id:" + String.join(",", tracks.trackIds), "play_index:" + parts[1]);
            } else {
                sendLoadCommand("album_id:" + parts[0], "play_index:" + parts[1]);
            }
            return true;
        } else if (mediaId.startsWith("album/")) {
            sendLoadCommand("album_id:" + mediaId.substring(6));
            return true;
        } else if (mediaId.startsWith(COPY_QUEUE_PREFIX)) {
            return copyQueue(mediaId.substring(COPY_QUEUE_PREFIX.length()));
        } else if (mediaId.startsWith("track/")) {
            sendLoadCommand("track_id:" + mediaId.substring(6));
            return true;
        } else if (mediaId.startsWith("playlist/")) {
            sendLoadCommand("playlist_id:" + mediaId.substring(9));
            return true;
        } else if (mediaId.startsWith("favorite/")) {
            sendPlayCommand(new String[]{"favorites", "playlist", "play", "item_id:" + mediaId.substring(9)});
            return true;
        }
        return false;
    }

    public static boolean isQueueAdd(String mediaId) {
        return mediaId.startsWith(QUEUE_ADD_PREFIX);
    }

    /**
     * Run a tapped "Add to queue" row: add the album or playlist to the end of the active player's queue. If the
     * queue was empty, playback is started: returns true then. Called on the browse executor.
     */
    public boolean addToQueue(String action) {
        String mediaId = action.substring(QUEUE_ADD_PREFIX.length());
        String playerId = MainActivity.activePlayer;
        String item = null;
        if (mediaId.startsWith("album/")) {
            String albumId = mediaId.substring(6);
            AlbumTracks tracks = lastAlbumTracks;
            // Same tracks, in the same order, as the track list shows.
            item = null!=tracks && tracks.albumId.equals(albumId) ? "track_id:" + String.join(",", tracks.trackIds) : "album_id:" + albumId;
        } else if (mediaId.startsWith("playlist/")) {
            item = "playlist_id:" + mediaId.substring(9);
        }
        if (Utils.isEmpty(playerId) || null==item) {
            return false;
        }
        try {
            JSONObject resp = rpc.sendMessageSync(playerId, new String[]{"status", "-", "1", "tags:"}, TIMEOUT_MS);
            JSONObject result = null==resp ? null : resp.optJSONObject("result");
            if (null==result) return false;
            boolean wasEmpty = 0==result.optInt("playlist_tracks", 0);
            String lib = getLibraryId();
            rpc.sendMessageSync(playerId, new String[]{"playlistcontrol", "cmd:add", item,
                    "library_id:" + (null!=lib ? lib : ALL_LIBRARIES_ID)}, TIMEOUT_MS);
            Utils.debug("Added " + mediaId + " to the " + (wasEmpty ? "empty " : "") + "queue");
            if (wasEmpty) {
                rpc.sendMessageSync(playerId, new String[]{"playlist", "index", "0"}, TIMEOUT_MS);
                return true;
            }
        } catch (Exception e) {
            Utils.error("Failed to add " + mediaId + " to the queue", e);
        }
        return false;
    }

    /**
     * Replace the queue with the given items. Always passes an explicit library: without one, LMS filters
     * the tracks by the *player's* library view (or the server default), which can differ from the list
     * shown in Android Auto - e.g. a Ratings Light "Rated Tracks" view would queue only an album's rated
     * tracks. "-1" means the whole library.
     */
    private void sendLoadCommand(String... items) {
        List<String> params = new ArrayList<>(Arrays.asList("playlistcontrol", "cmd:load"));
        params.addAll(Arrays.asList(items));
        String lib = getLibraryId();
        params.add("library_id:" + (null!=lib ? lib : ALL_LIBRARIES_ID));
        sendPlayCommand(params.toArray(new String[0]));
    }

    private void sendPlayCommand(String[] command) {
        String playerId = MainActivity.activePlayer;
        if (null!=playerId && !playerId.isEmpty()) {
            rpc.sendMessage(playerId, command);
        }
    }

    /**
     * Select the player or library of a tapped "player/" or "library/" item; returns false for other items.
     * These are playable items rather than browsable ones because Android Auto preloads the children of
     * browsable items (several levels deep) - which selected whichever entry it happened to load last.
     * A playable item is only ever "played" by an explicit tap.
     */
    public boolean selectMediaId(String mediaId) {
        if (mediaId.startsWith("player/")) {
            switchPlayer(mediaId.substring(7));
            return true;
        } else if (mediaId.startsWith("library/")) {
            switchLibrary(mediaId.substring(8));
            return true;
        }
        return false;
    }

    private void switchPlayer(String playerId) {
        String restricted = PlayerAvailability.restrictedPlayer(prefs);
        if (null!=restricted && !restricted.equals(playerId)) {
            // Restricted by ID or name - only switch if this ID belongs to the allowed player.
            try {
                JSONObject resp = rpc.sendMessageSync("", new String[]{"players", "0", "100"}, TIMEOUT_MS);
                JSONObject result = null==resp ? null : resp.optJSONObject("result");
                boolean allowed = false;
                for (PlayerAvailability.Player player : PlayerAvailability.parse(null==result ? null : result.optJSONArray("players_loop"))) {
                    if (player.id.equals(playerId) && PlayerAvailability.isAllowed(restricted, player.id, player.name)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    Utils.debug("Ignoring switch to non-default player " + playerId);
                    return;
                }
            } catch (Exception e) {
                Utils.debug("Failed to verify player " + playerId);
                return;
            }
        }
        MainActivity.activePlayer = playerId;
        prefs.edit().putString(MainActivity.CURRENT_PLAYER_ID_KEY, playerId).apply();
    }

    private void switchLibrary(String libraryId) {
        Utils.debug("Switch library to " + libraryId);
        if ("__ALL__".equals(libraryId)) {
            prefs.edit().remove(ACTIVE_LIBRARY_PREF_KEY).apply();
        } else {
            prefs.edit().putString(ACTIVE_LIBRARY_PREF_KEY, libraryId).apply();
        }
        clearLibraryCaches();
    }

    /** Drop what was loaded for the previous library. Call on the browse thread. */
    public void clearLibraryCaches() {
        albumArtistIndex = null;
        allArtistIndex = null;
        albumsAlphaIndex = null;
        lastAlbumTracks = null;
        dailyLists.clear(); // rebuilt for the new library
    }

    private String normalizeTextkey(String textkey) {
        if (null==textkey || textkey.isEmpty()) return "#";
        String nfd = Normalizer.normalize(textkey, Normalizer.Form.NFD);
        char c = Character.toUpperCase(nfd.charAt(0));
        if (c >= '0' && c <= '9') return "123";
        if (c >= 'A' && c <= 'Z') return String.valueOf(c);
        return "#";
    }

    private List<GroupIndex> buildLetterIndexFromLoop(JSONArray loop) {
        List<GroupIndex> index = new ArrayList<>();
        try {
            LinkedHashMap<String, int[]> groups = new LinkedHashMap<>();
            for (int i = 0; i < loop.length(); i++) {
                JSONObject item = loop.getJSONObject(i);
                String textkey = item.optString("textkey", "");
                String key = normalizeTextkey(textkey);
                if (!groups.containsKey(key)) {
                    groups.put(key, new int[]{i, 0});
                }
                groups.get(key)[1]++;
            }

            if (groups.containsKey("123")) {
                int[] v = groups.get("123");
                index.add(new GroupIndex("123", v[0], v[1]));
            }
            for (char c = 'A'; c <= 'Z'; c++) {
                String key = String.valueOf(c);
                if (groups.containsKey(key)) {
                    int[] v = groups.get(key);
                    index.add(new GroupIndex(key, v[0], v[1]));
                }
            }
            if (groups.containsKey("#")) {
                int[] v = groups.get("#");
                index.add(new GroupIndex("#", v[0], v[1]));
            }
        } catch (Exception e) {
            Utils.error("Failed to build letter index", e);
        }
        return index;
    }

    private MediaBrowserCompat.MediaItem buildLettersItem(String listId) {
        return buildBrowsableItem(LETTERS_PREFIX + listId, "Jump to letter", drawableUri(R.drawable.ic_letters));
    }

    /** The letters of an A-Z list, each opening that letter's full list. */
    private List<MediaBrowserCompat.MediaItem> loadLetters(String listId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        String what = ALBUMS_ALPHA_ID.equals(listId) ? "albums" : "artists";
        List<GroupIndex> index = letterIndex(listId);
        if (null==index) {
            // Index dropped (e.g. library changed) - rebuild it by loading the list.
            if (ALBUMS_ALPHA_ID.equals(listId)) {
                loadAlbumsAlphaGrouped();
            } else if (ALBUM_ARTISTS_ID.equals(listId) || ALL_ARTISTS_ID.equals(listId)) {
                loadArtistsGrouped(listId, ALBUM_ARTISTS_ID.equals(listId));
            }
            index = letterIndex(listId);
        }
        if (null==index) return items;
        for (GroupIndex group : index) {
            items.add(buildBrowsableItem(listId + ":" + group.key, group.key, group.count + " " + what, null));
        }
        return items;
    }

    private List<GroupIndex> letterIndex(String listId) {
        if (ALBUMS_ALPHA_ID.equals(listId)) return albumsAlphaIndex;
        if (ALBUM_ARTISTS_ID.equals(listId)) return albumArtistIndex;
        if (ALL_ARTISTS_ID.equals(listId)) return allArtistIndex;
        return null;
    }

    private MediaBrowserCompat.MediaItem buildShowAllItem(String mediaId, GroupIndex group, String what) {
        return buildBrowsableItemWithGroup(mediaId, "Show all " + group.count + " " + what, null,
                drawableUri(R.drawable.ic_show_all), group.key);
    }

    private GroupIndex findGroup(List<GroupIndex> index, String key) {
        if (null==index) return null;
        for (GroupIndex g : index) {
            if (g.key.equals(key)) return g;
        }
        return null;
    }

    private Uri drawableUri(int resId) {
        return Uri.parse("android.resource://" + packageName + "/" + resId);
    }

    private Uri albumArtUri(JSONObject album) {
        // Albums without artwork have no artwork_track_id; the album ID is not a track ID, so don't fall back to it.
        String artworkId = album.optString("artwork_track_id", "");
        return artworkId.isEmpty() ? null : resolveImageUri("/music/" + artworkId + "/cover");
    }

    // Android Auto only loads artwork from content:// URIs, so remote images go via ArtworkProvider.
    private Uri resolveImageUri(String path) {
        if (null==path || path.isEmpty()) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return ArtworkProvider.wrap(context, path);
        }
        String serverUrl = rpc.getServerUrl();
        if (null==serverUrl) return null;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.startsWith("/imageproxy/")) {
            String encoded = path.substring("/imageproxy/".length());
            int imgSuffix = encoded.lastIndexOf("/image");
            if (imgSuffix > 0) {
                encoded = encoded.substring(0, imgSuffix);
            }
            String decoded = Uri.decode(encoded);
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return ArtworkProvider.wrap(context, decoded);
            }
        }
        if (path.matches("/music/[^/]+/cover")) {
            path += LIST_IMAGE_SIZE;
        }
        return ArtworkProvider.wrap(context, serverUrl + path);
    }

    private MediaBrowserCompat.MediaItem buildBrowsableItem(String mediaId, String title, Uri iconUri) {
        return buildBrowsableItem(mediaId, title, null, iconUri);
    }

    private MediaBrowserCompat.MediaItem buildBrowsableItem(String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (null!=subtitle) {
            desc.setSubtitle(subtitle);
        }
        if (null!=iconUri) {
            desc.setIconUri(iconUri);
        }
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem buildBrowsablePlayableItem(String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (null!=subtitle) {
            desc.setSubtitle(subtitle);
        }
        if (null!=iconUri) {
            desc.setIconUri(iconUri);
        }
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    // Depending on the setting, tapping an album either opens its track list or plays it straight away.
    private boolean albumOpensTrackList() {
        return !ALBUM_CLICK_PLAY.equals(prefs.getString(ALBUM_CLICK_PREF_KEY, ALBUM_CLICK_TRACKS));
    }

    private MediaBrowserCompat.MediaItem buildAlbumItem(String albumId, String title, String subtitle, Uri iconUri) {
        return albumOpensTrackList()
                ? buildBrowsableItem("album/" + albumId, title, subtitle, iconUri)
                : buildPlayableItem("album/" + albumId, title, subtitle, iconUri);
    }

    private MediaBrowserCompat.MediaItem buildAlbumItemWithGroup(String albumId, String title, String subtitle, Uri iconUri, String groupTitle) {
        return albumOpensTrackList()
                ? buildBrowsableItemWithGroup("album/" + albumId, title, subtitle, iconUri, groupTitle)
                : buildPlayableItemWithGroup("album/" + albumId, title, subtitle, iconUri, groupTitle);
    }

    private MediaBrowserCompat.MediaItem buildQueueAddItem(String mediaId) {
        return buildPlayableItem(QUEUE_ADD_PREFIX + mediaId, context.getString(R.string.add_to_queue), null, drawableUri(R.drawable.ic_queue_add));
    }

    private MediaBrowserCompat.MediaItem buildPlayableItem(String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (null!=subtitle) {
            desc.setSubtitle(subtitle);
        }
        if (null!=iconUri) {
            desc.setIconUri(iconUri);
        }
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private MediaBrowserCompat.MediaItem buildBrowsableItemWithGroup(String mediaId, String title, Uri iconUri, String groupTitle) {
        return buildBrowsableItemWithGroup(mediaId, title, null, iconUri, groupTitle);
    }

    private MediaBrowserCompat.MediaItem buildBrowsableItemWithGroup(String mediaId, String title, String subtitle, Uri iconUri, String groupTitle) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (null!=subtitle) {
            desc.setSubtitle(subtitle);
        }
        if (null!=iconUri) {
            desc.setIconUri(iconUri);
        }
        Bundle extras = new Bundle();
        extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle);
        desc.setExtras(extras);
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem buildPlayableItemWithGroup(String mediaId, String title, String subtitle, Uri iconUri, String groupTitle) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (null!=subtitle) {
            desc.setSubtitle(subtitle);
        }
        if (null!=iconUri) {
            desc.setIconUri(iconUri);
        }
        Bundle extras = new Bundle();
        extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle);
        desc.setExtras(extras);
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

}
