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
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.ImageRequest;
import com.android.volley.toolbox.Volley;

import org.lyrion.squeezelite.cometd.CometClient;
import org.lyrion.squeezelite.cometd.ConnectionState;
import org.lyrion.squeezelite.cometd.PlayerStatus;

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
    private CometClient cometClient;
    private boolean sendBtMetadata = true;
    private boolean showYear = false;
    private boolean btA2dpConnected = true;
    private boolean androidAutoConnected = false;
    private BroadcastReceiver btA2dpReceiver;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {};
    private String lastTitle = "";
    private String lastArtist = "";
    private String lastAlbum = "";
    private String lastGenre = "";
    private long lastDurationMs = 0;
    private long lastTrackNum = 0;
    private long lastNumTracks = 0;
    private String lastArtworkUrl = "";
    private Bitmap lastArtwork = null;
    private RequestQueue imageRequestQueue;

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
        androidAutoConnected = true;
        if (null!=currentServerAddress && sendBtMetadata && null==cometClient) {
            startCometSubscription();
        }
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
        sendBtMetadata = prefs.getBoolean(Prefs.SEND_BT_METADATA_KEY, Prefs.DEFAULT_SEND_BT_METADATA);
        showYear = prefs.getBoolean(Prefs.SHOW_YEAR_KEY, Prefs.DEFAULT_SHOW_YEAR);
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

            notificationBuilder
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_mono_icon)
                    .setContentTitle(name + (Utils.isEmpty(currentServerAddress) ? "" : (" (" + currentServerAddress +")")))
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
                        lib.playPause();
                    }
                }

                @Override
                public void onPause() {
                    Utils.debug("");
                    if (null!=lib) {
                        lib.playPause();
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
                    if (null != lib) {
                        lib.sendCommand(new String[]{"time", Double.toString(pos / 1000.0)});
                    }
                }

                public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                    Utils.debug("");
                    KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (lib!=null && event!=null && 1==event.getAction()) {
                        Utils.debug("KeyCode:" + event.getKeyCode());
                        switch (event.getKeyCode()) {
                            case KeyEvent.KEYCODE_MEDIA_PLAY:
                                Utils.debug("Play");
                                lib.playPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                Utils.debug("Pause");
                                lib.playPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                                Utils.debug("Play/pause");
                                lib.playPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                Utils.debug("Prev");
                                lib.prev();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_NEXT:
                                Utils.debug("Next");
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
        mediaSession.setActive(true);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .build());

        registerBtA2dpReceiver();
    }

    private void stopPlayer() {
        if (null==lib) {
            return;
        }
        if (null!=wakeLock) {
            wakeLock.release();
            wakeLock = null;
        }
        unregisterBtA2dpReceiver();
        sendStatus(false);
        stopCometSubscription();
        stopTerminateTimer();
        abandonAudioFocus();
        lib.stopPlayer(this);
        if (null!=mediaSession) {
            mediaSession.setActive(false);
            mediaSession.release();
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
            stopCometSubscription();
        } else {
            stopTerminateTimer();
            startCometSubscription();
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

    private void startCometSubscription() {
        stopCometSubscription();
        if (null != lib && sendBtMetadata && (btA2dpConnected || androidAutoConnected)) {
            String serverUrl = lib.getServerUrl();
            String mac = lib.getPlayerMac();
            if (serverUrl != null && mac != null) {
                cometClient = new CometClient(serverUrl, mac, new CometClient.StatusListener() {
                    @Override
                    public void onPlayerStatus(PlayerStatus status) {
                        handler.post(() -> handleCometStatus(status));
                    }

                    @Override
                    public void onConnectionStateChanged(ConnectionState.State state) {
                        handler.post(() -> handleCometConnectionState(state));
                    }
                });
                cometClient.connect();
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBtA2dpReceiver() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (null!=adapter) {
                btA2dpConnected = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED;
            }
        } catch (SecurityException e) {
            Utils.debug("Cannot check A2DP state, assuming connected");
            btA2dpConnected = true;
        }
        btA2dpReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                    boolean wasConnected = btA2dpConnected;
                    btA2dpConnected = (state == BluetoothProfile.STATE_CONNECTED);
                    Utils.debug("A2DP state:"+state+", btA2dpConnected:"+btA2dpConnected);
                    if (btA2dpConnected && !wasConnected && null!=currentServerAddress) {
                        startCometSubscription();
                    } else if (!btA2dpConnected && wasConnected) {
                        stopCometSubscription();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btA2dpReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(btA2dpReceiver, filter);
        }
    }

    private void unregisterBtA2dpReceiver() {
        if (null!=btA2dpReceiver) {
            unregisterReceiver(btA2dpReceiver);
            btA2dpReceiver = null;
        }
    }

    private void stopCometSubscription() {
        if (null != cometClient) {
            cometClient.disconnect();
            cometClient = null;
            abandonAudioFocus();
            if (null != mediaSession) {
                handler.post(() -> {
                    if (null != mediaSession) {
                        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                                .build());
                        mediaSession.setActive(false);
                    }
                });
            }
        }
    }

    private void handleCometStatus(PlayerStatus status) {
        if (null == mediaSession) {
            return;
        }
        String title = status.title != null ? status.title : "";
        String artist = status.artist != null ? status.artist : "";
        String album = status.album != null ? status.album : "";
        String genre = status.genre != null ? status.genre : "";
        long durationMs = status.duration;
        long positionMs = status.time;
        int trackNum = status.trackNum;
        int playlistTracks = status.playlistTracks;

        if (showYear && status.year > 0 && !album.isEmpty()) {
            album = album + " (" + status.year + ")";
        }

        if (!title.equals(lastTitle) || !artist.equals(lastArtist) ||
                !album.equals(lastAlbum) || durationMs != lastDurationMs) {
            lastTitle = title;
            lastArtist = artist;
            lastAlbum = album;
            lastGenre = genre;
            lastDurationMs = durationMs;
            lastTrackNum = trackNum;
            lastNumTracks = playlistTracks;

            updateMediaSessionMetadata();

            String artworkUrl = resolveArtworkUrl(status.artworkUrl, status.coverId);
            if (!artworkUrl.isEmpty() && !artworkUrl.equals(lastArtworkUrl)) {
                lastArtworkUrl = artworkUrl;
                fetchArtwork(artworkUrl);
            }
        }

        int state;
        float speed;
        long reportedPosition;
        if (status.isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING;
            speed = 1.0f;
            reportedPosition = positionMs;
            requestAudioFocus();
        } else if (positionMs > 0) {
            state = PlaybackStateCompat.STATE_PAUSED;
            speed = 0f;
            reportedPosition = positionMs;
        } else {
            state = PlaybackStateCompat.STATE_STOPPED;
            speed = 0f;
            reportedPosition = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
            abandonAudioFocus();
        }

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO;

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, reportedPosition, speed)
                .setActions(actions)
                .build());

        if (!mediaSession.isActive() && state != PlaybackStateCompat.STATE_STOPPED) {
            mediaSession.setActive(true);
        }
    }

    private void handleCometConnectionState(ConnectionState.State state) {
        switch (state) {
            case CONNECTION_COMPLETED:
                Utils.info("CometD connected");
                break;
            case CONNECTION_FAILED:
            case DISCONNECTED:
                Utils.info("CometD disconnected: " + state);
                break;
            case REHANDSHAKING:
                Utils.info("CometD rehandshaking...");
                break;
        }
    }

    private String resolveArtworkUrl(String artworkUrl, String coverId) {
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            if (artworkUrl.startsWith("http")) {
                return artworkUrl;
            }
            String serverUrl = lib != null ? lib.getServerUrl() : null;
            if (serverUrl != null) {
                if (artworkUrl.startsWith("/")) {
                    return serverUrl + artworkUrl;
                }
                return serverUrl + "/" + artworkUrl;
            }
        }
        if (coverId != null && !coverId.isEmpty() && null != lib) {
            String serverUrl = lib.getServerUrl();
            if (null != serverUrl) {
                return serverUrl + "/music/" + coverId + "/cover.jpg";
            }
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        if (hasAudioFocus) {
            return;
        }
        if (null == audioManager) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (null == audioManager) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build();
            hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            hasAudioFocus = audioManager.requestAudioFocus(
                    audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (!hasAudioFocus || null == audioManager) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && null != audioFocusRequest) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        hasAudioFocus = false;
    }

    private void fetchArtwork(String url) {
        if (null == imageRequestQueue) {
            imageRequestQueue = Volley.newRequestQueue(this);
        }
        Utils.info("Fetching artwork: " + url);
        ImageRequest imageRequest = new ImageRequest(url,
                bitmap -> {
                    Utils.info("Artwork fetched: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    lastArtwork = bitmap;
                    updateMediaSessionMetadata();
                },
                512, 512, ImageView.ScaleType.CENTER_CROP, Bitmap.Config.RGB_565,
                error -> Utils.error("Failed to fetch artwork: " + url, error));
        imageRequestQueue.add(imageRequest);
    }

    private void updateMediaSessionMetadata() {
        if (null == mediaSession) {
            return;
        }
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
        if (!lastTitle.isEmpty()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, lastTitle);
        }
        if (!lastArtist.isEmpty()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist);
        }
        if (!lastAlbum.isEmpty()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, lastAlbum);
        }
        if (!lastGenre.isEmpty()) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, lastGenre);
        }
        if (lastDurationMs > 0) {
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastDurationMs);
        }
        if (lastTrackNum > 0) {
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, lastTrackNum);
        }
        if (lastNumTracks > 0) {
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, lastNumTracks);
        }
        if (null != lastArtwork) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, lastArtwork);
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, lastArtwork);
        }
        mediaSession.setMetadata(metaBuilder.build());
    }
}
