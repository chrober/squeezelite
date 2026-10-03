package org.lyrion.squeezelite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectionConnectionMonitorTest {
    @Test
    public void pausesOnlyAfterProjectionDisconnects() {
        ProjectionConnectionMonitor monitor = new ProjectionConnectionMonitor();

        assertFalse(monitor.connectionChanged(ProjectionConnectionMonitor.NOT_CONNECTED));
        assertFalse(monitor.connectionChanged(ProjectionConnectionMonitor.PROJECTION));
        assertTrue(monitor.connectionChanged(ProjectionConnectionMonitor.NOT_CONNECTED));
    }

    @Test
    public void doesNotPauseWhenProjectionStateIsRepeated() {
        ProjectionConnectionMonitor monitor = new ProjectionConnectionMonitor();

        assertFalse(monitor.connectionChanged(ProjectionConnectionMonitor.PROJECTION));
        assertFalse(monitor.connectionChanged(ProjectionConnectionMonitor.PROJECTION));
    }
}
