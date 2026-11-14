package com.craigd.lmsmaterial.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlayerAvailabilityTest {
    @Test
    public void findsTheSavedPlayerOnlyAfterItConnects() {
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false),
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true)));

        assertNull(PlayerAvailability.findConnectedPlayer(players, "00:11:22:33:44:55"));

        players.set(0, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true));
        PlayerAvailability.Player player = PlayerAvailability.findConnectedPlayer(
                players, "00:11:22:33:44:55");

        assertEquals("00:11:22:33:44:55", player.id);
        assertEquals("Phone", player.name);
    }

    @Test
    public void unrestrictedAllowsAnyPlayer() {
        assertTrue(PlayerAvailability.isAllowed(null, "aa:bb:cc:dd:ee:ff", "Kitchen"));
    }

    @Test
    public void restrictionMatchesIdOrExactName() {
        assertTrue(PlayerAvailability.isAllowed("00:11:22:33:44:55", "00:11:22:33:44:55", "Phone"));
        assertTrue(PlayerAvailability.isAllowed("Phone", "00:11:22:33:44:55", "Phone"));
        assertFalse(PlayerAvailability.isAllowed("Phone", "aa:bb:cc:dd:ee:ff", "Kitchen"));
        assertFalse(PlayerAvailability.isAllowed("Phone", "aa:bb:cc:dd:ee:ff", "Phone 2"));
    }

    @Test
    public void restrictedSelectionNeverPicksAnotherPlayer() {
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false)));

        assertNull(PlayerAvailability.findConnectedAllowedPlayer(players, "Phone"));

        players.set(1, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true));
        assertEquals("00:11:22:33:44:55", PlayerAvailability.findConnectedAllowedPlayer(players, "Phone").id);
    }

    @Test
    public void localPlayerIsMatchedByConnectedIpAddress() {
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.30")));

        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, Collections.singleton("192.168.1.30")));

        players.set(1, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.30"));
        assertEquals("00:11:22:33:44:55",
                PlayerAvailability.findConnectedLocalPlayer(players, Collections.singleton("192.168.1.30")).id);
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, Collections.singleton("10.0.0.5")));
    }

    @Test
    public void rememberedPlayerIsLocalWhenLmsSeesItAtAnotherAddress() {
        // Away from home, through a VPN: LMS lists the phone's stopped player at the gateway's address.
        PlayerAvailability.Player phone = new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.8", false);
        Set<String> local = Collections.singleton("10.20.30.40");

        assertTrue(PlayerAvailability.isLocalPlayer(phone, phone.id, local, phone.id, true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, local, phone.id, false));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, local, "aa:bb:cc:dd:ee:ff", true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, local, null, true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, local, "", true));
        // Not listed by LMS (e.g. forgotten): by its ID.
        assertTrue(PlayerAvailability.isLocalPlayer(null, phone.id, local, phone.id, true));
    }

    @Test
    public void localPlayerIsMatchedByIp() {
        PlayerAvailability.Player kitchen = new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20");

        assertTrue(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, Collections.singleton("192.168.1.20"), null, false));
        assertFalse(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, Collections.singleton("192.168.1.30"), null, true));
    }

    @Test
    public void remoteDefaultPlayerIsNotTakenForThePhonesPlayer() {
        // A player app is set, but the default player is a remote one (Kitchen); the phone is away from home and
        // its own player isn't connected. Kitchen must not be started, selected or switched on as this phone's.
        PlayerAvailability.Player kitchen = new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20");
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(kitchen,
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.8")));
        Set<String> local = Collections.singleton("10.20.30.40");
        String remembered = "00:11:22:33:44:55";

        assertFalse(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, local, remembered, true));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, remembered, true));
        // Nothing remembered yet (never seen at this phone's IP): still not local.
        assertFalse(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, local, null, true));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, null, true));
        // Once the phone's player connects, it is found by its ID.
        players.set(1, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.8"));
        assertEquals(remembered, PlayerAvailability.findConnectedLocalPlayer(players, local, remembered, true).id);
    }

    @Test
    public void connectedLocalPlayerFallsBackToTheRememberedPlayer() {
        // Away from home, through a VPN: LMS sees the phone at the gateway's address.
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.8")));
        Set<String> local = Collections.singleton("10.20.30.40");

        assertEquals("00:11:22:33:44:55", PlayerAvailability.findConnectedLocalPlayer(players, local, "00:11:22:33:44:55", true).id);
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, "00:11:22:33:44:55", false));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, null, true));
        // A match by IP wins over the remembered player.
        assertEquals("aa:bb:cc:dd:ee:ff", PlayerAvailability.findConnectedLocalPlayer(players,
                Collections.singleton("192.168.1.20"), "00:11:22:33:44:55", true).id);
        // Not while it isn't connected.
        players.set(1, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.8"));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, "00:11:22:33:44:55", true));
    }

    @Test
    public void remembersThePlayerSeenAtThisDevicesAddress() {
        Map<String, String> stored = new HashMap<>();
        SharedPreferences prefs = prefs(stored);
        List<PlayerAvailability.Player> home = Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.30"));

        // At home: the phone's player is at the phone's address.
        assertEquals("00:11:22:33:44:55", PlayerAvailability.rememberLocalPlayer(prefs, home, Collections.singleton("192.168.1.30")));
        // Away: nothing at the phone's address, the remembered ID is kept.
        List<PlayerAvailability.Player> away = Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.8"));
        assertEquals("00:11:22:33:44:55", PlayerAvailability.rememberLocalPlayer(prefs, away, Collections.singleton("10.20.30.40")));
        // A disconnected player at the phone's address (stale entry) doesn't replace it.
        List<PlayerAvailability.Player> stale = Collections.singletonList(
                new PlayerAvailability.Player("11:11:11:11:11:11", "Old", false, "10.20.30.40"));
        assertEquals("00:11:22:33:44:55", PlayerAvailability.rememberLocalPlayer(prefs, stale, Collections.singleton("10.20.30.40")));
        // A new player app ID at the phone's address replaces it.
        List<PlayerAvailability.Player> reinstalled = Collections.singletonList(
                new PlayerAvailability.Player("22:22:22:22:22:22", "Phone", true, "192.168.1.30"));
        assertEquals("22:22:22:22:22:22", PlayerAvailability.rememberLocalPlayer(prefs, reinstalled, Collections.singleton("192.168.1.30")));
        assertNull(PlayerAvailability.rememberLocalPlayer(prefs(new HashMap<>()), null, Collections.singleton("192.168.1.30")));
    }

    @Test
    public void twoPlayersAtThisDevicesAddressAreAmbiguous() {
        // E.g. the official Squeezelite app and a fork, both running on the phone.
        List<PlayerAvailability.Player> players = Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Squeezelite", true, "192.168.1.30"),
                new PlayerAvailability.Player("66:77:88:99:aa:bb", "Squeezelite (fork)", true, "192.168.1.30"));
        Set<String> phone = Collections.singleton("192.168.1.30");

        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, phone));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, phone, null, true));
        // The remembered player is taken if it is one of them...
        assertEquals("66:77:88:99:aa:bb",
                PlayerAvailability.findConnectedLocalPlayer(players, phone, "66:77:88:99:aa:bb", true).id);
        // ...but not another one.
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, phone, "aa:bb:cc:dd:ee:ff", true));

        // Nothing is remembered from an ambiguous match, and a remembered ID is kept.
        Map<String, String> stored = new HashMap<>();
        assertNull(PlayerAvailability.rememberLocalPlayer(prefs(stored), players, phone));
        stored.put(PlayerAvailability.LOCAL_PLAYER_ID_KEY, "00:11:22:33:44:55");
        assertEquals("00:11:22:33:44:55", PlayerAvailability.rememberLocalPlayer(prefs(stored), players, phone));
    }

    private static SharedPreferences prefs(Map<String, String> stored) {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.getString(anyString(), any())).thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), any())).thenAnswer(i -> {
            stored.put(i.getArgument(0), i.getArgument(1));
            return editor;
        });
        return prefs;
    }

    @Test
    public void findsAPlayerWhetherOrNotItIsConnected() {
        List<PlayerAvailability.Player> players = Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.30", false));

        PlayerAvailability.Player player = PlayerAvailability.findPlayer(players, "00:11:22:33:44:55");
        assertEquals("Phone", player.name);
        assertFalse(player.connected);
        assertFalse(player.powered);
        assertNull(PlayerAvailability.findPlayer(players, "11:22:33:44:55:66"));
        assertNull(PlayerAvailability.findPlayer(null, "00:11:22:33:44:55"));
    }
}
