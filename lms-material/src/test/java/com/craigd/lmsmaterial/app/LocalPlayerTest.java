package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalPlayerTest {
    @Test
    public void stopSqueezeliteSendsExplicitStopBroadcast() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null)).thenReturn(LocalPlayer.SQUEEZELITE);
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.isInstalled(context, LocalPlayer.SQUEEZELITE_PKG, "Squeezelite")).thenReturn(true);
            new LocalPlayer(preferences, context).stop();
        }
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(context).sendBroadcast(intent.capture());
        assertEquals("org.lyrion.squeezelite.STOP", intent.getValue().getAction());
        assertEquals("org.lyrion.squeezelite", intent.getValue().getComponent().getPackageName());
        assertEquals("org.lyrion.squeezelite.CommandReceiver", intent.getValue().getComponent().getClassName());
        verify(context, never()).stopService(any(Intent.class));
    }

    @Test
    public void stopForkSendsBroadcastToForkPackage() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null)).thenReturn(LocalPlayer.SQUEEZELITE_CHROBER);
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.isInstalled(context, LocalPlayer.SQUEEZELITE_CHROBER_PKG, "Squeezelite (chrober)")).thenReturn(true);
            new LocalPlayer(preferences, context).stop();
        }
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(context).sendBroadcast(intent.capture());
        assertEquals("org.lyrion.squeezelite.STOP", intent.getValue().getAction());
        assertEquals("org.lyrion.squeezelite.chrober", intent.getValue().getComponent().getPackageName());
        assertEquals("org.lyrion.squeezelite.CommandReceiver", intent.getValue().getComponent().getClassName());
        verify(context, never()).stopService(any(Intent.class));
    }
}
