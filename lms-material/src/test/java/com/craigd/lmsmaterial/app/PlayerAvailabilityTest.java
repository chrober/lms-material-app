package com.craigd.lmsmaterial.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void defaultPlayerIsLocalWhenLmsSeesItAtAnotherAddress() {
        // Away from home, through a VPN: LMS lists the phone's stopped player at the gateway's address.
        PlayerAvailability.Player phone = new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.8", false);
        Set<String> local = Collections.singleton("10.20.30.40");

        assertTrue(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, "Phone", true));
        assertTrue(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, phone.id, true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, "Phone", false));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, "Kitchen", true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, null, true));
        assertFalse(PlayerAvailability.isLocalPlayer(phone, phone.id, "Phone", local, " ", true));
    }

    @Test
    public void localPlayerIsMatchedByIpOrAsUnlistedDefault() {
        PlayerAvailability.Player kitchen = new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20");

        assertTrue(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, "Kitchen",
                Collections.singleton("192.168.1.20"), null, false));
        assertFalse(PlayerAvailability.isLocalPlayer(kitchen, kitchen.id, "Kitchen",
                Collections.singleton("192.168.1.30"), "Phone", true));
        // Not listed by LMS (e.g. forgotten): the default player, by the name last seen.
        assertTrue(PlayerAvailability.isLocalPlayer(null, "00:11:22:33:44:55", "Phone",
                Collections.singleton("192.168.1.30"), "Phone", true));
    }

    @Test
    public void connectedLocalPlayerFallsBackToTheDefaultPlayer() {
        // Away from home, through a VPN: LMS sees the phone at the gateway's address.
        List<PlayerAvailability.Player> players = new ArrayList<>(Arrays.asList(
                new PlayerAvailability.Player("aa:bb:cc:dd:ee:ff", "Kitchen", true, "192.168.1.20"),
                new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", true, "192.168.1.8")));
        Set<String> local = Collections.singleton("10.20.30.40");

        assertEquals("00:11:22:33:44:55", PlayerAvailability.findConnectedLocalPlayer(players, local, "Phone", true).id);
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, "Phone", false));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, null, true));
        // A match by IP wins over the default player.
        assertEquals("aa:bb:cc:dd:ee:ff", PlayerAvailability.findConnectedLocalPlayer(players,
                Collections.singleton("192.168.1.20"), "Phone", true).id);
        // Not while it isn't connected.
        players.set(1, new PlayerAvailability.Player("00:11:22:33:44:55", "Phone", false, "192.168.1.8"));
        assertNull(PlayerAvailability.findConnectedLocalPlayer(players, local, "Phone", true));
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
