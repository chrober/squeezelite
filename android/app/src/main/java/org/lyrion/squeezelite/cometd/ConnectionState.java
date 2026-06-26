/**
 * Adapted from LMS-Material-App / android-squeezer
 * Apache-2.0 license
 */

package org.lyrion.squeezelite.cometd;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.lyrion.squeezelite.Utils;

public class ConnectionState {
    ConnectionState() {
    }

    public enum Error {
        START_CLIENT_ERROR,
        INVALID_URL,
        CONNECTION_ERROR;
    }

    public enum State {
        MANUAL_DISCONNECT,
        DISCONNECTED,
        CONNECTION_STARTED,
        CONNECTION_FAILED,
        CONNECTION_COMPLETED,
        REHANDSHAKING;

        boolean isConnected() {
            return (this == CONNECTION_COMPLETED);
        }

        boolean isConnectInProgress() {
            return (this == CONNECTION_STARTED);
        }

        boolean isRehandshaking() {
            return (this == REHANDSHAKING);
        }
    }

    private volatile State state = State.DISCONNECTED;

    private static final long AUTO_CONNECT_INTERVAL = 60_000;
    private volatile long rehandshake;
    private static final long REHANDSHAKE_TIMEOUT = 15 * 60_000;

    void setConnectionState(State connectionState) {
        Utils.info(state + " => " + connectionState);
        updateConnectionState(connectionState);
    }

    void setConnectionError(Error connectionError) {
        Utils.info(state + " => " + connectionError);
        updateConnectionState(State.CONNECTION_FAILED);
    }

    private void updateConnectionState(State connectionState) {
        if (connectionState == State.REHANDSHAKING) {
            rehandshake = SystemClock.elapsedRealtime();
        }
        state = connectionState;
    }

    boolean isConnected() {
        return state.isConnected();
    }

    boolean isConnectInProgress() {
        return state.isConnectInProgress();
    }

    boolean isRehandshaking() {
        return state.isRehandshaking();
    }

    boolean canRehandshake() {
        return isRehandshaking()
                && ((SystemClock.elapsedRealtime() - rehandshake) < REHANDSHAKE_TIMEOUT);
    }

    @NonNull
    @Override
    public String toString() {
        return "" + state;
    }
}
