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
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.media.utils.MediaConstants;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class LmsBrowseHelper {
    public static final String ROOT_ID = "__ROOT__";
    public static final String ARTISTS_GROUP_ID = "__ARTISTS_GROUP__";
    public static final String RELEASES_GROUP_ID = "__RELEASES_GROUP__";
    public static final String ALBUM_ARTISTS_ID = "__ALBUM_ARTISTS__";
    public static final String ALL_ARTISTS_ID = "__ALL_ARTISTS__";
    public static final String NEW_ARTISTS_ID = "__NEW_ARTISTS__";
    public static final String ALL_RELEASES_ID = "__ALL_RELEASES__";
    public static final String ALBUMS_NEW_ID = "__ALBUMS_NEW__";
    public static final String ALBUMS_RANDOM_ID = "__ALBUMS_RANDOM__";
    public static final String FAVORITES_ID = "__FAVORITES__";
    public static final String PLAYLISTS_ID = "__PLAYLISTS__";
    public static final String PLAYERS_ID = "__PLAYERS__";

    private static final int BROWSE_LIMIT = 100;
    private static final int TIMEOUT_MS = 10000;

    private static final String LABEL_TOKENS = "BROWSE_BY_ARTIST,BROWSE_BY_ALBUMARTIST,BROWSE_BY_ALL_ARTISTS,BROWSE_BY_ALBUM,BROWSE_NEW_MUSIC,FAVORITES,SAVED_PLAYLISTS,PLAYERS,PLUGIN_MATERIAL_SKIN_NEW_ARTISTS,PLUGIN_MATERIAL_SKIN_RANDOM_MIX,ARTISTS,ALBUMS,SONGS";

    private final JsonRpc rpc;
    private final SharedPreferences prefs;
    private final String packageName;
    private Boolean showYear = null;
    private Map<String, String> labels = null;

    public LmsBrowseHelper(Context context) {
        rpc = new JsonRpc(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        packageName = context.getPackageName();
    }

    private boolean shouldShowYear() {
        if (null==showYear) {
            try {
                JSONObject resp = rpc.sendMessageSync("", new String[]{"pref", "showYear", "?"}, TIMEOUT_MS);
                if (null!=resp) {
                    JSONObject result = resp.optJSONObject("result");
                    if (null!=result) {
                        showYear = "1".equals(result.optString("_p2", "0"));
                    }
                }
            } catch (Exception e) {
                Utils.debug("Failed to query showYear pref");
            }
            if (null==showYear) {
                showYear = false;
            }
        }
        return showYear;
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
        labels.put("ARTISTS", "Artists");
        labels.put("ALBUMS", "Albums");
        labels.put("SONGS", "Songs");
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
        return MainActivity.activeLibrary;
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
        } else if (ALBUM_ARTISTS_ID.equals(parentMediaId)) {
            return loadAlbumArtists();
        } else if (ALL_ARTISTS_ID.equals(parentMediaId)) {
            return loadAllArtists();
        } else if (NEW_ARTISTS_ID.equals(parentMediaId)) {
            return loadNewArtists();
        } else if (ALL_RELEASES_ID.equals(parentMediaId)) {
            return loadAlbums(null);
        } else if (ALBUMS_NEW_ID.equals(parentMediaId)) {
            return loadAlbums("sort:new");
        } else if (ALBUMS_RANDOM_ID.equals(parentMediaId)) {
            return loadAlbums("sort:random");
        } else if (FAVORITES_ID.equals(parentMediaId)) {
            return loadFavorites();
        } else if (PLAYLISTS_ID.equals(parentMediaId)) {
            return loadPlaylists();
        } else if (PLAYERS_ID.equals(parentMediaId)) {
            return loadPlayers();
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

    private List<MediaBrowserCompat.MediaItem> loadRoot() {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        items.add(buildBrowsableItem(ARTISTS_GROUP_ID, getLabel("BROWSE_BY_ARTIST"), drawableUri(R.drawable.ic_artist)));
        items.add(buildBrowsableItem(RELEASES_GROUP_ID, getLabel("BROWSE_BY_ALBUM"), drawableUri(R.drawable.ic_release)));
        items.add(buildBrowsableItem(FAVORITES_ID, getLabel("FAVORITES"), drawableUri(R.drawable.ic_favorite)));
        items.add(buildBrowsableItem(PLAYLISTS_ID, getLabel("SAVED_PLAYLISTS"), drawableUri(R.drawable.ic_playlist)));
        items.add(buildBrowsableItem(PLAYERS_ID, getLabel("PLAYERS"), drawableUri(R.drawable.ic_speaker)));
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
        items.add(buildBrowsableItem(ALL_RELEASES_ID, getLabel("BROWSE_BY_ALBUM"), drawableUri(R.drawable.ic_release)));
        items.add(buildBrowsableItem(ALBUMS_NEW_ID, getLabel("BROWSE_NEW_MUSIC"), drawableUri(R.drawable.ic_new_releases)));
        items.add(buildBrowsableItem(ALBUMS_RANDOM_ID, getLabel("RANDOM_ALBUMS"), drawableUri(R.drawable.ic_dice_release)));
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumArtists() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("role_id:5");
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
                items.add(buildBrowsableItem("artist/" + id, name, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load album artists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAllArtists() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
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
                items.add(buildBrowsableItem("artist/" + id, name, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load artists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadNewArtists() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("artists");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("sort:new");
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
                items.add(buildBrowsableItem("artist/" + id, name, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load new artists", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbums(String sortParam) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
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
                JSONObject album = loop.getJSONObject(i);
                String id = album.optString("id", "");
                String title = album.optString("album", "");
                String artist = album.optString("artist", "");
                int year = album.optInt("year", 0);
                if (shouldShowYear() && year > 0) {
                    title = title + " (" + year + ")";
                }
                String artworkId = album.optString("artwork_track_id", album.optString("id", ""));
                Uri artUri = resolveImageUri("/music/" + artworkId + "/cover");
                items.add(buildPlayableItem("album/" + id, title, artist, artUri));
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistAlbums(String artistId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("albums");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("artist_id:" + artistId);
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
                int year = album.optInt("year", 0);
                if (shouldShowYear() && year > 0) {
                    title = title + " (" + year + ")";
                }
                String artworkId = album.optString("artwork_track_id", album.optString("id", ""));
                Uri artUri = resolveImageUri("/music/" + artworkId + "/cover");
                items.add(buildPlayableItem("album/" + id, title, null, artUri));
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
            params.add("tags:adlN");
            params.add("sort:tracknum");
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("titles_loop");
            if (null==loop) return items;

            items.add(buildPlayableItem("album/" + albumId, "\u25B6 Play All", null, null));
            for (int i = 0; i < loop.length(); i++) {
                JSONObject track = loop.getJSONObject(i);
                String id = track.optString("id", "");
                String title = track.optString("title", "");
                String artist = track.optString("artist", "");
                items.add(buildPlayableItem("track/" + id, title, artist, null));
            }
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
                String id = item.optString("id", "");
                String name = item.optString("name", "");
                String image = item.optString("image", item.optString("icon", ""));
                boolean hasItems = item.optInt("hasitems", 0) > 0;

                if (hasItems) {
                    items.add(buildBrowsableItem("favorite_folder/" + id, name, resolveImageUri(image)));
                } else {
                    items.add(buildPlayableItem("favorite/" + id, name, null, resolveImageUri(image)));
                }
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
                String id = item.optString("id", "");
                String name = item.optString("name", "");
                String image = item.optString("image", item.optString("icon", ""));
                boolean hasItems = item.optInt("hasitems", 0) > 0;

                if (hasItems) {
                    items.add(buildBrowsableItem("favorite_folder/" + id, name, resolveImageUri(image)));
                } else {
                    items.add(buildPlayableItem("favorite/" + id, name, null, resolveImageUri(image)));
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to load favorite folder", e);
        }
        return items;
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
                    new String[]{"players", "0", "10"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("players_loop");
            if (null==loop) return items;

            String currentPlayer = MainActivity.activePlayer;
            for (int i = 0; i < loop.length(); i++) {
                JSONObject player = loop.getJSONObject(i);
                String id = player.optString("playerid", "");
                String name = player.optString("name", "");
                boolean isConnected = player.optInt("connected", 0) == 1;
                boolean isActive = id.equals(currentPlayer);
                String title = (isActive ? "\u2713 " : "") + name;
                String subtitle = isConnected ? "Connected" : "Disconnected";
                items.add(buildPlayableItem("player/" + id, title, subtitle, null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load players", e);
        }
        return items;
    }

    public List<MediaBrowserCompat.MediaItem> search(String query) {
        ensureLabelsLoaded();
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            List<String> params = new ArrayList<>();
            params.add("search");
            params.add("0");
            params.add(String.valueOf(BROWSE_LIMIT));
            params.add("term:" + query);
            addLibraryParam(params);
            JSONObject resp = rpc.sendMessageSync("", params.toArray(new String[0]), TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;

            String artistsGroup = getLabel("ARTISTS");
            String albumsGroup = getLabel("ALBUMS");
            String songsGroup = getLabel("SONGS");

            JSONArray artists = result.optJSONArray("contributors_loop");
            if (null!=artists) {
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.getJSONObject(i);
                    String id = artist.optString("id", "");
                    String name = artist.optString("contributor", "");
                    Uri artUri = resolveImageUri("/imageproxy/mai/artist/" + id + "/image_300x300_f");
                    items.add(buildBrowsableItemWithGroup("artist/" + id, name, artUri, artistsGroup));
                }
            }

            JSONArray albums = result.optJSONArray("albums_loop");
            if (null!=albums) {
                for (int i = 0; i < albums.length(); i++) {
                    JSONObject album = albums.getJSONObject(i);
                    String id = album.optString("id", "");
                    String title = album.optString("album", "");
                    String artist = album.optString("artist", "");
                    int year = album.optInt("year", 0);
                    if (year > 0) {
                        title = title + " (" + year + ")";
                    }
                    String artworkId = album.optString("artwork_track_id", album.optString("id", ""));
                    Uri artUri = resolveImageUri("/music/" + artworkId + "/cover");
                    items.add(buildPlayableItemWithGroup("album/" + id, title, artist, artUri, albumsGroup));
                }
            }

            JSONArray tracks = result.optJSONArray("tracks_loop");
            if (null!=tracks) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject track = tracks.getJSONObject(i);
                    String id = track.optString("id", "");
                    String title = track.optString("track", "");
                    String artist = track.optString("artist", "");
                    String albumName = track.optString("album", "");
                    int year = track.optInt("year", 0);
                    String coverId = track.optString("coverid", track.optString("artwork_track_id", ""));
                    Uri artUri = !coverId.isEmpty() ? resolveImageUri("/music/" + coverId + "/cover") : null;
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
        } catch (Exception e) {
            Utils.error("Failed to search", e);
        }
        return items;
    }

    public boolean playMediaId(String mediaId) {
        if (mediaId.startsWith("player/")) {
            switchPlayer(mediaId.substring(7));
            return true;
        } else if (mediaId.startsWith("album/")) {
            sendPlayCommand(new String[]{"playlistcontrol", "cmd:load", "album_id:" + mediaId.substring(6)});
            return true;
        } else if (mediaId.startsWith("track/")) {
            sendPlayCommand(new String[]{"playlistcontrol", "cmd:load", "track_id:" + mediaId.substring(6)});
            return true;
        } else if (mediaId.startsWith("playlist/")) {
            sendPlayCommand(new String[]{"playlistcontrol", "cmd:load", "playlist_id:" + mediaId.substring(9)});
            return true;
        } else if (mediaId.startsWith("favorite/")) {
            sendPlayCommand(new String[]{"favorites", "playlist", "play", "item_id:" + mediaId.substring(9)});
            return true;
        }
        return false;
    }

    private void sendPlayCommand(String[] command) {
        String playerId = MainActivity.activePlayer;
        if (null!=playerId && !playerId.isEmpty()) {
            rpc.sendMessage(playerId, command);
        }
    }

    private void switchPlayer(String playerId) {
        MainActivity.activePlayer = playerId;
    }

    private Uri drawableUri(int resId) {
        return Uri.parse("android.resource://" + packageName + "/" + resId);
    }

    private Uri resolveImageUri(String path) {
        if (null==path || path.isEmpty()) return null;
        String serverUrl = rpc.getServerUrl();
        if (null==serverUrl) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return Uri.parse(path);
        }
        return Uri.parse(serverUrl + path);
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
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
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
