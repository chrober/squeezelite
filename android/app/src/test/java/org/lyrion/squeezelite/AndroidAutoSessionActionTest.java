package org.lyrion.squeezelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AndroidAutoSessionActionTest {
    @Test
    public void resumesWhenNeitherAndroidAutoOptionIsEnabled() {
        assertEquals(AndroidAutoSessionAction.RESUME,
                AndroidAutoSessionAction.forSettings(false, false));
    }

    @Test
    public void pausesWhenOnlyPauseOnDisconnectIsEnabled() {
        assertEquals(AndroidAutoSessionAction.PAUSE,
                AndroidAutoSessionAction.forSettings(false, true));
    }

    @Test
    public void stopsWhenAndroidAutoAutostartIsEnabled() {
        assertEquals(AndroidAutoSessionAction.STOP,
                AndroidAutoSessionAction.forSettings(true, false));
        assertEquals(AndroidAutoSessionAction.STOP,
                AndroidAutoSessionAction.forSettings(true, true));
    }
}
