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
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class LmsBrowseHelper {
    public static final String ROOT_ID = "__ROOT__";
    public static final String FAVORITES_ID = "__FAVORITES__";
    public static final String ARTISTS_ID = "__ARTISTS__";
    public static final String ALBUMS_ID = "__ALBUMS__";
    public static final String ALBUMS_NEW_ID = "__ALBUMS_NEW__";
    public static final String ALBUMS_RECENT_ID = "__ALBUMS_RECENT__";
    public static final String ALBUMS_RANDOM_ID = "__ALBUMS_RANDOM__";
    public static final String PLAYLISTS_ID = "__PLAYLISTS__";
    public static final String RADIOS_ID = "__RADIOS__";
    public static final String PLAYERS_ID = "__PLAYERS__";

    private static final String PREFS_HOME_ITEMS_KEY = "auto_home_items";
    private static final int BROWSE_LIMIT = 100;
    private static final int TIMEOUT_MS = 10000;

    private final JsonRpc rpc;
    private final SharedPreferences prefs;
    private final String packageName;

    public LmsBrowseHelper(Context context) {
        rpc = new JsonRpc(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        packageName = context.getPackageName();
    }

    public List<MediaBrowserCompat.MediaItem> loadChildren(String parentMediaId) {
        if (ROOT_ID.equals(parentMediaId)) {
            return loadRoot();
        } else if (FAVORITES_ID.equals(parentMediaId)) {
            return loadFavorites();
        } else if (ARTISTS_ID.equals(parentMediaId)) {
            return loadArtists();
        } else if (ALBUMS_ID.equals(parentMediaId) || ALBUMS_NEW_ID.equals(parentMediaId)) {
            return loadAlbums("sort:new");
        } else if (ALBUMS_RECENT_ID.equals(parentMediaId)) {
            return loadAlbums("sort:recentlyplayed");
        } else if (ALBUMS_RANDOM_ID.equals(parentMediaId)) {
            return loadAlbums("sort:random");
        } else if (PLAYLISTS_ID.equals(parentMediaId)) {
            return loadPlaylists();
        } else if (RADIOS_ID.equals(parentMediaId)) {
            return loadRadios();
        } else if (PLAYERS_ID.equals(parentMediaId)) {
            return loadPlayers();
        } else if (parentMediaId.startsWith("player/")) {
            switchPlayer(parentMediaId.substring(7));
            return loadRoot();
        } else if (parentMediaId.startsWith("artist/")) {
            return loadArtistAlbums(parentMediaId.substring(7));
        } else if (parentMediaId.startsWith("album/")) {
            return loadAlbumTracks(parentMediaId.substring(6));
        } else if (parentMediaId.startsWith("playlist/")) {
            return loadPlaylistTracks(parentMediaId.substring(9));
        }
        return new ArrayList<>();
    }

    private List<MediaBrowserCompat.MediaItem> loadRoot() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        String homeItemsJson = prefs.getString(PREFS_HOME_ITEMS_KEY, null);
        String[] homeItems;

        if (null!=homeItemsJson && !homeItemsJson.isEmpty() && !"[]".equals(homeItemsJson)) {
            try {
                JSONArray arr = new JSONArray(homeItemsJson);
                if (arr.length() > 0) {
                    homeItems = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        Object element = arr.get(i);
                        if (element instanceof JSONObject) {
                            homeItems[i] = ((JSONObject) element).optString("id", "");
                        } else {
                            homeItems[i] = arr.getString(i);
                        }
                    }
                } else {
                    homeItems = getDefaultHomeItems();
                }
            } catch (Exception e) {
                homeItems = getDefaultHomeItems();
            }
        } else {
            homeItems = getDefaultHomeItems();
        }

        for (String item : homeItems) {
            MediaBrowserCompat.MediaItem mi = mapHomeItemToMediaItem(item);
            if (null!=mi) {
                items.add(mi);
            }
            if (items.size() >= 6) break;
        }

        items.add(buildBrowsableItem(PLAYERS_ID, "Players", drawableUri(R.drawable.ic_speaker)));
        return items;
    }

    private String[] getDefaultHomeItems() {
        return new String[]{"std_favorites", "std_new", "std_radios", "std_playlists"};
    }

    private MediaBrowserCompat.MediaItem mapHomeItemToMediaItem(String stdId) {
        switch (stdId) {
            case "std_favorites":
                return buildBrowsableItem(FAVORITES_ID, "Favorites", drawableUri(R.drawable.ic_favorite));
            case "std_new":
                return buildBrowsableItem(ALBUMS_NEW_ID, "New Music", drawableUri(R.drawable.ic_new_releases));
            case "std_radios":
                return buildBrowsableItem(RADIOS_ID, "Radio", drawableUri(R.drawable.ic_radio));
            case "std_playlists":
                return buildBrowsableItem(PLAYLISTS_ID, "Playlists", drawableUri(R.drawable.ic_playlist));
            case "std_explore":
                return buildBrowsableItem(ARTISTS_ID, "Artists", drawableUri(R.drawable.ic_artist));
            case "std_recentlyplayed":
                return buildBrowsableItem(ALBUMS_RECENT_ID, "Recently Played", drawableUri(R.drawable.ic_history));
            case "std_random":
                return buildBrowsableItem(ALBUMS_RANDOM_ID, "Random", drawableUri(R.drawable.ic_shuffle));
            case "std_playcount":
                return buildBrowsableItem("__ALBUMS_PLAYCOUNT__", "Most Played", drawableUri(R.drawable.ic_star));
            default:
                return null;
        }
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

    private List<MediaBrowserCompat.MediaItem> loadArtists() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"artists", "0", String.valueOf(BROWSE_LIMIT), "tags:s"}, TIMEOUT_MS);
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

    private List<MediaBrowserCompat.MediaItem> loadAlbums(String sortParam) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"albums", "0", String.valueOf(BROWSE_LIMIT), sortParam, "tags:ajlsy"}, TIMEOUT_MS);
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
                String artworkId = album.optString("artwork_track_id", album.optString("id", ""));
                Uri artUri = resolveImageUri("/music/" + artworkId + "/cover");
                items.add(buildBrowsablePlayableItem("album/" + id, title, artist, artUri));
            }
        } catch (Exception e) {
            Utils.error("Failed to load albums", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadArtistAlbums(String artistId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"albums", "0", String.valueOf(BROWSE_LIMIT), "artist_id:" + artistId, "tags:ajlsy"}, TIMEOUT_MS);
            if (null==resp) return items;
            JSONObject result = resp.optJSONObject("result");
            if (null==result) return items;
            JSONArray loop = result.optJSONArray("albums_loop");
            if (null==loop) return items;

            for (int i = 0; i < loop.length(); i++) {
                JSONObject album = loop.getJSONObject(i);
                String id = album.optString("id", "");
                String title = album.optString("album", "");
                String artworkId = album.optString("artwork_track_id", album.optString("id", ""));
                Uri artUri = resolveImageUri("/music/" + artworkId + "/cover");
                items.add(buildBrowsablePlayableItem("album/" + id, title, null, artUri));
            }
        } catch (Exception e) {
            Utils.error("Failed to load artist albums", e);
        }
        return items;
    }

    private List<MediaBrowserCompat.MediaItem> loadAlbumTracks(String albumId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"titles", "0", String.valueOf(BROWSE_LIMIT), "album_id:" + albumId, "tags:adlN", "sort:tracknum"}, TIMEOUT_MS);
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

    private List<MediaBrowserCompat.MediaItem> loadRadios() {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            JSONObject resp = rpc.sendMessageSync("",
                    new String[]{"favorites", "items", "0", String.valueOf(BROWSE_LIMIT), "type:audio", "want_url:1"}, TIMEOUT_MS);
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
                String type = item.optString("type", "");
                if ("audio".equals(type)) {
                    items.add(buildPlayableItem("favorite/" + id, name, null, resolveImageUri(image)));
                }
            }
        } catch (Exception e) {
            Utils.error("Failed to load radios", e);
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

            for (int i = 0; i < loop.length(); i++) {
                JSONObject player = loop.getJSONObject(i);
                String id = player.optString("playerid", "");
                String name = player.optString("name", "");
                boolean isConnected = player.optInt("connected", 0) == 1;
                String subtitle = isConnected ? "Connected" : "Disconnected";
                items.add(buildBrowsableItem("player/" + id, name + " (" + subtitle + ")", null));
            }
        } catch (Exception e) {
            Utils.error("Failed to load players", e);
        }
        return items;
    }

    public boolean playMediaId(String mediaId) {
        if (mediaId.startsWith("album/")) {
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
}
