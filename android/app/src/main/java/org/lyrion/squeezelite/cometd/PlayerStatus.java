/**
 * Adapted from LMS-Material-App
 * MIT license
 */

package org.lyrion.squeezelite.cometd;

import androidx.annotation.NonNull;

public class PlayerStatus {
    public long timestamp;
    public String id;
    public String title;
    public String artist;
    public String album;
    public String genre;
    public String mode = "";
    public long duration = 0;
    public long time = 0;
    public boolean isPlaying = false;
    public boolean hasTime = false;
    public boolean hasTrack = false;
    public int trackNum = 0;
    public int year = 0;
    public int playlistTracks = 0;
    public String artworkUrl;
    public String coverId;

    @NonNull
    @Override
    public String toString() {
        return "id:" + id + ", title:" + title + ", artist:" + artist + ", album:" + album +
                ", duration:" + duration + ", time:" + time + ", isPlaying:" + isPlaying;
    }
}
