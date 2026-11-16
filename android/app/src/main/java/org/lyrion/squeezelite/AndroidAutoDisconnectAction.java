package org.lyrion.squeezelite;

enum AndroidAutoDisconnectAction {
    NONE, PAUSE, STOP;

    static AndroidAutoDisconnectAction forSettings(boolean autoStart, boolean pauseOnDisconnect) {
        return autoStart ? STOP : (pauseOnDisconnect ? PAUSE : NONE);
    }
}
