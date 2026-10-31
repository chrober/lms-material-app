package com.craigd.lmsmaterial.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PlayerAvailability {
    static final class Player {
        final String id;
        final String name;
        final boolean connected;
        final String ip; // host only, without the port
        final boolean powered;

        Player(String id, String name, boolean connected) {
            this(id, name, connected, "");
        }

        Player(String id, String name, boolean connected, String ip) {
            this(id, name, connected, ip, true);
        }

        Player(String id, String name, boolean connected, String ip, boolean powered) {
            this.id = id;
            this.name = name;
            this.connected = connected;
            this.ip = ip;
            this.powered = powered;
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
                        player.optString("name"), player.optInt("connected", 0) == 1,
                        player.optString("ip").split(":")[0], player.optInt("power", 1) == 1));
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

    static Player findPlayer(List<Player> players, String playerId) {
        if (players == null || playerId == null) {
            return null;
        }
        for (Player player : players) {
            if (playerId.equals(player.id)) {
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

    // Whether a player (null if LMS doesn't list it) runs on this device: by its IP, or, if a player app is set, if
    // it is the default player. Away from home LMS can see the phone at another address (e.g. a VPN gateway's), so
    // the IP alone doesn't tell.
    static boolean isLocalPlayer(Player player, String playerId, String playerName, Set<String> localAddresses,
                                 String defaultPlayer, boolean hasPlayerApp) {
        if (player != null && localAddresses != null && localAddresses.contains(player.ip)) {
            return true;
        }
        return hasPlayerApp && defaultPlayer != null && !defaultPlayer.trim().isEmpty() &&
                isAllowed(defaultPlayer.trim(), playerId, player != null ? player.name : playerName);
    }

    // A connected player running on this device: by IP, else (see isLocalPlayer) the default player.
    static Player findConnectedLocalPlayer(List<Player> players, Set<String> localAddresses, String defaultPlayer,
                                           boolean hasPlayerApp) {
        Player player = findConnectedLocalPlayer(players, localAddresses);
        if (player != null || players == null) {
            return player;
        }
        for (Player p : players) {
            if (p.connected && isLocalPlayer(p, p.id, p.name, localAddresses, defaultPlayer, hasPlayerApp)) {
                return p;
            }
        }
        return null;
    }

    // A connected player running on this device, e.g. the Squeezelite app.
    static Player findConnectedLocalPlayer(List<Player> players, Set<String> localAddresses) {
        if (players == null || localAddresses == null) {
            return null;
        }
        for (Player player : players) {
            if (player.connected && localAddresses.contains(player.ip)) {
                return player;
            }
        }
        return null;
    }
}
