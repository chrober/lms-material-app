/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Order of the top-level Android Auto tabs, stored as a comma separated list of tab keys.
 */
public class AutoTabOrder {
    public static final String PREF_KEY = "auto_tab_order";

    public static final String ALBUMS = "albums";
    public static final String ARTISTS = "artists";
    public static final String FAVORITES = "favorites";
    public static final String PLAYLISTS = "playlists";
    public static final String PLAYERS = "players";
    public static final String LIBRARIES = "libraries";

    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(
            ALBUMS, ARTISTS, FAVORITES, PLAYLISTS, PLAYERS, LIBRARIES));

    /**
     * Parse a stored value. Unknown and duplicate keys are dropped, and any tab missing
     * from the stored value is appended in its default position order.
     */
    public static List<String> parse(String value) {
        List<String> order = new ArrayList<>();
        if (null!=value) {
            for (String key : value.split(",")) {
                key = key.trim();
                if (DEFAULT.contains(key) && !order.contains(key)) {
                    order.add(key);
                }
            }
        }
        for (String key : DEFAULT) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        return order;
    }

    public static String serialize(List<String> order) {
        return String.join(",", order);
    }
}
