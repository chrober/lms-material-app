package com.craigd.lmsmaterial.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PlayerAvailability {
    static final class Player {
        final String id;
        final String name;
        final boolean connected;

        Player(String id, String name, boolean connected) {
            this.id = id;
            this.name = name;
            this.connected = connected;
        }
    }

    private PlayerAvailability() {
    }

    /**
     * The only player that may be controlled, when "Only control default" is enabled and a
     * default player (name or ID) is set; otherwise null.
     */
    static String restrictedPlayer(SharedPreferences prefs) {
        if (!prefs.getBoolean(SettingsActivity.SINGLE_PLAYER_PREF_KEY, false)) {
            return null;
        }
        String defaultPlayer = prefs.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null);
        return null == defaultPlayer || defaultPlayer.trim().isEmpty() ? null : defaultPlayer.trim();
    }

    // Same matching as the web UI's "?player=x&single" handling: ID or exact name.
    static boolean isAllowed(String restricted, String id, String name) {
        return restricted == null || restricted.equals(id) || restricted.equals(name);
    }

    static List<Player> parse(JSONArray players) {
        List<Player> parsed = new ArrayList<>();
        if (players == null) {
            return parsed;
        }
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player != null) {
                parsed.add(new Player(player.optString("playerid"),
                        player.optString("name"), player.optInt("connected", 0) == 1));
            }
        }
        return parsed;
    }

    static Player findConnectedPlayer(JSONArray players, String playerId) {
        if (players == null || playerId == null || playerId.isEmpty()) {
            return null;
        }
        return findConnectedPlayer(parse(players), playerId);
    }

    static Player findConnectedAllowedPlayer(List<Player> players, String restricted) {
        if (players == null || restricted == null) {
            return null;
        }
        for (Player player : players) {
            if (player.connected && isAllowed(restricted, player.id, player.name)) {
                return player;
            }
        }
        return null;
    }

    static Player findConnectedPlayer(List<Player> players, String playerId) {
        if (players == null || playerId == null || playerId.isEmpty()) {
            return null;
        }
        for (Player player : players) {
            if (player.connected && playerId.equals(player.id)) {
                return player;
            }
        }
        return null;
    }
}
