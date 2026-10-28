package org.lyrion.squeezelite;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CarConnectionTest {
    @Test
    public void pollsMoreFrequentlyDuringAnActiveCarSession() {
        assertEquals(1000, CarConnectionPolling.interval(true));
    }

    @Test
    public void keepsTheLowFrequencyPollOutsideACarSession() {
        assertEquals(30000, CarConnectionPolling.interval(false));
    }
}
