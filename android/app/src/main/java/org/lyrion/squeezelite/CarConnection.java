/*
 *  Squeezelite Android
 *
 *  (c) Vincent Verdeil 2026 <werdeil@hotmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.lyrion.squeezelite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

/**
 * Watches whether Android Auto is projecting, via its content provider. The end of a session is
 * not reliably visible as a Bluetooth disconnection.
 */
public class CarConnection {
    // See androidx.car.app.connection.CarConnection
    private static final Uri URI = Uri.parse("content://androidx.car.app.connection");
    private static final String STATE = "CarConnectionState";
    // Sent by Android Auto when its state changes; the provider does not always notify the change, and while
    // idle the state is only polled every 30 s
    private static final String UPDATED_ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";
    public static final int NOT_CONNECTED = 0;
    public static final int UNKNOWN = -1;
    // Verify a zero state once, without visibly delaying local playback after disconnect.
    private static final long CONFIRM_DELAY = 500;
    private static final long ACTIVE_POLL_INTERVAL = 1000;
    private static final long IDLE_POLL_INTERVAL = 30000;

    private final Context context;
    private final Runnable onSessionEnded;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable confirmTask = this::confirm;
    private final Runnable pollTask = this::poll;
    private final CarConnectionState connectionState = new CarConnectionState();
    private long queryNumber = 0;
    private ContentObserver observer = null;
    private BroadcastReceiver receiver = null;
    private Runnable onSessionStarted = null;
    private Runnable onSessionConnected = null;
    private boolean seenConnected = false;
    private boolean playing = false;
    // 0 if no session is running
    private long connectedSince = 0;

    // Not for the main thread
    public static int state(Context context) {
        try (Cursor cursor = context.getContentResolver().query(URI, new String[]{STATE}, null, null, null)) {
            if (null == cursor) {
                return UNKNOWN;
            }
            int col = cursor.getColumnIndex(STATE);
            return col < 0 || !cursor.moveToNext() ? UNKNOWN : cursor.getInt(col);
        } catch (Exception e) {
            // Android Auto not installed, or too old
            Utils.debug("Car connection state unavailable");
            return UNKNOWN;
        }
    }

    public long connectedSince() {
        return connectedSince;
    }

    long queryVersion() {
        return queryNumber;
    }

    public void setOnSessionStarted(Runnable onSessionStarted) {
        this.onSessionStarted = onSessionStarted;
    }

    public void setOnSessionConnected(Runnable onSessionConnected) {
        this.onSessionConnected = onSessionConnected;
    }

    void markConnected() {
        onState(++queryNumber, 2, false);
    }

    private void markConnected(boolean transition) {
        if (null==observer) {
            return;
        }
        if (!seenConnected) {
            seenConnected = true;
            connectedSince = SystemClock.elapsedRealtime();
            if (null!=onSessionStarted) {
                onSessionStarted.run();
            }
            if (transition && null!=onSessionConnected) {
                onSessionConnected.run();
            }
        }
        handler.removeCallbacks(confirmTask);
        schedulePoll();
    }

    public void playbackStateChanged(boolean playing) {
        this.playing = playing;
        if (null!=observer) {
            schedulePoll();
        }
    }

    public CarConnection(Context context, Runnable onSessionEnded) {
        this.context = context.getApplicationContext();
        this.onSessionEnded = onSessionEnded;
    }

    public void start() {
        if (null!=observer) {
            return;
        }
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                read(false);
            }
        };
        try {
            context.getContentResolver().registerContentObserver(URI, false, observer);
        } catch (Exception e) {
            Utils.error("Failed to watch car connection", e);
            observer = null;
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                read(false);
            }
        };
        try {
            ContextCompat.registerReceiver(context, receiver, new IntentFilter(UPDATED_ACTION),
                    ContextCompat.RECEIVER_EXPORTED);
        } catch (Exception e) {
            Utils.error("Failed to receive car connection updates", e);
            receiver = null;
        }
        read(false);
    }

    public void release() {
        handler.removeCallbacks(confirmTask);
        handler.removeCallbacks(pollTask);
        if (null!=observer) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (Exception e) {
                Utils.error("Failed to stop watching car connection", e);
            }
            observer = null;
        }
        if (null!=receiver) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                Utils.error("Failed to stop receiving car connection updates", e);
            }
            receiver = null;
        }
    }

    private void read(boolean confirming) {
        long query = ++queryNumber;
        new Thread(() -> {
            int state = state(context);
            handler.post(() -> onState(query, state, confirming));
        }).start();
    }

    private void onState(long query, int state, boolean confirming) {
        if (null==observer) {
            // Released whilst the query was running
            return;
        }
        Utils.debug("state:"+state+", seenConnected:"+seenConnected+", confirming:"+confirming);
        int action = connectionState.observe(query, state, confirming);
        if (!connectionState.isCurrent(query)) {
            return;
        }
        if (CarConnectionState.INITIAL_CONNECTED==action || CarConnectionState.CONNECTED==action) {
            markConnected(CarConnectionState.CONNECTED==action);
        } else if (CarConnectionState.DISCONNECTED==action) {
            Utils.info("Android Auto session ended");
            seenConnected = false;
            connectedSince = 0;
            schedulePoll();
            try {
                onSessionEnded.run();
            } catch (Exception e) {
                Utils.error("Failed to handle Android Auto session end", e);
            }
        } else if (CarConnectionState.CONFIRM==action) {
            handler.removeCallbacks(confirmTask);
            handler.postDelayed(confirmTask, CONFIRM_DELAY);
        } else if (state>NOT_CONNECTED) {
            markConnected(false);
        } else if (UNKNOWN==state || (NOT_CONNECTED==state && !seenConnected)) {
            schedulePoll();
        }
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, seenConnected || playing ? ACTIVE_POLL_INTERVAL : IDLE_POLL_INTERVAL);
    }

    private void confirm() {
        read(true);
    }

    private void poll() {
        read(false);
    }
}
