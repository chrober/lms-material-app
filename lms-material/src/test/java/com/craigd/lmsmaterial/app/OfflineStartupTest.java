package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.app.Activity;
import android.app.KeyguardManager;
import android.net.ConnectivityManager;
import android.view.View;
import android.webkit.WebView;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyguardManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
    public void loadsConfiguredServerWithoutDefaultNetwork() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(null);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(SettingsActivity.SERVER_PREF_KEY, "{\"ip\":\"192.168.43.123\",\"port\":9000}")
                .apply();

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        WebView webView = activity.findViewById(R.id.webview);

        assertEquals(View.VISIBLE, webView.getVisibility());
        assertTrue(shadowOf(webView).getLastLoadedUrl().startsWith("http://192.168.43.123:9000/material/"));
    }
}
