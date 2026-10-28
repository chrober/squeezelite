package org.lyrion.squeezelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidAutoSessionActionTest {
    @Test
    public void resumesWhenNeitherAndroidAutoOptionIsEnabled() {
        assertEquals(AndroidAutoSessionAction.RESUME,
                AndroidAutoSessionAction.forSettings(false, false));
    }

    @Test
    public void pausesWhenPauseOnSessionChangeIsEnabled() {
        assertEquals(AndroidAutoSessionAction.PAUSE,
                AndroidAutoSessionAction.forSettings(false, true));
    }

    @Test
    public void resumesOnConnectionUnlessPauseOnSessionChangeIsEnabled() {
        assertTrue(AndroidAutoSessionAction.resumeOnConnection(false));
        assertFalse(AndroidAutoSessionAction.resumeOnConnection(true));
    }

    @Test
    public void stopsWhenAndroidAutoAutostartIsEnabled() {
        assertEquals(AndroidAutoSessionAction.STOP,
                AndroidAutoSessionAction.forSettings(true, false));
        assertEquals(AndroidAutoSessionAction.STOP,
                AndroidAutoSessionAction.forSettings(true, true));
    }
}
