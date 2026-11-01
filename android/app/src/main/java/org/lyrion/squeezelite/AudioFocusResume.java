/*
 * Squeezelite Android
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.lyrion.squeezelite;

final class AudioFocusResume {
    private boolean pausedByLoss;
    private boolean suppressAutoResume;

    void playbackStarted() {
        pausedByLoss = false;
        suppressAutoResume = false;
    }

    void pauseRequested() {
        pausedByLoss = false;
        suppressAutoResume = true;
    }

    void transientLoss() {
        pausedByLoss = !suppressAutoResume;
    }

    void permanentLoss() {
        pausedByLoss = false;
        suppressAutoResume = true;
    }

    boolean waitingForFocus() {
        return pausedByLoss;
    }

    boolean focusGained() {
        boolean resume = pausedByLoss && !suppressAutoResume;
        pausedByLoss = false;
        return resume;
    }
}
