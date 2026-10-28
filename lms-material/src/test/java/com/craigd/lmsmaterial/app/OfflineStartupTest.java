package com.craigd.lmsmaterial.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.view.View;
import android.webkit.WebView;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyguardManager;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {OfflineStartupTest.ShadowUtils.class, OfflineStartupTest.TestKeyguardManager.class})
public class OfflineStartupTest {
    @Implements(KeyguardManager.class)
    public static class TestKeyguardManager extends ShadowKeyguardManager {
        @Implementation protected void requestDismissKeyguard(Activity activity, KeyguardManager.KeyguardDismissCallback callback) { }
    }

    @Implements(Utils.class)
    public static class ShadowUtils {
        @Implementation protected static void debug(String message) { }
        @Implementation protected static void info(String message) { }
        @Implementation protected static void verbose(String message) { }
        @Implementation protected static void warn(String message) { }
        @Implementation protected static void error(String message) { }
        @Implementation protected static void error(String message, Throwable error) { }
    }

    @Test
    public void waitsForNetworkWhenNoHotspotExists() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenReturn(Collections.emptyEnumeration());
            MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
            assertEquals(View.GONE, activity.findViewById(R.id.webview).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.progress).getVisibility());
        }
    }

    @Test
    public void loadsConfiguredServerThroughActiveHotspot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(SettingsActivity.SERVER_PREF_KEY, "{\"ip\":\"192.168.43.123\",\"port\":9000}")
                .apply();
        NetworkInterface hotspot = mock(NetworkInterface.class);
        InterfaceAddress address = mock(InterfaceAddress.class);
        when(hotspot.getName()).thenReturn("swlan0");
        when(hotspot.isUp()).thenReturn(true);
        when(address.getBroadcast()).thenReturn(InetAddress.getByName("192.168.43.255"));
        when(hotspot.getInterfaceAddresses()).thenReturn(Collections.singletonList(address));
        try (MockedStatic<NetworkInterface> interfaces = mockStatic(NetworkInterface.class)) {
            interfaces.when(NetworkInterface::getNetworkInterfaces).thenAnswer(invocation -> Collections.enumeration(Collections.singletonList(hotspot)));
            MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
            WebView webView = activity.findViewById(R.id.webview);
            assertEquals(View.VISIBLE, webView.getVisibility());
            assertTrue(shadowOf(webView).getLastLoadedUrl().startsWith("http://192.168.43.123:9000/material/"));
        }
    }
}
