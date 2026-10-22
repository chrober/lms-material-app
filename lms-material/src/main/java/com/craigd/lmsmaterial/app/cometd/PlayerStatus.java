/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app.cometd;

import androidx.annotation.NonNull;

import com.craigd.lmsmaterial.app.Utils;

import java.util.LinkedList;
import java.util.List;

public class PlayerStatus {
    public long timestamp;
    public String id;
    public String title;
    public String artist;
    public String album;
    public String cover;
    public String mode;
    public long duration = 0;
    public long time = 0;
    public boolean isPlaying = false;
    public int volume = -1;
    public int playlistIndex = -1; // index of current track in the play queue, -1 if unknown/empty
    public double playlistTimestamp = 0; // changes whenever the play queue is modified

    @NonNull
    @Override
    public String toString() {
        return "id:"+id+", title:"+title+", artist:"+artist+", album:"+album+", cover:"+cover+", duration:"+Utils.timeStr(duration)+", time:"+Utils.timeStr(time)+", mode:"+mode+", isPlaying:"+isPlaying+", volume:"+volume+", playlistIndex:"+playlistIndex;
    }

    public String display() {
        List<String> parts = new LinkedList<>();
        if (!Utils.isEmpty(title)) {
            parts.add(title);
        }
        if (!Utils.isEmpty(artist)) {
            parts.add(artist);
        }
        //if (!Utils.isEmpty(album)) {
        //    parts.add(album);
        //}
        return String.join(" • ", parts);
    }
}
