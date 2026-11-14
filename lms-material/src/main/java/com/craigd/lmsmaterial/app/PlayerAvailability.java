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

    // The ID of the player last seen at one of this device's addresses (e.g. the Squeezelite app's, at home).
    static final String LOCAL_PLAYER_ID_KEY = "local_player_id";

    // Remembers the player LMS lists at one of this device's addresses, so that it is still recognised where LMS
    // sees the device at another address (e.g. a VPN gateway's). Returns the ID now remembered, or null.
    static String rememberLocalPlayer(SharedPreferences prefs, List<Player> players, Set<String> localAddresses) {
        Player local = findConnectedLocalPlayer(players, localAddresses);
        if (local != null) {
            rememberLocalPlayer(prefs, local.id);
        }
        return prefs.getString(LOCAL_PLAYER_ID_KEY, null);
    }

    static void rememberLocalPlayer(SharedPreferences prefs, String playerId) {
        if (playerId != null && !playerId.isEmpty() && !playerId.equals(prefs.getString(LOCAL_PLAYER_ID_KEY, null))) {
            prefs.edit().putString(LOCAL_PLAYER_ID_KEY, playerId).apply();
        }
    }

    // Whether a player (null if LMS doesn't list it) runs on this device: by its IP, or, if a player app is set, by
    // the ID of the player last seen at this device's IP (see rememberLocalPlayer). Away from home LMS can see the
    // phone at another address (e.g. a VPN gateway's), so the IP alone doesn't tell. The default player is not
    // assumed to be local: it can be a player elsewhere.
    static boolean isLocalPlayer(Player player, String playerId, Set<String> localAddresses, String localPlayerId,
                                 boolean hasPlayerApp) {
        if (player != null && localAddresses != null && localAddresses.contains(player.ip)) {
            return true;
        }
        return hasPlayerApp && localPlayerId != null && !localPlayerId.isEmpty() && localPlayerId.equals(playerId);
    }

    // A connected player running on this device: by IP, else (see isLocalPlayer) the player last seen at its IP.
    // Several players at this device's address (e.g. two player apps installed and running) are ambiguous: then
    // only the remembered player is taken, if it is one of them.
    static Player findConnectedLocalPlayer(List<Player> players, Set<String> localAddresses, String localPlayerId,
                                           boolean hasPlayerApp) {
        List<Player> byIp = connectedPlayersAt(players, localAddresses);
        if (byIp.size() == 1) {
            return byIp.get(0);
        }
        boolean remembered = localPlayerId != null && !localPlayerId.isEmpty();
        if (byIp.size() > 1) {
            if (remembered) {
                for (Player p : byIp) {
                    if (localPlayerId.equals(p.id)) {
                        return p;
                    }
                }
            }
            return null;
        }
        if (players == null || !hasPlayerApp || !remembered) {
            return null;
        }
        for (Player p : players) {
            if (p.connected && localPlayerId.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    // The connected player running on this device, e.g. the Squeezelite app, by IP; null if none or several.
    static Player findConnectedLocalPlayer(List<Player> players, Set<String> localAddresses) {
        List<Player> byIp = connectedPlayersAt(players, localAddresses);
        return byIp.size() == 1 ? byIp.get(0) : null;
    }

    private static List<Player> connectedPlayersAt(List<Player> players, Set<String> addresses) {
        List<Player> found = new ArrayList<>();
        if (players == null || addresses == null) {
            return found;
        }
        for (Player player : players) {
            if (player.connected && addresses.contains(player.ip)) {
                found.add(player);
            }
        }
        return found;
    }
}
