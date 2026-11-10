package com.craigd.lmsmaterial.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
}
