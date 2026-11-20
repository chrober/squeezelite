package org.lyrion.squeezelite;

final class CarConnectionState {
    static final int NONE = 0;
    static final int INITIAL_CONNECTED = 1;
    static final int CONNECTED = 2;
    static final int CONFIRM = 3;
    static final int DISCONNECTED = 4;

    private long lastQuery = 0;
    private boolean initialized = false;
    private boolean connected = false;

    boolean isCurrent(long query) {
        return query==lastQuery;
    }

    int observe(long query, int state, boolean confirming) {
        if (query<=lastQuery) {
            return NONE;
        }
        lastQuery = query;
        if (state<0) {
            return NONE;
        }
        if (!initialized) {
            initialized = true;
            connected = state!=0;
            return connected ? INITIAL_CONNECTED : NONE;
        }
        if (state!=0) {
            if (!connected) {
                connected = true;
                return CONNECTED;
            }
            return NONE;
        }
        if (!connected) {
            return NONE;
        }
        if (!confirming) {
            return CONFIRM;
        }
        connected = false;
        return DISCONNECTED;
    }
}
