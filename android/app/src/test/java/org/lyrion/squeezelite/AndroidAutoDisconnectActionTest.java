package org.lyrion.squeezelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AndroidAutoDisconnectActionTest {
    @Test
    public void preservesUpstreamStopWhenAutoStartIsEnabled() {
        assertEquals(AndroidAutoDisconnectAction.STOP, AndroidAutoDisconnectAction.forSettings(true, false));
        assertEquals(AndroidAutoDisconnectAction.STOP, AndroidAutoDisconnectAction.forSettings(true, true));
    }

    @Test
    public void pausesOnlyWhenOptedIn() {
        assertEquals(AndroidAutoDisconnectAction.NONE, AndroidAutoDisconnectAction.forSettings(false, false));
        assertEquals(AndroidAutoDisconnectAction.PAUSE, AndroidAutoDisconnectAction.forSettings(false, true));
    }
}
