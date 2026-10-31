package com.craigd.lmsmaterial.app;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class AutoTabOrderTest {
    @Test
    public void defaultsToAlbumsFirst() {
        assertEquals(AutoTabOrder.DEFAULT, AutoTabOrder.parse(null));
        assertEquals(AutoTabOrder.DEFAULT, AutoTabOrder.parse(""));
        assertEquals(AutoTabOrder.ALBUMS, AutoTabOrder.parse(null).get(0));
    }

    @Test
    public void keepsSavedOrder() {
        String saved = "players,libraries,albums,artists,favorites,playlists,copy_queue";
        assertEquals(saved, AutoTabOrder.serialize(AutoTabOrder.parse(saved)));
    }

    @Test
    public void dropsUnknownAndDuplicatesAndAppendsMissing() {
        assertEquals(Arrays.asList(AutoTabOrder.PLAYLISTS, AutoTabOrder.ARTISTS, AutoTabOrder.ALBUMS,
                        AutoTabOrder.FAVORITES, AutoTabOrder.PLAYERS, AutoTabOrder.LIBRARIES, AutoTabOrder.COPY_QUEUE),
                AutoTabOrder.parse("playlists,bogus,artists,playlists"));
    }
}
