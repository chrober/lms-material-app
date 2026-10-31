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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

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
import java.util.ArrayList;
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
    private static final String ACTION_POWER = "power";
    private static final String ACTION_QUIT = "quit";
    private static final String ACTION_RATE = "rate";
    private static final String ACTION_SHUFFLE = "shuffle";
    private static final int RATING_REFRESH_MS = 1000;
    private static final int COMMAND_STATUS_REFRESH_MS = 500;
    private static final int PLAY_STATUS_REFRESH_MS = 2000;
    private static final int RESUME_TOLERANCE_MS = 3000;
    private static final int MSG_ID = 1;
    private static final int COVER_TIMEOUT_MS = 10000;
    private static final int PLAYER_AVAILABILITY_RETRY_MS = 1000;
    private static final int PLAYER_AVAILABILITY_TIMEOUT_MS = 15000;
    private static final int RESTRICTED_PLAYER_RETRY_MS = 5000;
    private static final String ANDROID_AUTO_PKG = "com.google.android.projection.gearhead";
    // Android Auto may ask for the root several times while starting - only react once (reset on disconnect).
    private static final long AUTO_CONNECT_DEBOUNCE_MS = 30000;
    // With "Ignore the car's play on connect", a play from Android Auto this soon after the car connects is dropped
    // (seen 2-13 s after the connect, with Android Auto's "Start music automatically" off).
    private static final long CONNECT_PLAY_IGNORE_MS = 15000;
    // "Resume after reconnecting": the state saved when the car disconnected (a CarResume), until it is resumed.
    private static final String CAR_RESUME_STATE_PREF_KEY = "auto_resume_state";
    // How long to wait for the local player app to show up in LMS after starting it.
    private static final int AUTO_PLAYER_TIMEOUT_MS = 20000;
    // On car connect, restart the player app if the default player is not connected after this long...
    private static final int PLAYER_RESTART_MS = 10000;
    // ...but only this many times.
    private static final int MAX_PLAYER_RESTARTS = 5;
    // Time for the player app to close its connection before it is started again.
    private static final int PLAYER_RESTART_DELAY_MS = 1500;
    // After switching this phone's player on, restart the player app if it isn't on after this long.
    private static final int POWER_ON_CHECK_MS = 3000;
    // Android Auto's connection state (what androidx.car.app.connection.CarConnection reads). Android Auto
    // may reuse its connection to the browse service from the previous drive, so onGetRoot() is not
    // always called when the car connects.
    private static final Uri CAR_CONNECTION_URI = Uri.parse("content://androidx.car.app.connection");
    private static final String CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";
    private static final String CAR_CONNECTION_STATE = "CarConnectionState";
    private static final int CAR_CONNECTION_PROJECTION = 2;
    // Android Auto reports only single taps on custom actions, so two taps this close together
    // on the rating button count as a double tap, which resets the rating to unrated.
    private static final long RATE_DOUBLE_TAP_MS = 400;
    private static final String[] PREV_COMMAND = {"button", "jump_rew"};
    private static final String[] PLAY_COMMAND = {"play"};
    private static final String[] PAUSE_COMMAND = {"pause", "1"};
    private static final String[] NEXT_COMMAND = {"playlist", "index", "+1"};
    private static String[] decVolumeCommand = {"mixer", "volume", "-5"};
    private static String[] incVolumeCommand = {"mixer", "volume", "+5"};
    private static final String[] POWER_ON_COMMAND = {"power", "1"};
    private static final String[] POWER_OFF_COMMAND = {"power", "0"};
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
    private long lastAutoConnect = 0;
    private long autoPlayerDeadline;
    private boolean autoPlayerStarted;
    private final Runnable autoPlayerCheck = this::checkAutoPlayer;
    // The player app was started for the player being waited for: switch it on once it connects, and
    // restart the player app if it doesn't (set on car connect with "Only control default", and by Power)
    private boolean playerStartPending;
    // The play to do (on the browse executor) once the started player app's player connects
    private Runnable playAfterStart;
    private int playerRestarts;
    private long nextPlayerRestart;
    // Position saved when Power switched the active player off, if LMS is set to pause it then. A Squeezelite
    // player disconnects when switched off, so LMS stops it and forgets the position: seek back once it plays again.
    private String resumePlayer;
    private long resumeTrackId;
    private long resumeTime;
    // "Resume after reconnecting": loaded on car connect, played once the player is on if it hasn't moved on since
    // (cleared then either way). Main thread only.
    private CarResume carResume;
    private boolean carResumeChecking;
    private boolean carResumePlay; // a play arrived during the check
    private boolean carConnected;
    private boolean carStateConnected; // last state read from Android Auto's car connection provider
    private BroadcastReceiver carConnectionReceiver;
    private final Runnable commandStatusRefresh = () -> {
        if (null!=cometClient && !Utils.isEmpty(MainActivity.activePlayer)) {
            cometClient.getPlayerStatus(MainActivity.activePlayer);
        }
    };
    private Boolean ratingsSupported = null; // null until checked whether Ratings Light is installed
    private long lastRateTap = 0;
    private boolean checkingRatingsSupport = false;
    // Play queue currently published to the media session (a window of the LMS playlist)
    private String queuePlayer = null;
    private double queueTimestamp = -1;
    private int queueStart = -1;
    private int queueTotal = 0;
    private boolean queueLoading = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sharedPreferences, key) -> {
        if (AutoTabOrder.PREF_KEY.equals(key)) {
            notifyChildrenChanged(LmsBrowseHelper.ROOT_ID);
        } else if (SettingsActivity.RESUME_ON_CONNECT_PREF_KEY.equals(key)) {
            if (!sharedPreferences.getBoolean(key, false)) {
                handler.post(() -> {
                    clearCarResumeState();
                    sharedPreferences.edit().remove(CAR_RESUME_STATE_PREF_KEY).apply();
                });
            }
        } else if (LmsBrowseHelper.SHOW_UNPLAYED_PREF_KEY.equals(key)) {
            notifyChildrenChanged(LmsBrowseHelper.RELEASES_GROUP_ID);
            getBrowseHelper().prefetchPlayCounts();
        } else if (LmsBrowseHelper.ACTIVE_LIBRARY_PREF_KEY.equals(key)) {
            // Also set from the phone UI (MainActivity syncs the two both ways).
            getBrowseExecutor().execute(() -> {
                getBrowseHelper().clearLibraryCaches();
                handler.post(() -> selectionChanged(false));
            });
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
        if (ANDROID_AUTO_PKG.equals(clientPackageName)) {
            androidAutoConnected();
        }
        return new BrowserRoot(LmsBrowseHelper.ROOT_ID, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        getBrowseExecutor().execute(() -> {
            try {
                List<MediaBrowserCompat.MediaItem> items = getBrowseHelper().loadChildren(parentId);
                result.sendResult(items);
            } catch (Exception e) {
                Utils.error("Failed to load children for: " + parentId, e);
                result.sendResult(Collections.emptyList());
            }
        });
    }

    private void selectionChanged(boolean player) {
        if (player) {
            if (null!=cometClient) {
                cometClient.setPlayer(MainActivity.activePlayer);
                if (!cometClient.isConnected()) {
                    cometClient.connect();
                }
            }
            updateNotification();
            notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
        } else {
            notifyChildrenChanged(LmsBrowseHelper.LIBRARIES_ID);
            notifyChildrenChanged(LmsBrowseHelper.ROOT_ID); // tab shows the library's name
        }
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
        ratingsSupported = null;
    }

    // The app that sent the current media session command, e.g. Android Auto or Bluetooth (a car's play button).
    private String controllerName() {
        androidx.media.MediaSessionManager.RemoteUserInfo info = controllerInfo();
        return null==info ? "?" : (info.getPackageName() + " (uid " + info.getUid() + ")");
    }

    private androidx.media.MediaSessionManager.RemoteUserInfo controllerInfo() {
        MediaSessionCompat session = mediaSession;
        if (null==session) {
            return null;
        }
        try {
            return session.getCurrentControllerInfo();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The car (or Android Auto) sends play a few seconds after connecting, even with Android Auto's "Start music
     * automatically" off. With the setting on, a play from Android Auto that soon after the connect is ignored.
     */
    private boolean isConnectAutoPlay() {
        if (!prefs.getBoolean(SettingsActivity.IGNORE_CONNECT_PLAY_PREF_KEY, false) || 0==lastAutoConnect ||
                SystemClock.elapsedRealtime() - lastAutoConnect > CONNECT_PLAY_IGNORE_MS) {
            return false;
        }
        androidx.media.MediaSessionManager.RemoteUserInfo info = controllerInfo();
        return null!=info && ANDROID_AUTO_PKG.equals(info.getPackageName());
    }

    private synchronized MediaSessionCompat.Callback getMediaSessionCallback() {
        if (null==mediaSessionCallback) {
            mediaSessionCallback = new MediaSessionCompat.Callback() {
                // Media keys (e.g. a car's buttons) arrive here before onPlay/onPause; a tap on Android Auto's
                // screen calls those directly. Logged to tell the car's own play (after it is switched off and on
                // with Android Auto still connected) from the driver's.
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                    KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (null!=event) {
                        Utils.debug(KeyEvent.keyCodeToString(event.getKeyCode()) + " " +
                                (KeyEvent.ACTION_DOWN==event.getAction() ? "down" : "up") + ", repeat:" +
                                event.getRepeatCount() + ", device:" + event.getDeviceId() + ", source:0x" +
                                Integer.toHexString(event.getSource()) + ", from " + controllerName());
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent);
                }

                @Override
                public void onPlay() {
                    Utils.debug("from " + controllerName());
                    if (isConnectAutoPlay()) {
                        Utils.debug("Ignoring play on car connect");
                        return;
                    }
                    playOnPlayer(() -> {
                        if (null!=carResume) {
                            checkCarResume(true);
                        } else {
                            playCurrent();
                        }
                    });
                }

                @Override
                public void onPause() {
                    Utils.debug("from " + controllerName());
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
                public void onSkipToQueueItem(long id) {
                    // Queue item IDs are LMS playlist indexes
                    clearCarResume("queue item played");
                    playOnPlayer(() -> sendCommand(new String[]{"playlist", "index", Long.toString(id)}));
                }

                @Override
                public void onPlayFromMediaId(String mediaId, Bundle extras) {
                    Utils.debug("playFromMediaId: " + mediaId);
                    if (mediaId.startsWith("player/") || mediaId.startsWith("library/")) {
                        // A tapped player or library entry: select it, nothing is played.
                        boolean player = mediaId.startsWith("player/");
                        getBrowseExecutor().execute(() -> {
                            if (getBrowseHelper().selectMediaId(mediaId)) {
                                handler.post(() -> selectionChanged(player));
                            }
                        });
                        return;
                    }
                    if (LmsBrowseHelper.isQueueAdd(mediaId)) {
                        // Adding counts as playing (with "Only control default" the player is switched on, or
                        // its app started, first), as adding to an empty queue starts playback.
                        getBrowseExecutor().execute(() -> switchOnForPlay(() ->
                                handler.post(getBrowseHelper().addToQueue(mediaId)
                                        ? ControlService.this::playbackRequested : commandStatusRefresh)));
                        return;
                    }
                    clearCarResume("played from browse");
                    getBrowseExecutor().execute(() -> switchOnForPlay(() -> {
                        if (getBrowseHelper().playMediaId(mediaId)) {
                            handler.post(ControlService.this::playbackRequested);
                        }
                    }));
                }

                @Override
                public void onPlayFromSearch(String query, Bundle extras) {
                    Utils.debug("playFromSearch: " + query);
                    if (Utils.isEmpty(query)) {
                        playOnPlayer(() -> sendCommand(PLAY_COMMAND));
                        return;
                    }
                    clearCarResume("played from search");
                    getBrowseExecutor().execute(() -> switchOnForPlay(() -> {
                        for (MediaBrowserCompat.MediaItem item : getBrowseHelper().search(query)) {
                            if (item.isPlayable() && getBrowseHelper().playMediaId(item.getMediaId())) {
                                handler.post(ControlService.this::playbackRequested);
                                return;
                            }
                        }
                        Utils.debug("No playable search result for: " + query);
                    }));
                }

                @Override
                public void onCustomAction(String action, Bundle extras) {
                    if (ACTION_QUIT.equals(action)) {
                        quit();
                    } else if (ACTION_POWER.equals(action)) {
                        powerPressed();
                    } else if (ACTION_SHUFFLE.equals(action)) {
                        // Toggle LMS's shuffle mode (it then lists the queue in the shuffled order; off restores it).
                        if (null!=lastStatus) {
                            sendCommand(new String[]{"playlist", "shuffle", 0==lastStatus.shuffle ? "1" : "0"});
                        }
                    } else if (ACTION_RATE.equals(action)) {
                        if (canRate()) {
                            long now = SystemClock.elapsedRealtime();
                            boolean doubleTap = now - lastRateTap < RATE_DOUBLE_TAP_MS;
                            // A third quick tap starts a new cycle rather than another double tap.
                            lastRateTap = doubleTap ? 0 : now;
                            setRating(doubleTap ? 0 : Ratings.nextStars(lastStatus.rating));
                        }
                    }
                }
            };
        }
        return mediaSessionCallback;
    }

    // Playback was started from the browse tree or search, not via sendCommand(): also re-read the status
    // rather than relying on the CometD push, which can go silent and leave Now Playing on the old track.
    // LMS may still be loading the tracks after the first read, so read again a bit later.
    private void playbackRequested() {
        setBuffering();
        if (null!=cometClient && !cometClient.isConnected() && Utils.isNetworkConnected(this)) {
            cometClient.connect();
        }
        handler.removeCallbacks(commandStatusRefresh);
        handler.postDelayed(commandStatusRefresh, COMMAND_STATUS_REFRESH_MS);
        handler.postDelayed(commandStatusRefresh, PLAY_STATUS_REFRESH_MS);
    }

    private void setBuffering() {
        if (null!=mediaSession) {
            mediaSession.setPlaybackState(playbackStateBuilder(
                    PlaybackStateCompat.STATE_BUFFERING, 0, 0f).build());
        }
    }

    private PlaybackStateCompat.Builder playbackStateBuilder(int state, long position, float speed) {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setState(state, position, speed)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM);
        if (null!=lastStatus && lastStatus.playlistIndex >= 0 && null!=lastStatus.id && lastStatus.id.equals(MainActivity.activePlayer)) {
            builder.setActiveQueueItemId(lastStatus.playlistIndex);
        }
        // Single rating button that cycles Unrated -> 5..1 stars. (Android Auto's overflow menu
        // shows only 5 icon-only entries and can't be dismissed, so it is no use as a picker.)
        if (canRate()) {
            int rating = lastStatus.rating;
            builder.addCustomAction(ACTION_RATE, getString(R.string.rating) + " " + Ratings.stars(rating),
                    ratingIcon(Ratings.roundedStars(rating)));
        }
        // Android Auto draws custom action icons in one colour, so the state is shown by the icon's shape.
        boolean powered = null!=lastStatus && lastStatus.powered && null!=lastStatus.id && lastStatus.id.equals(MainActivity.activePlayer);
        builder.addCustomAction(ACTION_POWER, getString(R.string.power), powered ? R.drawable.ic_power_on : R.drawable.ic_power_off);
        if (null!=lastStatus && lastStatus.playlistIndex >= 0 && null!=lastStatus.id && lastStatus.id.equals(MainActivity.activePlayer)) {
            builder.addCustomAction(ACTION_SHUFFLE, getString(R.string.shuffle),
                    0==lastStatus.shuffle ? R.drawable.ic_shuffle_off : R.drawable.ic_shuffle);
        }
        // In the car Android Auto restarts the app straight away, so Quit would only restart the player app.
        if (!carConnected) {
            builder.addCustomAction(ACTION_QUIT, getString(R.string.quit), R.drawable.ic_action_quit);
        }
        return builder;
    }

    // The rating button's icon shows the current rating, as Android Auto doesn't display action labels.
    private static int ratingIcon(int stars) {
        switch (stars) {
            case 1: return R.drawable.ic_rating_1;
            case 2: return R.drawable.ic_rating_2;
            case 3: return R.drawable.ic_rating_3;
            case 4: return R.drawable.ic_rating_4;
            case 5: return R.drawable.ic_rating_5;
            default: return R.drawable.ic_star_border;
        }
    }

    private boolean canRate() {
        return Boolean.TRUE.equals(ratingsSupported) && null!=lastStatus && lastStatus.trackId > 0 &&
                null!=lastStatus.id && lastStatus.id.equals(MainActivity.activePlayer);
    }

    private void checkRatingsSupport() {
        if (null!=ratingsSupported || checkingRatingsSupport) {
            return;
        }
        checkingRatingsSupport = true;
        getBrowseExecutor().execute(() -> {
            // Any failure counts as "not installed" until the next reconnect resets the check, so a
            // slow or unreachable server doesn't get re-queried (blocking browsing) on every status update.
            boolean supported = false;
            try {
                if (null==rpc) {
                    rpc = new JsonRpc(getApplicationContext());
                }
                JSONObject response = rpc.sendMessageSync("", new String[]{"can", "ratingslight", "setrating", "?"}, 5000);
                JSONObject result = null==response ? null : response.optJSONObject("result");
                supported = null!=result && 1 == result.optInt("_can", 0);
            } catch (Exception e) {
                Utils.debug("Failed to check for Ratings Light: " + e.getMessage());
            }
            boolean value = supported;
            handler.post(() -> {
                checkingRatingsSupport = false;
                ratingsSupported = value;
                if (value) {
                    updateMediaSession();
                }
            });
        });
    }

    private void clearQueue() {
        if (null!=queuePlayer && null!=mediaSession) {
            mediaSession.setQueue(null);
        }
        queuePlayer = null;
        queueTimestamp = -1;
        queueStart = -1;
        queueTotal = 0;
    }

    // Reload the queue when the player or playlist changes, or the current track leaves the shown window.
    private void updateQueue() {
        final String player = lastStatus.id;
        final int current = lastStatus.playlistIndex;
        final double timestamp = lastStatus.playlistTimestamp;
        if (current < 0) {
            clearQueue();
            return;
        }
        if (queueLoading || (player.equals(queuePlayer) && timestamp == queueTimestamp &&
                PlayQueue.windowStart(current, queueTotal) == queueStart)) {
            return;
        }
        queueLoading = true;
        final int start = PlayQueue.windowStart(current, player.equals(queuePlayer) && timestamp == queueTimestamp ? queueTotal : Integer.MAX_VALUE);
        getBrowseExecutor().execute(() -> {
            List<MediaSessionCompat.QueueItem> items = null;
            int total = 0;
            int loadedStart = start;
            try {
                if (null==rpc) {
                    rpc = new JsonRpc(getApplicationContext());
                }
                JSONObject response = rpc.sendMessageSync(player, new String[]{"status", Integer.toString(start),
                        Integer.toString(PlayQueue.MAX_ITEMS), "tags:acdlKN"}, 10000);
                JSONObject result = null==response ? null : response.optJSONObject("result");
                if (null!=result) {
                    total = result.optInt("playlist_tracks", 0);
                    if (start > 0 && start != PlayQueue.windowStart(current, total)) {
                        // First load of a large queue - fetch again now the size is known.
                        loadedStart = PlayQueue.windowStart(current, total);
                        response = rpc.sendMessageSync(player, new String[]{"status", Integer.toString(loadedStart),
                                Integer.toString(PlayQueue.MAX_ITEMS), "tags:acdlKN"}, 10000);
                        result = null==response ? null : response.optJSONObject("result");
                    }
                }
                JSONArray loop = null==result ? null : result.optJSONArray("playlist_loop");
                if (null!=loop) {
                    String serverUrl = rpc.getServerUrl();
                    items = new ArrayList<>();
                    for (int i = 0; i < loop.length(); i++) {
                        JSONObject track = loop.getJSONObject(i);
                        long index = track.optLong("playlist index", loadedStart + i);
                        String title = track.optString("title", "");
                        String remoteTitle = track.optString("remote_title", "");
                        if (!remoteTitle.isEmpty() && (title.isEmpty() || title.startsWith("http"))) {
                            title = remoteTitle;
                        }
                        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                                .setMediaId("queue/" + index)
                                .setTitle(title)
                                .setSubtitle(track.optString("artist", ""));
                        String coverId = track.optString("coverid", "");
                        if (!coverId.isEmpty() && null!=serverUrl) {
                            desc.setIconUri(ArtworkProvider.wrap(getApplicationContext(),
                                    serverUrl + "/music/" + coverId + "/cover_300x300_f"));
                        }
                        items.add(new MediaSessionCompat.QueueItem(desc.build(), index));
                    }
                }
            } catch (Exception e) {
                Utils.debug("Failed to load play queue: " + e.getMessage());
            }
            final List<MediaSessionCompat.QueueItem> queue = items;
            final int queueSize = total;
            final int queueFrom = loadedStart;
            handler.post(() -> {
                queueLoading = false;
                if (null==mediaSession || null==queue || !player.equals(MainActivity.activePlayer)) {
                    return;
                }
                mediaSession.setQueue(queue);
                mediaSession.setQueueTitle(getString(R.string.queue));
                queuePlayer = player;
                queueTimestamp = timestamp;
                queueStart = queueFrom;
                queueTotal = queueSize;
                // Status may have moved on while loading (e.g. next track) - re-check.
                if (null!=lastStatus && lastStatus.id.equals(player)) {
                    updateQueue();
                }
            });
        });
    }

    private void setRating(int stars) {
        if (!canRate() || stars < 0 || stars > Ratings.MAX_STARS) {
            return;
        }
        final String player = MainActivity.activePlayer;
        sendCommand(new String[]{"ratingslight", "setrating", "track_id:" + lastStatus.trackId, "rating:" + stars});
        // Show the new rating immediately, then re-read the status to pick up what the server stored.
        lastStatus.rating = stars * 20;
        updateMediaSession();
        handler.postDelayed(() -> {
            if (null!=cometClient && player.equals(MainActivity.activePlayer)) {
                cometClient.getPlayerStatus(player);
            }
        }, RATING_REFRESH_MS);
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
        checkResumePosition(status);
        handler.post(() -> carResumeStatus(status));
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
            clearQueue();
            return;
        }
        int state = "play".equals(lastStatus.mode) ? PlaybackStateCompat.STATE_PLAYING :
                ("pause".equals(lastStatus.mode) ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED);
        mediaSession.setPlaybackState(playbackStateBuilder(state, displayTime(lastStatus),
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
        checkRatingsSupport();
        updateQueue();
        // No star text here: Android Auto shows the same subtitle in Now Playing and the split-screen
        // card, so the rating is shown only by the rating button's icon.
        String artist = lastStatus.artist;
        if (!lastStatus.powered) {
            // Android Auto has no place for other text in Now Playing, so the warning goes in the subtitle.
            artist = getString(R.string.player_off) + (Utils.isEmpty(artist) ? "" : " · " + artist);
        }
        if (!Utils.isEmpty(artist)) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist);
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
        carConnectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkCarConnection();
            }
        };
        ContextCompat.registerReceiver(this, carConnectionReceiver, new IntentFilter(CAR_CONNECTION_UPDATED),
                ContextCompat.RECEIVER_EXPORTED);
        checkCarConnection();
    }

    @Override
    public void onDestroy() {
        Utils.debug("");
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (null!=connectionChangeListener) {
            unregisterReceiver(connectionChangeListener);
            connectionChangeListener = null;
        }
        if (null!=carConnectionReceiver) {
            unregisterReceiver(carConnectionReceiver);
            carConnectionReceiver = null;
        }
        if (null!=cometClient) {
            cometClient.disconnect();
        }
        if (null!=browseExecutor) {
            browseExecutor.shutdownNow();
            browseExecutor = null;
        }
        cancelPlayerAvailabilityCheck();
        handler.removeCallbacks(autoPlayerCheck);
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
        startPlayerAvailabilityCheck(0);
    }

    private void startPlayerAvailabilityCheck(long delayMs) {
        if (Utils.isEmpty(MainActivity.activePlayer) && null==restrictedPlayer()) {
            return;
        }
        playerAvailabilityDeadline = SystemClock.elapsedRealtime() + delayMs + PLAYER_AVAILABILITY_TIMEOUT_MS;
        playerWasUnavailable = false;
        handler.removeCallbacks(playerAvailabilityCheck);
        handler.postDelayed(playerAvailabilityCheck, delayMs);
    }

    private void cancelPlayerAvailabilityCheck() {
        handler.removeCallbacks(playerAvailabilityCheck);
        playerAvailabilityDeadline = 0;
        playerWasUnavailable = false;
        playerStartPending = false;
        playAfterStart = null;
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
            boolean started = playerStartPending;
            playerStartPending = false;
            // After starting the player app always reconnect: LMS may still list the player from before.
            if (playerWasUnavailable || changed || started) {
                cometClient.setPlayer(player.id);
                cometClient.reconnect();
            }
            if (started) {
                powerOn(player);
                if (null!=playAfterStart) {
                    Runnable play = playAfterStart;
                    playAfterStart = null;
                    // After the power command, which is sent asynchronously
                    handler.postDelayed(() -> getBrowseExecutor().execute(play), COMMAND_STATUS_REFRESH_MS);
                }
            }
            updateMediaSession();
            if (changed) {
                updateNotification();
            }
            if (changed || playerWasUnavailable || started) {
                notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID); // shows it as connected
            }
            return;
        }
        playerWasUnavailable = true;
        long now = SystemClock.elapsedRealtime();
        if (playerStartPending && autoPlayerStarted && now >= nextPlayerRestart) {
            if (playerRestarts < MAX_PLAYER_RESTARTS) {
                playerRestarts++;
                nextPlayerRestart = now + PLAYER_RESTART_MS;
                restartPlayerApp();
            } else {
                Utils.debug("Player still not connected, giving up restarting the player app");
                playerStartPending = false;
            }
        }
        if (SystemClock.elapsedRealtime() < playerAvailabilityDeadline) {
            handler.postDelayed(playerAvailabilityCheck, PLAYER_AVAILABILITY_RETRY_MS);
        } else if (null!=restricted) {
            // The default player may start (e.g. Squeezelite on car connect) at any time - keep looking.
            handler.postDelayed(playerAvailabilityCheck, RESTRICTED_PLAYER_RETRY_MS);
        }
    }

    // The player app was started but its player has not appeared in LMS - stop it and start it again.
    private void restartPlayerApp() {
        Utils.debug("Player not connected, restarting the player app (" + playerRestarts + "/" + MAX_PLAYER_RESTARTS + ")");
        LocalPlayer localPlayer = new LocalPlayer(prefs, getApplicationContext());
        localPlayer.stop();
        handler.postDelayed(localPlayer::start, PLAYER_RESTART_DELAY_MS);
    }

    // Switching this phone's player off in LMS makes the Squeezelite app stop its player, after which LMS can't
    // switch it on again. So switching this phone's player on also (re)starts the player app: if the player isn't
    // connected, the player app is started and the player switched on once it connects; otherwise it is switched
    // on, and if it still isn't on shortly after, the player app is restarted.
    private void powerPressed() {
        String playerId = MainActivity.activePlayer;
        if (Utils.isEmpty(playerId)) {
            return;
        }
        PlayerStatus status = lastStatus;
        boolean shownOn = null!=status && playerId.equals(status.id) && status.powered;
        getBrowseExecutor().execute(() -> {
            List<PlayerAvailability.Player> players = fetchPlayers();
            PlayerAvailability.Player player = PlayerAvailability.findPlayer(players, playerId);
            // The status can be stale, so only switch off if LMS also says the player is on.
            boolean switchingOff = shownOn && (null==players || (null!=player && player.connected && player.powered));
            long time = switchingOff && status.trackId > 0 ? fetchResumeTime(playerId) : -1;
            handler.post(() -> {
                if (!playerId.equals(MainActivity.activePlayer)) {
                    return;
                }
                if (switchingOff) {
                    if (time > 0) {
                        saveResumePosition(playerId, status.trackId, time);
                    }
                    sendCommand(POWER_OFF_COMMAND);
                } else {
                    switchOn(players, playerId);
                }
            });
        });
    }

    private void switchOn(List<PlayerAvailability.Player> players, String playerId) {
        if (startPlayerApp(players, playerId, null)) {
            return; // switched on once it connects
        }
        boolean local = isLocalPlayer(players, playerId);
        if (local) {
            // Does nothing if the player app is running, but starts its player if that was stopped.
            new LocalPlayer(prefs, getApplicationContext()).start();
        }
        Utils.debug("Switching on " + playerId);
        sendCommand(POWER_ON_COMMAND);
        if (local) {
            handler.postDelayed(() -> checkSwitchedOn(playerId, null), POWER_ON_CHECK_MS);
        }
    }

    // This phone's player was switched on: if LMS doesn't list it as on and connected, restart the player app and
    // switch the player on (and do play, if set) once it connects.
    private void checkSwitchedOn(String playerId, Runnable play) {
        getBrowseExecutor().execute(() -> {
            List<PlayerAvailability.Player> players = fetchPlayers();
            PlayerAvailability.Player player = PlayerAvailability.findPlayer(players, playerId);
            handler.post(() -> {
                if (!playerId.equals(MainActivity.activePlayer) || null==players ||
                        (null!=player && player.connected && player.powered)) {
                    return;
                }
                Utils.debug("Player still not on after switching it on, restarting the player app");
                autoPlayerStarted = true;
                restartPlayerApp();
                // Not before the restart, which would find the old connection.
                startPlayerAvailabilityCheck(PLAYER_RESTART_DELAY_MS + PLAYER_AVAILABILITY_RETRY_MS);
                playerStartPending = true;
                playAfterStart = play;
                playerRestarts = 0;
                nextPlayerRestart = SystemClock.elapsedRealtime() + PLAYER_RESTART_MS;
                setBuffering();
            });
        });
    }

    // The current position (ms) if LMS is set to pause the player when it is switched off, else -1.
    // Called on the browse executor.
    private long fetchResumeTime(String playerId) {
        try {
            if (null==rpc) {
                rpc = new JsonRpc(getApplicationContext());
            }
            JSONObject response = rpc.sendMessageSync(playerId, new String[]{"playerpref", "powerOnResume", "?"}, 3000);
            JSONObject result = null==response ? null : response.optJSONObject("result");
            if (null==result || !result.optString("_p2").startsWith("PauseOff")) {
                return -1;
            }
            response = rpc.sendMessageSync(playerId, new String[]{"time", "?"}, 3000);
            result = null==response ? null : response.optJSONObject("result");
            return null==result ? -1 : (long) (result.optDouble("_time", 0) * 1000.0);
        } catch (Exception e) {
            Utils.debug("Failed to read the position: " + e.getMessage());
        }
        return -1;
    }

    private synchronized void saveResumePosition(String playerId, long trackId, long time) {
        Utils.debug("Saving position " + Utils.timeStr(time) + " of " + playerId);
        resumePlayer = playerId;
        resumeTrackId = trackId;
        resumeTime = time;
    }

    // LMS can't seek a stopped player, so until it plays again show the saved position rather than 0:00.
    private synchronized long displayTime(PlayerStatus status) {
        return null!=resumePlayer && resumePlayer.equals(status.id) && !status.isPlaying && status.time < resumeTime &&
                (status.trackId <= 0 || status.trackId == resumeTrackId) ? resumeTime : status.time;
    }

    // Called with the status lock held.
    private void checkResumePosition(PlayerStatus status) {
        if (null==resumePlayer || null==status || !resumePlayer.equals(status.id)) {
            return;
        }
        if (status.trackId > 0 && status.trackId != resumeTrackId) {
            resumePlayer = null; // another track was played
        } else if (status.isPlaying && status.trackId == resumeTrackId) {
            if (status.time < resumeTime - RESUME_TOLERANCE_MS) {
                Utils.debug("Resuming " + status.id + " at " + Utils.timeStr(resumeTime));
                rpc.sendMessage(status.id, new String[]{"time", String.valueOf(resumeTime / 1000.0)});
                handler.removeCallbacks(commandStatusRefresh);
                handler.postDelayed(commandStatusRefresh, COMMAND_STATUS_REFRESH_MS);
            }
            resumePlayer = null;
        }
    }

    // If the active player is this phone's and isn't connected, start the player app; the player is switched on
    // (and play, if set, is done on the browse executor) once it connects. Returns false if the player app wasn't
    // started.
    private boolean startPlayerApp(List<PlayerAvailability.Player> players, String playerId, Runnable play) {
        if (null==players || !playerId.equals(MainActivity.activePlayer)) {
            return false;
        }
        PlayerAvailability.Player player = PlayerAvailability.findPlayer(players, playerId);
        if ((null!=player && player.connected) || !isLocalPlayer(players, playerId) ||
                !new LocalPlayer(prefs, getApplicationContext()).start()) {
            return false;
        }
        Utils.debug("Player not connected, started the player app");
        autoPlayerStarted = true;
        startPlayerAvailabilityCheck();
        playerStartPending = true;
        playAfterStart = play;
        playerRestarts = 0;
        nextPlayerRestart = SystemClock.elapsedRealtime() + PLAYER_RESTART_MS;
        setBuffering();
        return true;
    }

    // Whether the player runs on this phone: by its IP, or if it is the default player and a player app is set.
    private boolean isLocalPlayer(List<PlayerAvailability.Player> players, String playerId) {
        return PlayerAvailability.isLocalPlayer(PlayerAvailability.findPlayer(players, playerId), playerId,
                MainActivity.activePlayerName, MainActivity.getLocalIpAddresses(),
                prefs.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null), hasPlayerApp());
    }

    private boolean hasPlayerApp() {
        String playerApp = prefs.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null);
        return !Utils.isEmpty(playerApp) && !LocalPlayer.NO_PLAYER.equals(playerApp);
    }

    private void playCurrent() {
        sendCommand(PLAY_COMMAND);
        // Resuming, LMS's first status can still have the old time (seen: the track's end), and
        // nothing is pushed while a track plays on.
        handler.postDelayed(commandStatusRefresh, PLAY_STATUS_REFRESH_MS);
    }

    // "Resume after reconnecting": the car disconnected, remember the active player's track and position.
    private void saveCarState() {
        if (null!=carResume) {
            // Not resumed during this drive: keep the saved state for the next one.
            Utils.debug("Keeping the state saved for " + carResume.player);
            carResume = null;
            return;
        }
        String playerId = MainActivity.activePlayer;
        if (!prefs.getBoolean(SettingsActivity.RESUME_ON_CONNECT_PREF_KEY, false) || Utils.isEmpty(playerId)) {
            return;
        }
        PlayerStatus status = lastStatus;
        getBrowseExecutor().execute(() -> {
            CarResume state = fetchCarState(playerId, status);
            Utils.debug("Saving car state: " + state);
            if (null==state) {
                prefs.edit().remove(CAR_RESUME_STATE_PREF_KEY).apply();
            } else {
                prefs.edit().putString(CAR_RESUME_STATE_PREF_KEY, state.serialize()).apply();
            }
        });
    }

    // The player's current track and position, from LMS, or from the last status if LMS can't be reached.
    // Called on the browse executor.
    private CarResume fetchCarState(String playerId, PlayerStatus status) {
        try {
            if (null==rpc) {
                rpc = new JsonRpc(getApplicationContext());
            }
            JSONObject response = rpc.sendMessageSync(playerId, new String[]{"status", "-", "1", "tags:"}, 3000);
            JSONObject result = null==response ? null : response.optJSONObject("result");
            JSONArray loop = null==result ? null : result.optJSONArray("playlist_loop");
            JSONObject track = null==loop ? null : loop.optJSONObject(0);
            if (null!=track && track.optLong("id", 0) > 0) {
                long trackId = track.optLong("id", 0);
                long time = (long) (result.optDouble("time", 0) * 1000.0);
                if ("stop".equals(result.optString("mode")) && null!=status && playerId.equals(status.id) &&
                        trackId==status.trackId) {
                    time = displayTime(status); // stopped by Power, with the position saved
                }
                return new CarResume(playerId, trackId, result.optInt("playlist_cur_index", 0), time);
            }
        } catch (Exception e) {
            Utils.debug("Failed to read the status: " + e.getMessage());
        }
        if (null==status || !playerId.equals(status.id) || status.trackId <= 0 || status.playlistIndex < 0) {
            return null;
        }
        return new CarResume(playerId, status.trackId, status.playlistIndex, status.isPlaying
                ? status.time + SystemClock.elapsedRealtime() - status.timestamp : displayTime(status));
    }

    // The car connected: load the state saved at the last disconnect, to be played once the player is on.
    private void loadCarResume() {
        clearCarResumeState();
        if (!prefs.getBoolean(SettingsActivity.RESUME_ON_CONNECT_PREF_KEY, false)) {
            return;
        }
        // On the browse executor: saving at the last disconnect may still be running there.
        getBrowseExecutor().execute(() -> {
            CarResume resume = CarResume.parse(prefs.getString(CAR_RESUME_STATE_PREF_KEY, null));
            if (null==resume) {
                return;
            }
            handler.post(() -> {
                if (!carConnected) {
                    return;
                }
                Utils.debug("To resume: " + resume);
                carResume = resume;
                carResumeStatus(lastStatus);
            });
        });
    }

    // A status of the active player: resume once the player is on and connected.
    private void carResumeStatus(PlayerStatus status) {
        CarResume resume = carResume;
        if (null==resume || null==status || !resume.player.equals(status.id) || !status.powered ||
                status.playlistIndex < 0) {
            return;
        }
        checkCarResume(false);
    }

    // Play from the saved position if the player is where the car left it: the same track current at the same
    // queue index, and not played on past the saved position (a restarted player is at 0:00). If it moved on
    // since, nothing is resumed (and play, a play from a controller during the check, just plays).
    private void checkCarResume(boolean play) {
        carResumePlay |= play;
        CarResume resume = carResume;
        if (null==resume || carResumeChecking) {
            return;
        }
        carResumeChecking = true;
        getBrowseExecutor().execute(() -> {
            boolean ok = false;
            boolean playing = false;
            int current = -1;
            long currentTrack = 0;
            long time = 0;
            try {
                if (null==rpc) {
                    rpc = new JsonRpc(getApplicationContext());
                }
                JSONObject response = rpc.sendMessageSync(resume.player, new String[]{"status", "-", "1", "tags:"}, 3000);
                JSONObject result = null==response ? null : response.optJSONObject("result");
                if (null!=result) {
                    ok = true;
                    playing = "play".equals(result.optString("mode"));
                    current = result.optInt("playlist_cur_index", -1);
                    time = "stop".equals(result.optString("mode")) ? 0 : (long) (result.optDouble("time", 0) * 1000.0);
                    JSONArray loop = result.optJSONArray("playlist_loop");
                    JSONObject track = null==loop ? null : loop.optJSONObject(0);
                    currentTrack = null==track ? 0 : track.optLong("id", 0);
                }
            } catch (Exception e) {
                Utils.debug("Failed to read the status: " + e.getMessage());
            }
            boolean read = ok;
            boolean isPlaying = playing;
            int currentIndex = current;
            long trackId = currentTrack;
            long position = time;
            handler.post(() -> carResumeChecked(resume, read, isPlaying, currentIndex, trackId, position));
        });
    }

    private void carResumeChecked(CarResume resume, boolean ok, boolean playing, int current, long trackId, long time) {
        carResumeChecking = false;
        boolean play = carResumePlay;
        carResumePlay = false;
        if (resume != carResume || !ok || !resume.player.equals(MainActivity.activePlayer)) {
            // Cleared meanwhile, or the status couldn't be read (tried again on the next status).
            if (play) {
                playCurrent();
            }
            return;
        }
        String moved = CarResume.movedOn(resume, current, trackId, time);
        if (null!=moved) {
            clearCarResume(moved);
            if (play) {
                playCurrent();
            }
            return;
        }
        clearCarResume("playing from " + Utils.timeStr(resume.time));
        // Seeked to once it plays.
        saveResumePosition(resume.player, resume.trackId, resume.time);
        if (playing) {
            handler.removeCallbacks(commandStatusRefresh);
            handler.post(commandStatusRefresh);
        } else {
            playCurrent();
        }
    }

    private void clearCarResume(String reason) {
        if (null!=carResume) {
            Utils.debug("Car state resumed or dropped: " + reason);
            prefs.edit().remove(CAR_RESUME_STATE_PREF_KEY).apply();
        }
        clearCarResumeState();
    }

    private void clearCarResumeState() {
        carResume = null;
        carResumePlay = false;
    }

    private void playOnPlayer(Runnable play) {
        if (null==restrictedPlayer()) {
            play.run();
            return;
        }
        getBrowseExecutor().execute(() -> switchOnForPlay(() -> handler.post(play)));
    }

    // With "Only control default" there's no point playing on the default player while it is switched off: switch
    // it on first, or, if its player app stopped when it was switched off, start that and play once it connects.
    // Playing before it connects would make LMS run through the whole queue, as every track fails at once.
    // Called on the browse executor; play is run on it, now or once the player connects.
    private void switchOnForPlay(Runnable play) {
        String playerId = MainActivity.activePlayer;
        // Not skipped when the last status says the player is on: that can be stale.
        if (null==restrictedPlayer() || Utils.isEmpty(playerId)) {
            play.run();
            return;
        }
        List<PlayerAvailability.Player> players = fetchPlayers();
        PlayerAvailability.Player player = PlayerAvailability.findPlayer(players, playerId);
        if (null==player || !player.connected) {
            handler.post(() -> {
                if (!startPlayerApp(players, playerId, play)) {
                    getBrowseExecutor().execute(play);
                }
            });
            return;
        }
        if (!player.powered) {
            Utils.debug("Switching on " + player.name + " to play");
            try {
                rpc.sendMessageSync(playerId, POWER_ON_COMMAND, 3000);
            } catch (Exception e) {
                Utils.debug("Failed to switch on: " + e.getMessage());
            }
            if (isLocalPlayer(players, playerId)) {
                // If it still isn't on, the player app is restarted and the current track played once it connects.
                handler.postDelayed(() -> checkSwitchedOn(playerId, () -> handler.post(() -> sendCommand(PLAY_COMMAND))),
                        POWER_ON_CHECK_MS);
            }
        }
        play.run();
    }

    // LMS's player list, or null if it couldn't be read. Called on the browse executor.
    private List<PlayerAvailability.Player> fetchPlayers() {
        try {
            if (null==rpc) {
                rpc = new JsonRpc(getApplicationContext());
            }
            JSONObject response = rpc.sendMessageSync("", new String[]{"players", "0", "100"}, 3000);
            JSONObject result = null==response ? null : response.optJSONObject("result");
            if (null!=result) {
                return PlayerAvailability.parse(result.optJSONArray("players_loop"));
            }
        } catch (Exception e) {
            Utils.debug("Failed to check players: " + e.getMessage());
        }
        return null;
    }

    // Once the player app is started, the player may be connected but switched off in LMS.
    private void powerOn(PlayerAvailability.Player player) {
        if (player.powered) {
            return;
        }
        Utils.debug("Switching on " + player.name);
        if (null==rpc) {
            rpc = new JsonRpc(getApplicationContext());
        }
        rpc.sendMessage(player.id, POWER_ON_COMMAND);
        handler.removeCallbacks(commandStatusRefresh);
        handler.postDelayed(commandStatusRefresh, COMMAND_STATUS_REFRESH_MS);
    }

    private void checkCarConnection() {
        getBrowseExecutor().execute(() -> {
            int type = -1;
            try (Cursor cursor = getContentResolver().query(CAR_CONNECTION_URI, new String[]{CAR_CONNECTION_STATE}, null, null, null)) {
                if (null!=cursor && cursor.moveToFirst()) {
                    type = cursor.getInt(cursor.getColumnIndexOrThrow(CAR_CONNECTION_STATE));
                }
            } catch (Exception e) {
                Utils.debug("Failed to read car connection state: " + e.getMessage());
            }
            if (type < 0) {
                return;
            }
            boolean connected = CAR_CONNECTION_PROJECTION == type;
            handler.post(() -> {
                // Only a change of the state is a disconnect: when the service starts, the first read can still
                // say disconnected after onGetRoot has already seen the car.
                boolean changed = connected != carStateConnected;
                carStateConnected = connected;
                if (connected != carConnected && (connected || changed)) {
                    Utils.debug("Car " + (connected ? "connected" : "disconnected"));
                    if (connected) {
                        androidAutoConnected();
                    } else {
                        carConnected = false;
                        lastAutoConnect = 0; // the debounce is only for repeats within one connection
                        saveCarState();
                        updateMediaSession();
                    }
                }
            });
        });
    }

    /**
     * Android Auto connected: check the selected library, start the player app (if set to auto start), and
     * control it if it is the default player or nothing else is connected. With "Only control default", the
     * player app is restarted if its player does not connect, and the player is switched on.
     */
    private void androidAutoConnected() {
        if (!carConnected) {
            carConnected = true;
            updateMediaSession(); // hides Quit
        }
        long now = SystemClock.elapsedRealtime();
        if (0!=lastAutoConnect && now - lastAutoConnect < AUTO_CONNECT_DEBOUNCE_MS) {
            return;
        }
        lastAutoConnect = now;
        Utils.debug("Android Auto connected");
        loadCarResume();
        getBrowseHelper().prefetchDailyLists();
        // Queued before Android Auto's first loadChildren, so the root and its lists use a valid selection.
        getBrowseExecutor().execute(() -> {
            if (getBrowseHelper().ensureLibrarySelected()) {
                handler.post(() -> {
                    notifyChildrenChanged(LmsBrowseHelper.ROOT_ID);
                    notifyChildrenChanged(LmsBrowseHelper.LIBRARIES_ID);
                });
            }
        });

        // The player app is only started when "Auto start player" is enabled, as on app start.
        autoPlayerStarted = prefs.getBoolean(SettingsActivity.AUTO_START_PLAYER_APP_PREF_KEY, false) &&
                new LocalPlayer(prefs, getApplicationContext()).start();
        if (null!=restrictedPlayer()) {
            // Only the default player may be used - the availability check waits for that one.
            startPlayerAvailabilityCheck();
            playerStartPending = true;
            playerRestarts = 0;
            nextPlayerRestart = now + PLAYER_RESTART_MS;
            return;
        }
        cancelPlayerAvailabilityCheck();
        autoPlayerDeadline = now + AUTO_PLAYER_TIMEOUT_MS;
        handler.removeCallbacks(autoPlayerCheck);
        handler.post(autoPlayerCheck);
    }

    private void checkAutoPlayer() {
        getBrowseExecutor().execute(() -> {
            List<PlayerAvailability.Player> players = fetchPlayers();
            handler.post(() -> autoPlayerChecked(players));
        });
    }

    private void autoPlayerChecked(List<PlayerAvailability.Player> players) {
        String defaultPlayer = prefs.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null);
        defaultPlayer = null==defaultPlayer || defaultPlayer.trim().isEmpty() ? null : defaultPlayer.trim();
        PlayerAvailability.Player active = PlayerAvailability.findConnectedPlayer(players, MainActivity.activePlayer);
        PlayerAvailability.Player local = autoPlayerStarted
                ? PlayerAvailability.findConnectedLocalPlayer(players, MainActivity.getLocalIpAddresses(), defaultPlayer, hasPlayerApp())
                : null;
        boolean activeIsDefault = null!=active && null!=defaultPlayer && PlayerAvailability.isAllowed(defaultPlayer, active.id, active.name);

        if (null!=local) {
            boolean localIsDefault = null!=defaultPlayer && PlayerAvailability.isAllowed(defaultPlayer, local.id, local.name);
            useAutoPlayer(localIsDefault || null==active || local.id.equals(active.id) ? local : active);
            return;
        }
        if (autoPlayerStarted && !activeIsDefault && SystemClock.elapsedRealtime() < autoPlayerDeadline) {
            // Give the player app a few seconds to start and register with LMS.
            handler.postDelayed(autoPlayerCheck, PLAYER_AVAILABILITY_RETRY_MS);
            return;
        }
        if (null==active && null!=defaultPlayer && null!=players) {
            active = PlayerAvailability.findConnectedAllowedPlayer(players, defaultPlayer);
        }
        if (null!=active) {
            useAutoPlayer(active);
        } else {
            Utils.debug("No connected player for Android Auto");
            startPlayerAvailabilityCheck();
        }
    }

    private void useAutoPlayer(PlayerAvailability.Player player) {
        boolean changed = !player.id.equals(MainActivity.activePlayer);
        Utils.debug("Android Auto player " + player.name + ", id:" + player.id + (changed ? " (selected)" : ""));
        MainActivity.activePlayer = player.id;
        MainActivity.activePlayerName = player.name;
        if (changed) {
            prefs.edit().putString(MainActivity.CURRENT_PLAYER_ID_KEY, player.id).apply();
        }
        cancelPlayerAvailabilityCheck();
        // Always reconnect: the status subscription may have been made before the player existed in LMS,
        // leaving it shown as disconnected.
        cometClient.setPlayer(player.id);
        cometClient.reconnect();
        powerOn(player);
        updateMediaSession();
        updateNotification();
        notifyChildrenChanged(LmsBrowseHelper.PLAYERS_ID);
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
