package org.lyrion.squeezelite;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CarConnectionTest {
    @Test
    public void pollsMoreFrequentlyDuringAnActiveCarSession() {
        assertEquals(1000, CarConnectionPolling.interval(true, false));
    }

    @Test
    public void pollsMoreFrequentlyWhilePlayingToDetectAnAutoSession() {
        assertEquals(1000, CarConnectionPolling.interval(false, true));
    }

    @Test
    public void keepsTheLowFrequencyPollOutsideACarSession() {
        assertEquals(30000, CarConnectionPolling.interval(false, false));
    }
}
