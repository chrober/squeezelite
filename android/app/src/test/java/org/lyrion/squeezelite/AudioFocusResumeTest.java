package org.lyrion.squeezelite;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioFocusResumeTest {
    @Test
    public void transientLossResumesWhenFocusReturns() {
        AudioFocusResume state = new AudioFocusResume();
        state.transientLoss();
        assertTrue(state.waitingForFocus());
        assertTrue(state.focusGained());
        assertFalse(state.focusGained());
    }

    @Test
    public void permanentLossNeverResumesAutomatically() {
        AudioFocusResume state = new AudioFocusResume();
        state.transientLoss();
        state.permanentLoss();
        assertFalse(state.waitingForFocus());
        assertFalse(state.focusGained());
        state.transientLoss();
        assertFalse(state.focusGained());
        state.playbackStarted();
        state.transientLoss();
        assertTrue(state.focusGained());
    }

    @Test
    public void explicitPausePreventsDelayedResumeUntilPlaybackRestarts() {
        AudioFocusResume state = new AudioFocusResume();
        state.transientLoss();
        state.pauseRequested();
        assertFalse(state.focusGained());
        state.transientLoss();
        assertFalse(state.focusGained());
        state.playbackStarted();
        state.transientLoss();
        assertTrue(state.focusGained());
    }
}
