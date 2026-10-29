package org.lyrion.squeezelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CarConnectionStateTest {
    @Test
    public void initialConnectionIsNotANewSession() {
        CarConnectionState state = new CarConnectionState();
        assertEquals(CarConnectionState.INITIAL_CONNECTED, state.observe(1, 2, false));
        assertEquals(CarConnectionState.NONE, state.observe(2, 2, false));
    }

    @Test
    public void newSessionAndConfirmedEndAreReportedOnce() {
        CarConnectionState state = new CarConnectionState();
        assertEquals(CarConnectionState.NONE, state.observe(1, 0, false));
        assertEquals(CarConnectionState.CONNECTED, state.observe(2, 2, false));
        assertEquals(CarConnectionState.CONFIRM, state.observe(3, 0, false));
        assertEquals(CarConnectionState.DISCONNECTED, state.observe(4, 0, true));
        assertEquals(CarConnectionState.NONE, state.observe(5, 0, true));
    }

    @Test
    public void failedQueriesAndOldResultsDoNotEndSession() {
        CarConnectionState state = new CarConnectionState();
        state.observe(1, 2, false);
        assertEquals(CarConnectionState.NONE, state.observe(3, -1, true));
        assertEquals(CarConnectionState.NONE, state.observe(2, 0, true));
        assertEquals(CarConnectionState.CONFIRM, state.observe(4, 0, false));
        assertEquals(CarConnectionState.DISCONNECTED, state.observe(5, 0, true));
    }

    @Test
    public void unknownInitialStateDoesNotInventAConnectionTransition() {
        CarConnectionState state = new CarConnectionState();
        assertEquals(CarConnectionState.NONE, state.observe(1, -1, false));
        assertEquals(CarConnectionState.INITIAL_CONNECTED, state.observe(2, 2, false));
    }

    @Test
    public void connectedResultPreventsAnOlderDisconnectConfirmation() {
        CarConnectionState state = new CarConnectionState();
        state.observe(1, 2, false);
        state.observe(2, 0, false);
        assertEquals(CarConnectionState.NONE, state.observe(4, 2, false));
        assertEquals(CarConnectionState.NONE, state.observe(3, 0, true));
        assertEquals(CarConnectionState.CONFIRM, state.observe(5, 0, false));
    }
}
