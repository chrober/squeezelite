package org.lyrion.squeezelite;

import androidx.car.app.connection.CarConnection;

class ProjectionConnectionMonitor {
    static final int NOT_CONNECTED = CarConnection.CONNECTION_TYPE_NOT_CONNECTED;
    static final int PROJECTION = CarConnection.CONNECTION_TYPE_PROJECTION;

    private boolean projecting;

    boolean connectionChanged(int type) {
        boolean pause = projecting && type == NOT_CONNECTED;
        projecting = type == PROJECTION;
        return pause;
    }
}
