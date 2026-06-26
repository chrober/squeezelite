/**
 * Adapted from LMS-Material-App / android-squeezer
 * Apache-2.0 license
 */

package org.lyrion.squeezelite.cometd;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.B64Code;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.lyrion.squeezelite.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CometClient {
    public interface StatusListener {
        void onPlayerStatus(PlayerStatus status);
        void onConnectionStateChanged(ConnectionState.State state);
    }

    private final ConnectionState connectionState;
    private SlimClient bayeuxClient;
    private HttpClient httpClient;
    private final String playerMac;
    private String subscribedPlayer = null;
    private final String serverAddress;
    private final int serverPort;
    private final String serverUser;
    private final String serverPass;
    private final Handler backgroundHandler;
    private final StatusListener listener;
    private int handShakeFailures = 0;

    private static final int MAX_HANDSHAKE_FAILURES = 5;
    private static final String PLAYER_STATUS_TAGS = "tags:acdglKNty";
    private static final int HANDSHAKE_TIMEOUT = 4 * 1000;
    private static final int MSG_HANDSHAKE_TIMEOUT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_RECONNECT = 3;
    private static final int MSG_SUBSCRIBE = 4;
    private static final int MSG_PUBLISH = 5;

    private class PublishListener implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (!message.isSuccessful()) {
                if (Message.RECONNECT_HANDSHAKE_VALUE.equals(getAdviceAction(message.getAdvice()))) {
                    Utils.info("rehandshake");
                    bayeuxClient.rehandshake();
                } else {
                    Map<String, Object> failure = getRecord(message, "failure");
                    Exception exception = (failure != null) ? (Exception) failure.get("exception") : null;
                    Utils.warn(channel + ": " + message.getJSON(), exception);
                }
            }
        }
    }

    private static class PublishMessage {
        final Object request;
        final String channel;
        final String responseChannel;
        final PublishListener publishListener;

        private PublishMessage(Object request, String channel, String responseChannel, PublishListener publishListener) {
            this.request = request;
            this.channel = channel;
            this.responseChannel = responseChannel;
            this.publishListener = publishListener;
        }
    }

    private class MessageHandler extends Handler {
        MessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(android.os.Message msg) {
            Utils.debug("CometClient msg:" + msg.what);
            switch (msg.what) {
                case MSG_HANDSHAKE_TIMEOUT:
                    Utils.warn("Handshake timeout: " + connectionState);
                    disconnectFromServer();
                    notifyConnectionState(ConnectionState.State.DISCONNECTED);
                    break;
                case MSG_DISCONNECT:
                    removeCallbacksAndMessages(null);
                    disconnectFromServer();
                    break;
                case MSG_RECONNECT:
                    disconnectFromServer();
                    doConnect();
                    break;
                case MSG_SUBSCRIBE:
                    doSubscribePlayer();
                    break;
                case MSG_PUBLISH: {
                    PublishMessage message = (PublishMessage) msg.obj;
                    doPublishMessage(message.request, message.channel, message.responseChannel, message.publishListener);
                    break;
                }
                default:
                    break;
            }
        }
    }

    public CometClient(String serverUrl, String playerMac, StatusListener listener) {
        this(serverUrl, playerMac, "", "", listener);
    }

    public CometClient(String serverUrl, String playerMac, String user, String pass, StatusListener listener) {
        this.listener = listener;
        this.playerMac = playerMac;
        this.serverUser = user != null ? user : "";
        this.serverPass = pass != null ? pass : "";

        String address = "127.0.0.1";
        int port = 9000;
        try {
            java.net.URL url = new java.net.URL(serverUrl);
            address = url.getHost();
            port = url.getPort() > 0 ? url.getPort() : 9000;
        } catch (Exception e) {
            Utils.error("Failed to parse server URL: " + serverUrl, e);
        }
        this.serverAddress = address;
        this.serverPort = port;

        connectionState = new ConnectionState();
        HandlerThread handlerThread = new HandlerThread("CometClient");
        handlerThread.start();
        backgroundHandler = new MessageHandler(handlerThread.getLooper());
    }

    public synchronized boolean isConnected() {
        return connectionState.isConnected() && null != bayeuxClient;
    }

    public synchronized void connect() {
        Utils.debug("CometD connect");
        connectionState.setConnectionState(ConnectionState.State.CONNECTION_STARTED);
        notifyConnectionState(ConnectionState.State.CONNECTION_STARTED);
        backgroundHandler.post(this::doConnect);
    }

    private void doConnect() {
        httpClient = new HttpClient();
        try {
            httpClient.start();
        } catch (Exception e) {
            connectionState.setConnectionError(ConnectionState.Error.START_CLIENT_ERROR);
            notifyConnectionState(ConnectionState.State.CONNECTION_FAILED);
            return;
        }

        String url = "http://" + serverAddress + ":" + serverPort + "/cometd";
        Utils.debug("CometD URL: " + url);
        ClientTransport clientTransport = new HttpStreamingTransport(url, null, httpClient) {
            @Override
            protected void customize(Request request) {
                if (!serverUser.isEmpty() && !serverPass.isEmpty()) {
                    request.header(HttpHeader.AUTHORIZATION, "Basic " + B64Code.encode(serverUser + ":" + serverPass));
                }
            }
        };
        bayeuxClient = new SlimClient(connectionState, url, clientTransport);
        bayeuxClient.addExtension(new BayeuxExtension());
        backgroundHandler.sendEmptyMessageDelayed(MSG_HANDSHAKE_TIMEOUT, HANDSHAKE_TIMEOUT);
        bayeuxClient.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener) (channel, message) -> {
            handShakeFailures = message.isSuccessful() ? 0 : (handShakeFailures + 1);
            Utils.debug("Handshake OK: " + message.isSuccessful() + ", canRehandshake: " + connectionState.canRehandshake() + ", failures:" + handShakeFailures);
            if (message.isSuccessful()) {
                onConnected();
            } else if (handShakeFailures >= MAX_HANDSHAKE_FAILURES) {
                Utils.error("Too many handshake errors, aborting");
                handShakeFailures = 0;
                try {
                    clientTransport.abort();
                    try {
                        if (httpClient != null) {
                            httpClient.stop();
                            httpClient = null;
                        }
                    } catch (Exception e) {
                        Utils.error("Failed to stop HTTP client", e);
                    }
                    bayeuxClient.stop();
                    bayeuxClient = null;
                } catch (Exception e) {
                    Utils.error("Aborting", e);
                }
                connectionState.setConnectionState(ConnectionState.State.DISCONNECTED);
                notifyConnectionState(ConnectionState.State.DISCONNECTED);
            } else if (!connectionState.canRehandshake()) {
                handShakeFailures = 0;
                Map<String, Object> failure = getRecord(message, "failure");
                Message failedMessage = (failure != null) ? (Message) failure.get("message") : message;
                if (failedMessage != null && getAdviceAction(failedMessage.getAdvice()) == null) {
                    Utils.warn("Unsuccessful message on handshake channel: " + message.getJSON());
                    disconnect();
                }
            }
        });
        bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener) (channel, message) -> {
            Utils.debug("Connect OK? " + message.isSuccessful());
            if (!message.isSuccessful() && (getAdviceAction(message.getAdvice()) == null)) {
                Utils.warn("Unsuccessful message on connect channel: " + message.getJSON());
                disconnect();
            }
        });
        bayeuxClient.handshake();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getRecord(Map<String, Object> record, String name) {
        Object rec = record.get(name);
        return rec instanceof Map ? (Map<String, Object>) rec : null;
    }

    private static String getAdviceAction(Map<String, Object> advice) {
        if (advice != null && advice.containsKey(Message.RECONNECT_FIELD)) {
            return (String) advice.get(Message.RECONNECT_FIELD);
        }
        return null;
    }

    public void disconnect() {
        Utils.debug("CometD disconnect");
        if (bayeuxClient != null && connectionState.isConnected()) {
            backgroundHandler.sendEmptyMessage(MSG_DISCONNECT);
        }
        connectionState.setConnectionState(ConnectionState.State.DISCONNECTED);
        notifyConnectionState(ConnectionState.State.DISCONNECTED);
    }

    private synchronized void disconnectFromServer() {
        if (bayeuxClient != null) {
            String[] channels = new String[]{Channel.META_HANDSHAKE, Channel.META_CONNECT};
            for (String channelId : channels) {
                ClientSessionChannel channel = bayeuxClient.getChannel(channelId);
                for (ClientSessionChannel.ClientSessionChannelListener l : channel.getListeners()) {
                    channel.removeListener(l);
                }
                channel.unsubscribe();
            }
            bayeuxClient.disconnect();
            bayeuxClient = null;
        }
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                Utils.error("Failed to stop HTTP client", e);
            }
            httpClient = null;
        }
        subscribedPlayer = null;
    }

    private synchronized void onConnected() {
        Utils.info("CometD connected");
        subscribedPlayer = null;
        connectionState.setConnectionState(ConnectionState.State.CONNECTION_COMPLETED);
        notifyConnectionState(ConnectionState.State.CONNECTION_COMPLETED);
        bayeuxClient.getChannel("/" + bayeuxClient.getId() + "/slim/playerstatus/*").subscribe(this::handlePlayerStatusMessage);
        backgroundHandler.sendEmptyMessage(MSG_SUBSCRIBE);
        backgroundHandler.removeMessages(MSG_HANDSHAKE_TIMEOUT);
    }

    private void doSubscribePlayer() {
        subscribePlayer(playerMac);
    }

    private void publishMessage(Object request, final String channel, final String responseChannel, final PublishListener publishListener) {
        if (backgroundHandler.getLooper() == Looper.myLooper()) {
            doPublishMessage(request, channel, responseChannel, publishListener);
        } else {
            PublishMessage publishMessage = new PublishMessage(request, channel, responseChannel, publishListener);
            android.os.Message message = backgroundHandler.obtainMessage(MSG_PUBLISH, publishMessage);
            backgroundHandler.sendMessage(message);
        }
    }

    private void doPublishMessage(Object request, String channel, String responseChannel, PublishListener publishListener) {
        Map<String, Object> data = new HashMap<>();
        if (request != null) {
            data.put("request", request);
            data.put("response", responseChannel);
        } else {
            data.put("unsubscribe", responseChannel);
        }
        bayeuxClient.getChannel(channel).publish(data, publishListener);
    }

    private void subscribePlayer(String id) {
        Utils.debug("Subscribe ID:" + id + ", connected:" + connectionState.isConnected());
        if (null != id && !id.isEmpty() && connectionState.isConnected() && !id.equals(subscribedPlayer)) {
            List<Object> req = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            params.add("status");
            params.add("-");
            params.add("1");
            params.add("subscribe:0");
            params.add(PLAYER_STATUS_TAGS);
            req.add(id);
            req.add(params);
            publishMessage(req, "/slim/subscribe", "/" + bayeuxClient.getId() + "/slim/playerstatus/" + id, new PublishListener() {
                @Override
                public void onMessage(ClientSessionChannel channel, Message message) {
                    super.onMessage(channel, message);
                    if (message.isSuccessful()) {
                        subscribedPlayer = id;
                        Utils.info("Subscribed to player: " + id);
                        requestStatus();
                    }
                }
            });
        }
    }

    public void requestStatus() {
        if (null == playerMac || playerMac.isEmpty() || !connectionState.isConnected() || null == bayeuxClient) {
            return;
        }
        List<Object> req = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add("status");
        params.add("-");
        params.add("1");
        params.add(PLAYER_STATUS_TAGS);
        req.add(playerMac);
        req.add(params);
        publishMessage(req, "/slim/subscribe", "/" + bayeuxClient.getId() + "/slim/playerstatus/" + playerMac, new PublishListener());
    }

    private float parseFloat(Object val) {
        if (null == val) {
            return 0.0f;
        }
        if (val instanceof Float) {
            return (Float) val;
        }
        if (val instanceof Double) {
            return ((Double) val).floatValue();
        }
        if (val instanceof String) {
            try {
                return Float.parseFloat((String) val);
            } catch (NumberFormatException ignored) {
            }
        }
        if (val instanceof Number) {
            return ((Number) val).floatValue();
        }
        return 0.0f;
    }

    private int parseInt(Object val) {
        if (null == val) {
            return 0;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private synchronized void handlePlayerStatusMessage(ClientSessionChannel channel, Message message) {
        String[] parts = message.getChannel().split("/");
        String playerId = parts[parts.length - 1];
        Utils.verbose("CometD status for " + playerId);

        if (!Objects.equals(playerId, playerMac)) {
            return;
        }

        Map<String, Object> messageData = message.getDataAsMap();
        String mode = (String) messageData.get("mode");
        Object[] playlist_loop = (Object[]) messageData.get("playlist_loop");

        PlayerStatus status = new PlayerStatus();
        status.id = playerId;
        status.timestamp = SystemClock.elapsedRealtime();
        status.mode = mode != null ? mode : "";
        status.isPlaying = "play".equals(mode);
        status.hasTime = messageData.containsKey("time");
        status.time = "stop".equals(mode) ? 0 : (long) (parseFloat(messageData.get("time")) * 1000.0f);
        status.playlistTracks = parseInt(messageData.get("playlist_tracks"));

        if (playlist_loop != null && playlist_loop.length > 0) {
            status.hasTrack = true;
            Map<String, Object> track = (Map<String, Object>) playlist_loop[0];
            status.title = (String) track.get("title");
            status.artist = (String) track.get("artist");
            status.album = (String) track.get("album");
            status.genre = (String) track.get("genre");
            status.duration = (long) (parseFloat(track.get("duration")) * 1000.0f);
            status.trackNum = parseInt(track.get("tracknum"));
            status.year = parseInt(track.get("year"));
            status.artworkUrl = (String) track.get("artwork_url");
            status.coverId = (String) track.get("coverid");

            String remoteTitle = (String) track.get("remote_title");
            if (remoteTitle != null && !remoteTitle.isEmpty()) {
                if (!remoteTitle.startsWith("http") || status.title == null || status.title.isEmpty()) {
                    status.title = remoteTitle;
                }
            }
        }

        listener.onPlayerStatus(status);
    }

    private void notifyConnectionState(ConnectionState.State state) {
        listener.onConnectionStateChanged(state);
    }
}
