/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import static com.craigd.lmsmaterial.app.MainActivity.LMS_PASSWORD_KEY;
import static com.craigd.lmsmaterial.app.MainActivity.LMS_USERNAME_KEY;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.eclipse.jetty.util.B64Code;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Android Auto only loads browse artwork from content:// (or android.resource://) URIs, so
 * LMS images are exposed via this provider, which downloads and caches them on first use.
 *
 * URIs are content://AUTHORITY/lms/ENCODED_PATH, where ENCODED_PATH is an image path on the configured
 * LMS server (e.g. music/<id>/cover_300x300_f). The provider is exported, so that Android Auto can read it,
 * and it sends the stored LMS credentials. So it only fetches from the configured server, only paths that
 * are images (no query string, so no LMS commands), and only for Android Auto or this app. External images
 * go through LMS's imageproxy, as in the web UI.
 */
public class ArtworkProvider extends ContentProvider {
    private static final String PATH = "lms";
    private static final String CACHE_DIR = "artwork";
    private static final int TIMEOUT_MS = 10000;
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final String ANDROID_AUTO_PKG = "com.google.android.projection.gearhead";

    // Image paths on the LMS server: covers, imageproxy (external images, fetched and resized by LMS), and
    // the static icons of the web UI and plugins (optionally resized by LMS, e.g. icon_300x300_f.png).
    private static final Pattern ALLOWED_PATH = Pattern.compile(
            "music/[A-Za-z0-9_-]+/cover(_[A-Za-z0-9]+)*(\\.(jpg|jpeg|png|gif|webp))?" +
            "|imageproxy(/[A-Za-z0-9%._~!'()*-]+)+/image(_[A-Za-z0-9]+)*(\\.(jpg|jpeg|png|gif|webp))?" +
            "|(html|material/html|plugins/[A-Za-z0-9_.-]+/html)(/[A-Za-z0-9_.-]+)+\\.(jpg|jpeg|png|gif|svg|webp)");

    private static final Pattern IMAGEPROXY_UNSIZED = Pattern.compile("/image(\\.[A-Za-z]+)?$");

    public static String authority(Context context) {
        return context.getPackageName() + ".artwork";
    }

    // path: an image path on the LMS server, with or without a leading '/'.
    public static Uri wrap(Context context, String path) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH)
                .appendPath(path.startsWith("/") ? path.substring(1) : path)
                .build();
    }

    // Turns an image reference from LMS (a server path, an imageproxy path, or an http(s) URL) into a path
    // on the LMS server for wrap(), or null. External URLs go through LMS's imageproxy; size is the LMS
    // resize suffix for them (e.g. _300x300_f).
    public static String lmsPath(String image, String serverUrl, String size) {
        if (null==image || image.isEmpty()) {
            return null;
        }
        if (image.startsWith("http://") || image.startsWith("https://")) {
            if (null!=serverUrl && image.startsWith(serverUrl + "/")) {
                image = image.substring(serverUrl.length());
            } else {
                return "imageproxy/" + Utils.encodeURIComponent(image) + "/image" + size;
            }
        }
        String path = image.startsWith("/") ? image.substring(1) : image;
        // Without a size LMS's imageproxy redirects to the external image, which isn't fetched (not the server)
        if (path.startsWith("imageproxy/")) {
            path = IMAGEPROXY_UNSIZED.matcher(path).replaceFirst("/image" + size);
        }
        return path;
    }

    static boolean isAllowedPath(String path) {
        return null!=path && !path.contains("..") && ALLOWED_PATH.matcher(path).matches();
    }

    static boolean isSameServer(URL server, URL url) {
        return server.getProtocol().equals(url.getProtocol()) && server.getHost().equalsIgnoreCase(url.getHost()) &&
                (server.getPort()<0 ? server.getDefaultPort() : server.getPort()) == (url.getPort()<0 ? url.getDefaultPort() : url.getPort());
    }

    static boolean isAllowedCaller(String callingPackage, String ownPackage) {
        return null!=callingPackage && (callingPackage.equals(ownPackage) || callingPackage.equals(ANDROID_AUTO_PKG));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context context = getContext();
        if (null==context || uri.getPathSegments().size()!=2 || !PATH.equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException(uri.toString());
        }
        String caller = Binder.getCallingUid()==Process.myUid() ? context.getPackageName() : getCallingPackage();
        if (!isAllowedCaller(caller, context.getPackageName())) {
            Utils.debug("Artwork request from " + caller + " refused");
            throw new SecurityException("Artwork is only available to Android Auto");
        }
        String path = uri.getPathSegments().get(1);
        if (!isAllowedPath(path)) {
            Utils.debug("Artwork path refused: " + path);
            throw new FileNotFoundException(uri.toString());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY, null));
        if (null==server.ip) {
            throw new FileNotFoundException(uri.toString());
        }
        String url = "http://" + server.ip + ":" + server.port + "/" + path;
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        File file = new File(dir, hash(url));
        if (!file.exists()) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new FileNotFoundException("Failed to create artwork cache");
            }
            download(prefs, url, file);
            prune(dir);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void download(SharedPreferences prefs, String url, File file) throws FileNotFoundException {
        File tmp;
        try {
            tmp = File.createTempFile(file.getName(), ".tmp", file.getParentFile());
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to create artwork file");
        }
        HttpURLConnection connection = null;
        try {
            // Only ever the configured LMS server (see openFile), so its credentials can be sent. LMS redirects
            // some images (e.g. artist images to imageproxy/mai/_artist/... or contributor/...): follow those
            // only while they stay on the same server.
            URL server = new URL(url);
            URL imageUrl = server;
            String user = prefs.getString(LMS_USERNAME_KEY, "");
            String pass = prefs.getString(LMS_PASSWORD_KEY, "");
            for (int redirects = 0; ; redirects++) {
                connection = (HttpURLConnection) imageUrl.openConnection();
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                if (!user.isEmpty() && !pass.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Basic " + B64Code.encode(user + ":" + pass));
                }
                int code = connection.getResponseCode();
                if (code<300 || code>=400) {
                    break;
                }
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                connection = null;
                URL next = null==location ? null : new URL(imageUrl, location);
                if (null==next || redirects>=MAX_REDIRECTS || !isSameServer(server, next)) {
                    throw new FileNotFoundException("Artwork redirect refused: " + location);
                }
                imageUrl = next;
            }
            String type = connection.getContentType();
            if (connection.getResponseCode()!=HttpURLConnection.HTTP_OK || (null!=type && !type.startsWith("image/"))) {
                throw new FileNotFoundException(url);
            }
            long total = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) {
                        throw new FileNotFoundException("Image too large: " + url);
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (!tmp.renameTo(file)) {
                throw new FileNotFoundException("Failed to cache " + url);
            }
        } catch (FileNotFoundException e) {
            tmp.delete();
            throw e;
        } catch (Exception e) {
            tmp.delete();
            Utils.debug("Failed to fetch artwork " + url + ": " + e.getMessage());
            throw new FileNotFoundException(url);
        } finally {
            if (null!=connection) {
                connection.disconnect();
            }
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (null==files) {
            return;
        }
        long size = 0;
        for (File f : files) {
            size += f.length();
        }
        if (size <= MAX_CACHE_BYTES) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (size <= MAX_CACHE_BYTES * 3 / 4) {
                break;
            }
            size -= f.length();
            f.delete();
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
