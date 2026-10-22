/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import static com.craigd.lmsmaterial.app.MainActivity.LMS_PASSWORD_KEY;
import static com.craigd.lmsmaterial.app.MainActivity.LMS_USERNAME_KEY;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import androidx.preference.PreferenceManager;

import com.craigd.lmsmaterial.app.cometd.CometClient;
import com.craigd.lmsmaterial.app.cometd.PlayerStatus;

import org.eclipse.jetty.util.B64Code;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class ControlService extends MediaBrowserServiceCompat {
    public static final String NO_NOTIFICATION = "none";
    public static final String BASIC_NOTIFICATION = "basic";
    public static final String FULL_NOTIFICATION = "full";
    private static final String NEXT_TRACK = ControlService.class.getCanonicalName() + ".NEXT_TRACK";
    private static final String PREV_TRACK = ControlService.class.getCanonicalName() + ".PREV_TRACK";
    private static final String PLAY_TRACK = ControlService.class.getCanonicalName() + ".PLAY_TRACK";
    private static final String PAUSE_TRACK = ControlService.class.getCanonicalName() + ".PAUSE_TRACK";
    private static final String QUIT_APP = ControlService.class.getCanonicalName() + ".QUIT";
    public static final int ACTIVE_PLAYER = 1;
    public static final int PLAYER_REFRESH = 2;
    public static final int CHECK_COMET_CONNECTION = 3;
    public static final int LIBRARY_CHANGED = 4;
    private static final String ACTION_POWER = "power";
    private static final String ACTION_QUIT = "quit";
    private static final int COMMAND_STATUS_REFRESH_MS = 500;
    private static final int MSG_ID = 1;
    private static final int COVER_TIMEOUT_MS = 10000;
    private static final int PLAYER_AVAILABILITY_RETRY_MS = 1000;
    private static final int PLAYER_AVAILABILITY_TIMEOUT_MS = 15000;
    private static final int RESTRICTED_PLAYER_RETRY_MS = 5000;
    private static final String[] PREV_COMMAND = {"button", "jump_rew"};
    private static final String[] PLAY_COMMAND = {"play"};
    private static final String[] PAUSE_COMMAND = {"pause", "1"};
    private static final String[] NEXT_COMMAND = {"playlist", "index", "+1"};
    private static String[] decVolumeCommand = {"mixer", "volume", "-5"};
    private static String[] incVolumeCommand = {"mixer", "volume", "+5"};
    private static final String[] POWER_COMMAND = {"power"};
    public static final String NOTIFICATION_CHANNEL_ID = "lms_control_service";
    private static final int MAX_COVER_SIZE = 512;

    private static boolean isRunning = false;

    private static int volStep = 5;

    public static void setVolumeStep(int step) {
        if (step!=volStep) {
            volStep = step;
            Utils.debug("Set volume step: " + step);
            decVolumeCommand = new String[] {"mixer", "volume", "-" + volStep};
            incVolumeCommand = new String[] {"mixer", "volume", "+" + volStep};
        }
    }
    public static boolean isActive() {
        return isRunning;
    }

    private JsonRpc rpc;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private MediaSessionCompat mediaSession;
    MediaSessionCompat.Callback mediaSessionCallback;
    private VolumeProviderCompat volumeProvider;
    private Boolean lastUseHardwareVolume;
    private String notificationType = NO_NOTIFICATION;
    private CometClient cometClient = null;
    private SharedPreferences prefs = null;
    private PlayerStatus lastStatus;
    private Handler handler;
    private ConnectionChangeListener connectionChangeListener;
    private final Messenger messenger = new Messenger(new IncomingHandler(this));
    private LmsBrowseHelper browseHelper;
    private ExecutorService browseExecutor;
    private String currentCoverUrl;
    private Bitmap currentCoverBitmap;
    private Bitmap fallbackBitmap;
    private long playerAvailabilityDeadline;
    private boolean playerWasUnavailable;
    private final Runnable playerAvailabilityCheck = this::checkPlayerAvailability;
    private final Runnable commandStatusRefresh = () -> {
        if (null!=cometClient && !Utils.isEmpty(MainActivity.activePlayer)) {
            cometClient.getPlayerStatus(MainActivity.activePlayer);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sharedPreferences, key) -> {
        if (AutoTabOrder.PREF_KEY.equals(key)) {
            notifyChildrenChanged(LmsBrowseHelper.ROOT_ID);
        } else if (MainActivity.CURRENT_PLAYER_ID_KEY.equals(key)) {
            activePlayerChanged();
        } else if (SettingsActivity.SINGLE_PLAYER_PREF_KEY.equals(key) || SettingsActivity.DEFAULT_PLAYER_PREF_KEY.equals(key)) {
            applyPlayerRestriction();
            startPlayerAvailabilityCheck();
            updateNotification();
            notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
        }
    };

    private static class IncomingHandler extends Handler {
        private final WeakReference<ControlService> serviceRef;
        public IncomingHandler(ControlService service) {
            super(Looper.getMainLooper());
            serviceRef = new WeakReference<>(service);
        }
        @Override
        public void handleMessage(@NonNull Message msg) {
            Utils.debug("Handle message " + msg.what);
            ControlService srv = serviceRef.get();
            if (null==srv) {
                super.handleMessage(msg);
                return;
            }
            if (msg.what == ACTIVE_PLAYER && null!=srv.notificationBuilder && null!=srv.notificationManager) {
                String[] vals = (String[]) msg.obj;
                Utils.debug("Set notification player name " + vals[1] + ", id:" + vals[0]);
                if (!PlayerAvailability.isAllowed(srv.restrictedPlayer(), vals[0], vals[1])) {
                    Utils.debug("Ignoring non-default player " + vals[0]);
                    return;
                }
                srv.cancelPlayerAvailabilityCheck();
                srv.notificationBuilder.setContentTitle(vals[1]);
                if (null!=srv.cometClient) {
                    srv.cometClient.setPlayer(vals[0]);
                    if (!srv.cometClient.isConnected()) {
                        srv.cometClient.connect();
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(srv.getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                srv.updateNotification();
            } else if (msg.what == PLAYER_REFRESH && null!=srv.notificationBuilder && null!=srv.notificationManager) {
                srv.resetBrowseHelper();
                if (null!=srv.cometClient && !srv.cometClient.isConnected()) {
                    Utils.debug("Connect comet client");
                    srv.cometClient.connect();
                }
                srv.createNotification();
            } else if (msg.what == CHECK_COMET_CONNECTION && null!=srv.cometClient) {
                srv.resetBrowseHelper();
                srv.cometClient.reconnectIfChanged();
            } else if (msg.what == LIBRARY_CHANGED) {
                srv.resetBrowseHelper();
                srv.notifyChildrenChanged(LmsBrowseHelper.ROOT_ID);
            } else {
                super.handleMessage(msg);
            }
        }
    }

    public static class ConnectionChangeListener extends BroadcastReceiver {
        private final ControlService service;

        ConnectionChangeListener(ControlService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (null==service) {
                return;
            }
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                service.handler.post(service::networkConnectivityChanged);
            }
        }
    }

    public ControlService() {
        handler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putBoolean("android.media.browse.SEARCH_SUPPORTED", true);
        return new BrowserRoot(LmsBrowseHelper.ROOT_ID, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        getBrowseExecutor().execute(() -> {
            try {
                List<MediaBrowserCompat.MediaItem> items = getBrowseHelper().loadChildren(parentId);
                result.sendResult(items);
                if (parentId.startsWith("player/")) {
                    handler.post(() -> {
                        if (null!=cometClient) {
                            cometClient.setPlayer(MainActivity.activePlayer);
                            if (!cometClient.isConnected()) {
                                cometClient.connect();
                            }
                        }
                        updateNotification();
                        notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
                    });
                } else if (parentId.startsWith("library/")) {
                    handler.post(() -> notifyChildrenChanged(LmsBrowseHelper.LIBRARIES_ID));
                }
            } catch (Exception e) {
                Utils.error("Failed to load children for: " + parentId, e);
                result.sendResult(Collections.emptyList());
            }
        });
    }

    @Override
    public void onSearch(@NonNull String query, Bundle extras, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        getBrowseExecutor().execute(() -> {
            try {
                List<MediaBrowserCompat.MediaItem> items = getBrowseHelper().search(query);
                result.sendResult(items);
            } catch (Exception e) {
                Utils.error("Failed to search: " + query, e);
                result.sendResult(Collections.emptyList());
            }
        });
    }

    private synchronized LmsBrowseHelper getBrowseHelper() {
        if (null==browseHelper) {
            browseHelper = new LmsBrowseHelper(getApplicationContext());
        }
        return browseHelper;
    }

    private synchronized ExecutorService getBrowseExecutor() {
        if (null==browseExecutor) {
            browseExecutor = Executors.newSingleThreadExecutor();
        }
        return browseExecutor;
    }

    private synchronized void resetBrowseHelper() {
        if (null!=browseHelper) {
            browseHelper.reset();
        }
    }

    private synchronized MediaSessionCompat.Callback getMediaSessionCallback() {
        if (null==mediaSessionCallback) {
            mediaSessionCallback = new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    Utils.debug("");
                    sendCommand(PLAY_COMMAND);
                }

                @Override
                public void onPause() {
                    Utils.debug("");
                    sendCommand(PAUSE_COMMAND);
                }

                @Override
                public void onSkipToNext() {
                    sendCommand(NEXT_COMMAND);
                }

                @Override
                public void onSkipToPrevious() {
                    sendCommand(PREV_COMMAND);
                }

                @Override
                public void onSeekTo(long pos) {
                    sendCommand(new String[]{"time", Double.toString(pos / 1000.0)});
                }

                @Override
                public void onPlayFromMediaId(String mediaId, Bundle extras) {
                    Utils.debug("playFromMediaId: " + mediaId);
                    if (getBrowseHelper().playMediaId(mediaId)) {
                        setBuffering();
                    }
                }

                @Override
                public void onPlayFromSearch(String query, Bundle extras) {
                    Utils.debug("playFromSearch: " + query);
                    if (Utils.isEmpty(query)) {
                        sendCommand(PLAY_COMMAND);
                        return;
                    }
                    getBrowseExecutor().execute(() -> {
                        for (MediaBrowserCompat.MediaItem item : getBrowseHelper().search(query)) {
                            if (item.isPlayable() && getBrowseHelper().playMediaId(item.getMediaId())) {
                                handler.post(ControlService.this::setBuffering);
                                return;
                            }
                        }
                        Utils.debug("No playable search result for: " + query);
                    });
                }

                @Override
                public void onCustomAction(String action, Bundle extras) {
                    if (ACTION_QUIT.equals(action)) {
                        quit();
                    } else if (ACTION_POWER.equals(action)) {
                        sendCommand(POWER_COMMAND);
                    }
                }
            };
        }
        return mediaSessionCallback;
    }

    private void setBuffering() {
        if (null!=mediaSession) {
            mediaSession.setPlaybackState(playbackStateBuilder(
                    PlaybackStateCompat.STATE_BUFFERING, 0, 0f).build());
        }
    }

    private PlaybackStateCompat.Builder playbackStateBuilder(int state, long position, float speed) {
        return new PlaybackStateCompat.Builder()
                .setState(state, position, speed)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .addCustomAction(ACTION_POWER, getString(R.string.power), android.R.drawable.ic_lock_power_off)
                .addCustomAction(ACTION_QUIT, getString(R.string.quit), R.drawable.ic_action_quit);
    }

    private void networkConnectivityChanged() {
        Utils.debug("");
        resetBrowseHelper();
        if (Utils.isNetworkConnected(this)) {
            cometClient.setPlayer(MainActivity.activePlayer);
            cometClient.connect();
        } else {
            lastStatus = null;
            cometClient.disconnect();
        }
        updateNotification();
    }

    public synchronized void updatePlayerStatus(PlayerStatus status) {
        if (null!=cometClient && cometClient.isConnected() && null!=status && (null==lastStatus || (status.id.equals(lastStatus.id) && !lastStatus.isPlaying && status.isPlaying))) {
            cometClient.getPlayerStatus(status.id);
        }
        lastStatus = status;
        handler.post(this::updateNotification);
        handler.post(this::updateMediaSession);
    }

    private void updateMediaSession() {
        if (null==mediaSession) {
            return;
        }
        configurePlaybackVolume();
        if (null==lastStatus || Utils.isEmpty(lastStatus.id) ||
                !lastStatus.id.equals(MainActivity.activePlayer)) {
            mediaSession.setPlaybackState(playbackStateBuilder(
                    PlaybackStateCompat.STATE_NONE, 0, 0f).build());
            String playerName = Utils.isEmpty(MainActivity.activePlayerName) ?
                    getString(R.string.no_player) : MainActivity.activePlayerName;
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                            getString(R.string.notification_meta_text))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, playerName)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, getFallback())
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, getFallback())
                    .build());
            return;
        }
        int state = "play".equals(lastStatus.mode) ? PlaybackStateCompat.STATE_PLAYING :
                ("pause".equals(lastStatus.mode) ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED);
        mediaSession.setPlaybackState(playbackStateBuilder(state, lastStatus.time,
                PlaybackStateCompat.STATE_PLAYING==state ? 1.0f : 0f).build());

        String coverUrl = lastStatus.cover;
        boolean coverChanged = (null==coverUrl && null!=currentCoverUrl) ||
                (null!=coverUrl && !coverUrl.equals(currentCoverUrl));
        if (coverChanged) {
            currentCoverUrl = coverUrl;
            currentCoverBitmap = null;
            if (!Utils.isEmpty(coverUrl)) {
                getBrowseExecutor().execute(() -> fetchCoverArt(coverUrl));
            }
        }

        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder();
        if (!Utils.isEmpty(lastStatus.title)) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_TITLE, lastStatus.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, lastStatus.title);
        }
        if (!Utils.isEmpty(lastStatus.artist)) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastStatus.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, lastStatus.artist);
        }
        if (!Utils.isEmpty(lastStatus.album)) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, lastStatus.album);
        }
        if (lastStatus.duration > 0) {
            meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastStatus.duration);
        }
        Bitmap artwork = null==currentCoverBitmap ? getFallback() : currentCoverBitmap;
        meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork);
        mediaSession.setMetadata(meta.build());
    }

    private synchronized Bitmap getFallback() {
        if (null==fallbackBitmap) {
            fallbackBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.notification_image);
        }
        return fallbackBitmap;
    }

    private void fetchCoverArt(String url) {
        try {
            URL coverUrl = new URL(url);
            URLConnection connection = coverUrl.openConnection();
            connection.setConnectTimeout(COVER_TIMEOUT_MS);
            connection.setReadTimeout(COVER_TIMEOUT_MS);
            SharedPreferences preferences = null==prefs ?
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext()) : prefs;
            ServerDiscovery.Server server = new ServerDiscovery.Server(
                    preferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
            int coverPort = coverUrl.getPort()<0 ? coverUrl.getDefaultPort() : coverUrl.getPort();
            if (null!=server.ip && server.ip.equalsIgnoreCase(coverUrl.getHost()) && server.port==coverPort) {
                String user = preferences.getString(LMS_USERNAME_KEY, "");
                String pass = preferences.getString(LMS_PASSWORD_KEY, "");
                if (!user.isEmpty() && !pass.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Basic " + B64Code.encode(user + ":" + pass));
                }
            }
            Bitmap bitmap;
            try (InputStream in = connection.getInputStream()) {
                bitmap = decodeCover(in);
            }
            if (null!=bitmap) {
                handler.post(() -> {
                    if (url.equals(currentCoverUrl)) {
                        currentCoverBitmap = bitmap;
                        updateMediaSession();
                    }
                });
            }
        } catch (Exception e) {
            Utils.debug("Failed to fetch cover art: " + e.getMessage());
        }
    }

    private static Bitmap decodeCover(InputStream stream) throws IOException {
        byte[] data;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            data = out.toByteArray();
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return null;
        }
        int sampleSize = 1;
        while (Math.max(opts.outWidth, opts.outHeight) / (sampleSize * 2) >= MAX_COVER_SIZE) {
            sampleSize *= 2;
        }
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (null==bitmap) {
            return null;
        }
        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largest > MAX_COVER_SIZE) {
            float scale = (float) MAX_COVER_SIZE / largest;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            bitmap = scaled;
        }
        return bitmap;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (null!=intent && SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        Utils.debug("");
        return messenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debug("");
        cometClient = new CometClient(this);
        mediaSession = new MediaSessionCompat(getApplicationContext(), "Lyrion");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(getMediaSessionCallback());
        mediaSession.setActive(true);
        mediaSession.setPlaybackState(playbackStateBuilder(
                PlaybackStateCompat.STATE_NONE, 0, 0f).build());
        setSessionToken(mediaSession.getSessionToken());
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).registerOnSharedPreferenceChangeListener(prefsListener);
        startForegroundService();
    }

    @Override
    public void onDestroy() {
        Utils.debug("");
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (null!=connectionChangeListener) {
            unregisterReceiver(connectionChangeListener);
            connectionChangeListener = null;
        }
        if (null!=cometClient) {
            cometClient.disconnect();
        }
        if (null!=browseExecutor) {
            browseExecutor.shutdownNow();
            browseExecutor = null;
        }
        cancelPlayerAvailabilityCheck();
        handler.removeCallbacks(commandStatusRefresh);
        browseHelper = null;
        if (null!=mediaSession) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        unregisterCallStateListener();
        stopForeground(true);
        isRunning = false;
        super.onDestroy();
    }

    private void sendCommand(String[] command) {
        if (Utils.isEmpty(MainActivity.activePlayer)) {
            return;
        }
        if (null==rpc) {
            rpc = new JsonRpc(getApplicationContext());
        }
        rpc.sendMessage(MainActivity.activePlayer, command);
        if (null!=cometClient && !cometClient.isConnected() && Utils.isNetworkConnected(this)) {
            cometClient.connect();
        }
        // Don't rely only on the CometD push for the result: its streaming connection can go silent for
        // up to a minute before being detected, leaving Android Auto showing a stale state.
        handler.removeCallbacks(commandStatusRefresh);
        handler.postDelayed(commandStatusRefresh, COMMAND_STATUS_REFRESH_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (null!=intent) {
            String action = intent.getAction();
            if (PREV_TRACK.equals(action)) {
                sendCommand(PREV_COMMAND);
            } else if (PLAY_TRACK.equals(action)) {
                sendCommand(PLAY_COMMAND);
            } else if (PAUSE_TRACK.equals(action)) {
                sendCommand(PAUSE_COMMAND);
            } else if (NEXT_TRACK.equals(action)) {
                sendCommand(NEXT_COMMAND);
            } else if (QUIT_APP.equals(action)) {
                quit();
            }
        }
        if (null!=mediaSession) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    private void startForegroundService() {
        Utils.debug("");
        notificationManager = NotificationManagerCompat.from(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        } else {
            notificationBuilder = new NotificationCompat.Builder(this);
        }
        initialiseCometClient();
        configurePlaybackVolume();
        createNotification();
        registerCallStateListener();
        isRunning = true;
    }

    private void initialiseCometClient() {
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        }
        if (Utils.isEmpty(MainActivity.activePlayer)) {
            MainActivity.activePlayer = prefs.getString(MainActivity.CURRENT_PLAYER_ID_KEY, null);
        }
        applyPlayerRestriction();
        new LocalPlayer(prefs, getApplicationContext()).autoStart(false);
        String setting = prefs.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, NO_NOTIFICATION);
        notificationType = setting;
        cometClient.setPlayer(MainActivity.activePlayer);
        cometClient.connect();
        startPlayerAvailabilityCheck();
        if (null==connectionChangeListener) {
            connectionChangeListener = new ConnectionChangeListener(this);
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectionChangeListener, filter);
        }
    }

    // The active player was changed elsewhere (phone UI or Android Auto's player list) - follow it.
    private void activePlayerChanged() {
        String player = MainActivity.activePlayer;
        if (null==cometClient || Utils.isEmpty(player)) {
            return;
        }
        Utils.debug("Active player changed to " + player);
        cancelPlayerAvailabilityCheck();
        cometClient.setPlayer(player);
        if (!cometClient.isConnected() && Utils.isNetworkConnected(this)) {
            cometClient.connect();
        }
        updateMediaSession();
        updateNotification();
        notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
    }

    private void applyPlayerRestriction() {
        String restricted = restrictedPlayer();
        if (null!=restricted && !restricted.equals(MainActivity.activePlayer)) {
            // Don't control a previously used player; the availability check selects the default one.
            MainActivity.activePlayer = null;
            MainActivity.activePlayerName = null;
            if (null!=cometClient) {
                cometClient.setPlayer(null);
            }
        }
    }

    private String restrictedPlayer() {
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        }
        return PlayerAvailability.restrictedPlayer(prefs);
    }

    private void startPlayerAvailabilityCheck() {
        if (Utils.isEmpty(MainActivity.activePlayer) && null==restrictedPlayer()) {
            return;
        }
        playerAvailabilityDeadline = SystemClock.elapsedRealtime() + PLAYER_AVAILABILITY_TIMEOUT_MS;
        playerWasUnavailable = false;
        handler.removeCallbacks(playerAvailabilityCheck);
        handler.post(playerAvailabilityCheck);
    }

    private void cancelPlayerAvailabilityCheck() {
        handler.removeCallbacks(playerAvailabilityCheck);
        playerAvailabilityDeadline = 0;
        playerWasUnavailable = false;
    }

    private void checkPlayerAvailability() {
        final String playerId = MainActivity.activePlayer;
        final String restricted = restrictedPlayer();
        if (Utils.isEmpty(playerId) && null==restricted) {
            return;
        }
        getBrowseExecutor().execute(() -> {
            PlayerAvailability.Player player = null;
            try {
                if (null==rpc) {
                    rpc = new JsonRpc(getApplicationContext());
                }
                JSONObject response = rpc.sendMessageSync("", new String[]{"players", "0", "100"}, 3000);
                JSONObject result = null==response ? null : response.optJSONObject("result");
                JSONArray players = null==result ? null : result.optJSONArray("players_loop");
                player = null==restricted
                        ? PlayerAvailability.findConnectedPlayer(players, playerId)
                        : PlayerAvailability.findConnectedAllowedPlayer(PlayerAvailability.parse(players), restricted);
            } catch (Exception e) {
                Utils.debug("Failed to check player availability: " + e.getMessage());
            }
            PlayerAvailability.Player availablePlayer = player;
            handler.post(() -> playerAvailabilityChecked(playerId, restricted, availablePlayer));
        });
    }

    private void playerAvailabilityChecked(String playerId, String restricted, PlayerAvailability.Player player) {
        if (!Objects.equals(playerId, MainActivity.activePlayer) || !Objects.equals(restricted, restrictedPlayer())) {
            return;
        }
        if (player != null) {
            boolean changed = !player.id.equals(MainActivity.activePlayer);
            if (changed) {
                Utils.debug("Selecting default player " + player.name + ", id:" + player.id);
                MainActivity.activePlayer = player.id;
                prefs.edit().putString(MainActivity.CURRENT_PLAYER_ID_KEY, player.id).apply();
            }
            MainActivity.activePlayerName = player.name;
            if (playerWasUnavailable || changed) {
                cometClient.setPlayer(player.id);
                cometClient.reconnect();
            }
            updateMediaSession();
            if (changed) {
                updateNotification();
                notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
            }
            return;
        }
        playerWasUnavailable = true;
        if (SystemClock.elapsedRealtime() < playerAvailabilityDeadline) {
            handler.postDelayed(playerAvailabilityCheck, PLAYER_AVAILABILITY_RETRY_MS);
        } else if (null!=restricted) {
            // The default player may start (e.g. Squeezelite on car connect) at any time - keep looking.
            handler.postDelayed(playerAvailabilityCheck, RESTRICTED_PLAYER_RETRY_MS);
        }
    }

    private void configurePlaybackVolume() {
        boolean useHardwareVolume = prefs.getBoolean(SettingsActivity.HARDWARE_VOLUME_PREF_KEY, true);
        if (lastUseHardwareVolume==null || lastUseHardwareVolume!=useHardwareVolume) {
            lastUseHardwareVolume = useHardwareVolume;
            if (useHardwareVolume) {
                volumeProvider = null;
                mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
            } else {
                volumeProvider = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
                        100, null!=lastStatus && lastStatus.volume>=0 ? lastStatus.volume : 50) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        Utils.debug(""+direction);
                        if (direction > 0) {
                            sendCommand(incVolumeCommand);
                        } else if (direction < 0) {
                            sendCommand(decVolumeCommand);
                        }
                    }

                    @Override
                    public void onSetVolumeTo(int volume) {
                        Utils.debug(""+volume);
                        sendCommand(new String[]{"mixer", "volume", Integer.toString(volume)});
                        setCurrentVolume(volume);
                    }
                };
                mediaSession.setPlaybackToRemote(volumeProvider);
            }
        } else if (null!=volumeProvider && null!=lastStatus && lastStatus.volume>=0) {
            volumeProvider.setCurrentVolume(lastStatus.volume);
        }
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

    @NonNull
    private PendingIntent getPendingIntent(@NonNull String action) {
        Intent intent = new Intent(this, ControlService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private MediaStyle getMediaStyle() {
        MediaStyle mediaStyle = new MediaStyle().setMediaSession(mediaSession.getSessionToken());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (FULL_NOTIFICATION.equals(notificationType)) {
                mediaStyle.setShowActionsInCompactView(0, 1, 2);
            } else {
                mediaStyle.setShowActionsInCompactView(1, 2, 3);
            }
        }
        return mediaStyle;
    }

    @SuppressLint("MissingPermission")
    private synchronized Notification updateNotification() {
        Utils.debug("");
        if (!Utils.notificationAllowed(this, NOTIFICATION_CHANNEL_ID)) {
            return null;
        }
        try {
            boolean isFull = FULL_NOTIFICATION.equals(notificationType);
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
            boolean statusValid = false;
            if (null!=lastStatus && lastStatus.id.equals(MainActivity.activePlayer)) {
                statusValid = true;
            } else {
                lastStatus = null;
            }
            notificationBuilder
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_mono_icon)
                    .setContentTitle(null==MainActivity.activePlayerName || MainActivity.activePlayerName.isEmpty() ? getResources().getString(R.string.no_player) : MainActivity.activePlayerName)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setVibrate(null)
                    .setSound(null)
                    .setShowWhen(false)
                    .setStyle(getMediaStyle())
                    .setChannelId(NOTIFICATION_CHANNEL_ID);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notificationBuilder.clearActions();
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_prev, "Previous", getPendingIntent(PREV_TRACK)));
                if (!statusValid) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                    if (!isFull) {
                        notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                    }
                } else if (lastStatus.isPlaying) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                } else {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                }
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_next, "Next", getPendingIntent(NEXT_TRACK)));
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_action_quit, getString(R.string.quit), getPendingIntent(QUIT_APP)));
                notificationBuilder.setSubText(statusValid ? lastStatus.display() : getResources().getString(R.string.notification_meta_text));
            } else {
                notificationBuilder.clearActions();
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_prev, "Previous", getPendingIntent(PREV_TRACK)));
                if (statusValid && lastStatus.isPlaying) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                } else {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                }
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_next, "Next", getPendingIntent(NEXT_TRACK)));
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_action_quit, getString(R.string.quit), getPendingIntent(QUIT_APP)));
                notificationBuilder.setSubText(statusValid ? lastStatus.display() : getResources().getString(R.string.notification_meta_text));
            }

            Notification notification = notificationBuilder.build();
            Utils.debug("Build notification.");
            notificationManager.notify(MSG_ID, notification);
            return notification;
        } catch (Exception e) {
            Utils.error("Failed to create control notification", e);
        }
        return null;
    }

    public void quit() {
        stopForegroundService();
        new LocalPlayer(prefs, this).autoStop();
        System.exit(0);
    }

    private void createNotification() {
        Utils.debug("");
        Notification notification = updateNotification();
        if (null==notification) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Utils.debug("startForegroundService");
            startForegroundService(new Intent(this, ControlService.class));
        } else {
            Utils.debug("startService");
            startService(new Intent(this, ControlService.class));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Utils.debug("ServiceCompat.startForeground");
            ServiceCompat.startForeground(this, MSG_ID, notification, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
        }
    }

    private void stopForegroundService() {
        Utils.debug("");
        if (null!=mediaSession) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
        unregisterCallStateListener();
        stopSelf();
        isRunning = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static abstract class CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        abstract public void onCallStateChanged(int state);
    }

    private boolean callStateListenerRegistered = false;
    private PhoneStateHandler phoneStateHandler = null;
    private final CallStateListener callStateListener = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
            new CallStateListener() {
                @Override
                public void onCallStateChanged(int state) {
                    if (null==phoneStateHandler) {
                        phoneStateHandler = new PhoneStateHandler();
                    }
                    phoneStateHandler.handle(getApplicationContext(), state);
                }
            }
            : null;
    private final PhoneStateListener phoneStateListener = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) ?
            new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    if (null==phoneStateHandler) {
                        phoneStateHandler = new PhoneStateHandler();
                    }
                    phoneStateHandler.handle(getApplicationContext(), state);
                }
            }
            : null;

    private void registerCallStateListener() {
        Utils.debug("callStateListenerRegistered:" + callStateListenerRegistered);
        if (!callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    Utils.debug("Calling registerTelephonyCallback");
                    telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
                } else {
                    Utils.error("Permission not granted");
                    return;
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            callStateListenerRegistered = true;
        }
    }

    private void unregisterCallStateListener() {
        Utils.debug("callStateListenerRegistered:" + callStateListenerRegistered);
        if (callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(callStateListener);
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
            callStateListenerRegistered = false;
        }
    }
}
