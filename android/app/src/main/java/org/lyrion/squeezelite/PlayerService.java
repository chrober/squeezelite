/*
 *  Squeezelite Android
 *
 *  (c) Craig Drummond 2025-2026 <craig.p.drummond@gmail.com>
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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PlayerService extends MediaBrowserServiceCompat {
    // How long after losing connection to server should we stop player?
    public static final String STATUS_INTENT = PlayerService.class.getCanonicalName()+".STATUS";
    private static final String QUIT_INTENT = PlayerService.class.getCanonicalName() + ".QUIT";
    public static final String RUNNING_KEY = "running";
    public static final String NOTIFICATION_CHANNEL_ID = "squeezelite_service";
    private static final int MSG_ID = 1;

    private String currentServerAddress = null;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private final Handler handler;
    private PowerManager.WakeLock wakeLock = null;
    private Library lib = null;
    private int initialConnectionTimeout = 0;
    private int connectionLostTimeout = 0;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> terminateOnConnectionLostHandler;
    private String playerName;
    private MediaSessionCompat mediaSession;
    private MediaSessionCompat.Callback mediaSessionCallback;
    private volatile NowPlaying nowPlaying;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private boolean playbackActive = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (AudioManager.AUDIOFOCUS_GAIN==focusChange) {
            hasAudioFocus = true;
        } else if (AudioManager.AUDIOFOCUS_LOSS==focusChange ||
                   AudioManager.AUDIOFOCUS_LOSS_TRANSIENT==focusChange ||
                   AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK==focusChange) {
            hasAudioFocus = false;
        }
    };

    public PlayerService() {
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Utils.debug("");
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debug("");
        startForegroundService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utils.debug("");
        stopForegroundService();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable android.os.Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                .setMediaId("__INFO__")
                .setTitle("Squeezelite is for playback only.")
                .setSubtitle("Browse music via Lyrion app.")
                .build();
        items.add(new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        result.sendResult(items);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Utils.debug("");
        if (intent != null) {
            String action = intent.getAction();
            if (QUIT_INTENT.equals(action)) {
                stopForegroundService();
                return START_NOT_STICKY;
            }
        }

        SharedPreferences prefs = Prefs.get(this);

        if (!Prefs.hasBeenConfigured(prefs)) {
            Intent actIntent = new Intent(this, MainActivity.class);
            actIntent.putExtra(MainActivity.FROM_PLAYER_SERVICE_EXTRA, true);
            actIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(actIntent);
            stopForegroundService();
            return START_NOT_STICKY;
        }

        connectionLostTimeout = Utils.toInt(Prefs.get(this).getString(Prefs.CONNECTION_LOST_TIMEOUT_KEY, Prefs.DEFAULT_CONNECTION_LOST_TIMEOUT), 60);
        initialConnectionTimeout = Utils.toInt(Prefs.get(this).getString(Prefs.CONNECTION_LOST_TIMEOUT_KEY, Prefs.DEFAULT_CONNECTION_LOST_TIMEOUT), 300);
        if (null!=mediaSession) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    private void startForegroundService() {
        Utils.debug("");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        } else {
            notificationBuilder = new NotificationCompat.Builder(this);
        }
        createNotification();
        startPlayer();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        Utils.debug("");
        notificationManager = NotificationManagerCompat.from(this);
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, getApplicationContext().getResources().getString(R.string.main_notification), NotificationManager.IMPORTANCE_LOW);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        chan.setShowBadge(false);
        chan.enableLights(false);
        chan.enableVibration(false);
        chan.setSound(null, null);
        notificationManager.createNotificationChannel(chan);
        notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
    }

    @SuppressLint("MissingPermission")
    private synchronized Notification updateNotification() {
        Utils.debug("");
        if (!Utils.notificationAllowed(this, NOTIFICATION_CHANNEL_ID)) {
            return null;
        }
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.FROM_PLAYER_SERVICE_EXTRA, true);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
            SharedPreferences prefs = Prefs.get(this);
            String name = Utils.isEmpty(playerName) ? prefs.getString(Prefs.PLAYER_NAME_KEY, Prefs.DEFAULT_PLAYER_NAME) : playerName;
            Intent quitIntent = new Intent(this, PlayerService.class);
            quitIntent.setAction(QUIT_INTENT);

            String track = null==nowPlaying ? null : nowPlaying.getDescription();
            notificationBuilder
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_mono_icon)
                    .setContentTitle(name + (Utils.isEmpty(currentServerAddress) ? "" : (" (" + currentServerAddress +")")))
                    .setContentText(track)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setVibrate(null)
                    .setSound(null)
                    .setShowWhen(false)
                    .setChannelId(NOTIFICATION_CHANNEL_ID);
            notificationBuilder.clearActions();
            notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_action_quit, getString(R.string.stop_player), PendingIntent.getService(this, 0, quitIntent, PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)));
            Notification notification = notificationBuilder.build();
            Utils.debug("Build notification.");
            notificationManager.notify(MSG_ID, notification);
            return notification;
        } catch (Exception e) {
            Utils.error("Failed to create control notification", e);
        }
        return null;
    }

    private void createNotification() {
        Utils.debug("");
        Notification notification = updateNotification();
        if (null==notification) {
            return;
        }

        Utils.debug("startForegroundService");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Utils.debug("startForegroundService");
            startForegroundService(new Intent(this, PlayerService.class));
        } else {
            Utils.debug("startService");
            startService(new Intent(this, PlayerService.class));
        }

        Utils.debug("ServiceCompat.startForeground");
        ServiceCompat.startForeground(this, MSG_ID, notification, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
    }

    private void stopForegroundService() {
        Utils.debug("");
        stopForeground(true);
        stopSelf();
        stopPlayer();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void startPlayer() {
        if (null==lib) {
            lib = new Library();
        }
        startTerminateTimer(initialConnectionTimeout);
        playerName = lib.startPlayer(this);
        if (Prefs.get(this).getBoolean(Prefs.USE_WAKE_LOCK_KEY, Prefs.DEFAULT_USE_WAKE_LOCK)) {
            if (null==wakeLock) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Squeezelite:player");
            }
            if (null!=wakeLock) {
                wakeLock.acquire();
            }
        }
        sendStatus(true);
        if (!Utils.isEmpty(playerName)) {
            updateNotification();
        }

        mediaSession = new MediaSessionCompat(getApplicationContext(), "Squeezelite");
        if (mediaSessionCallback==null) {
            mediaSessionCallback=new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    Utils.debug("");
                    if (null!=lib) {
                        lib.play();
                    }
                }

                @Override
                public void onPause() {
                    Utils.debug("");
                    if (null!=lib) {
                        lib.pause();
                    }
                }

                @Override
                public void onStop() {
                    Utils.debug("");
                    if (null!=lib) {
                        lib.stopPlayback();
                    }
                }

                @Override
                public void onSkipToNext() {
                    if (null!=lib) {
                        lib.next();
                    }                }

                @Override
                public void onSkipToPrevious() {
                    if (null!=lib) {
                        lib.prev();
                    }
                }

                @Override
                public void onSeekTo(long pos) {
                    if (null!=lib) {
                        lib.seekTo(pos);
                    }
                }

                // Act on ACTION_DOWN, and consume it, so that the event does not also reach the
                // transport callbacks above. ACTION_UP falls through to super, which ignores it.
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                    KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (null!=lib && null!=event && KeyEvent.ACTION_DOWN==event.getAction()) {
                        Utils.debug("KeyCode:" + event.getKeyCode());
                        switch (event.getKeyCode()) {
                            case KeyEvent.KEYCODE_MEDIA_PLAY:
                                lib.play();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                lib.pause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_STOP:
                                lib.stopPlayback();
                                return true;
                            // These do not say which of the two is wanted, so toggle
                            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            case KeyEvent.KEYCODE_HEADSETHOOK:
                                lib.playPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                lib.prev();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_NEXT:
                                lib.next();
                                return true;
                            default:
                                break;
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent);
                }
            };
        }
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(mediaSessionCallback);
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .build());
        mediaSession.setActive(true);
        if (Prefs.get(this).getBoolean(Prefs.SEND_TRACK_DETAILS_KEY, Prefs.DEFAULT_SEND_TRACK_DETAILS)) {
            nowPlaying = new NowPlaying(this, lib, mediaSession);
            nowPlaying.update();
        }
    }

    private void stopPlayer() {
        if (null==lib) {
            return;
        }
        if (null!=wakeLock) {
            wakeLock.release();
            wakeLock = null;
        }
        sendStatus(false);
        stopTerminateTimer();
        setPlaybackActive(false);
        if (null!=nowPlaying) {
            nowPlaying.release();
            nowPlaying = null;
        }
        lib.stopPlayer(this);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
    }

    public void playbackStateChanged() {
        Utils.debug("");
        NowPlaying np = nowPlaying;
        if (null!=np) {
            handler.post(np::update);
        }
    }

    public void trackChanged() {
        updateNotification();
    }

    public void setPlaybackActive(boolean active) {
        playbackActive = active;
        if (active) {
            requestAudioFocus();
        } else {
            abandonAudioFocus();
        }
    }

    private void sendStatus(boolean running) {
        Intent intent = new Intent(STATUS_INTENT);
        intent.putExtra(RUNNING_KEY, running);
        sendBroadcast(intent);
    }

    public void poweredOff() {
        Utils.debug("");
        if (Prefs.get(this).getBoolean(Prefs.STOP_ON_POWER_OFF_KEY, Prefs.DEFAULT_STOP_ON_POWER_OFF)) {
            handler.post(this::stopForegroundService);
        }
    }

    public void connectionStateChanged(String ip) {
        boolean changed = (null==currentServerAddress && null!=ip) || (null!=currentServerAddress && null==ip) || (null!=currentServerAddress && !currentServerAddress.equals(ip));
        currentServerAddress = ip;
        if (changed) {
            handler.post(this::updateNotification);
        }
        if (Utils.isEmpty(ip)) {
            startTerminateTimer(connectionLostTimeout);
        } else {
            stopTerminateTimer();
            // Now that we know where the server is, read what it is playing
            playbackStateChanged();
        }
    }

    private void startTerminateTimer(int timeout) {
        Utils.debug("");
        stopTerminateTimer();
        if (timeout>0) {
            terminateOnConnectionLostHandler = executorService.schedule(this::stopForegroundService, timeout, TimeUnit.SECONDS);
        }
    }

    private void stopTerminateTimer() {
        Utils.debug("");
        if (null!= terminateOnConnectionLostHandler) {
            terminateOnConnectionLostHandler.cancel(false);
            terminateOnConnectionLostHandler = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        if (hasAudioFocus) {
            return;
        }
        if (null==audioManager) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (null==audioManager) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (null==audioFocusRequest) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(audioFocusListener, handler)
                        .build();
            }
            hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            hasAudioFocus = audioManager.requestAudioFocus(
                    audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (!hasAudioFocus) {
            Utils.warn("Audio focus request was not granted");
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (!hasAudioFocus || null==audioManager) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && null!=audioFocusRequest) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        hasAudioFocus = false;
    }
}
