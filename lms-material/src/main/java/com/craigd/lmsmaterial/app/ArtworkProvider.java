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
import android.os.ParcelFileDescriptor;

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

/**
 * Android Auto only loads browse artwork from content:// (or android.resource://) URIs, so
 * remote images are exposed via this provider, which downloads and caches them on first use.
 *
 * URIs are content://AUTHORITY/img/ENCODED_URL, where ENCODED_URL is the http(s) image URL.
 */
public class ArtworkProvider extends ContentProvider {
    private static final String PATH = "img";
    private static final String CACHE_DIR = "artwork";
    private static final int TIMEOUT_MS = 10000;
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 50 * 1024 * 1024;

    public static String authority(Context context) {
        return context.getPackageName() + ".artwork";
    }

    public static Uri wrap(Context context, String url) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH)
                .appendPath(url)
                .build();
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
        String url = uri.getPathSegments().get(1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new FileNotFoundException(uri.toString());
        }
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        File file = new File(dir, hash(url));
        if (!file.exists()) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new FileNotFoundException("Failed to create artwork cache");
            }
            download(context, url, file);
            prune(dir);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void download(Context context, String url, File file) throws FileNotFoundException {
        File tmp;
        try {
            tmp = File.createTempFile(file.getName(), ".tmp", file.getParentFile());
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to create artwork file");
        }
        HttpURLConnection connection = null;
        try {
            URL imageUrl = new URL(url);
            connection = (HttpURLConnection) imageUrl.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY, null));
            int port = imageUrl.getPort()<0 ? imageUrl.getDefaultPort() : imageUrl.getPort();
            if (null!=server.ip && server.ip.equalsIgnoreCase(imageUrl.getHost()) && server.port==port) {
                String user = prefs.getString(LMS_USERNAME_KEY, "");
                String pass = prefs.getString(LMS_PASSWORD_KEY, "");
                if (!user.isEmpty() && !pass.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Basic " + B64Code.encode(user + ":" + pass));
                }
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
