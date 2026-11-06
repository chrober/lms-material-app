package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.Uri;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.FileNotFoundException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ArtworkProviderTest {
    private static final String SERVER = "http://192.168.1.8:9000";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";

    @Test
    public void serverPathsStayOnTheServer() {
        assertEquals("music/8d11da4a/cover", ArtworkProvider.lmsPath("/music/8d11da4a/cover", SERVER, "_300x300_f"));
        assertEquals("plugins/Podcast/html/images/icon.png",
                ArtworkProvider.lmsPath("plugins/Podcast/html/images/icon.png", SERVER, "_300x300_f"));
        assertEquals("imageproxy/http%3A%2F%2Fcdn-profiles.tunein.com%2Fs20305%2Fimages%2Flogoq.png%3Ft%3D1/image_300x300_f",
                ArtworkProvider.lmsPath("/imageproxy/http%3A%2F%2Fcdn-profiles.tunein.com%2Fs20305%2Fimages%2Flogoq.png%3Ft%3D1/image.png",
                        SERVER, "_300x300_f"));
        assertEquals("music/1/cover_300x300_f", ArtworkProvider.lmsPath(SERVER + "/music/1/cover_300x300_f", SERVER, "_300x300_f"));
        assertNull(ArtworkProvider.lmsPath("", SERVER, "_300x300_f"));
        assertNull(ArtworkProvider.lmsPath(null, SERVER, "_300x300_f"));
    }

    @Test
    public void imageproxyImagesAreAlwaysSized() {
        // Without a size LMS redirects to the external host, which the provider refuses to follow
        assertEquals("imageproxy/https%3A%2F%2Fi.guim.co.uk%2Fa.jpg/image_300x300_f",
                ArtworkProvider.lmsPath("/imageproxy/https%3A%2F%2Fi.guim.co.uk%2Fa.jpg/image.jpg", SERVER, "_300x300_f"));
        assertEquals("imageproxy/https%3A%2F%2Fi.guim.co.uk%2Fa.jpg/image_300x300_f",
                ArtworkProvider.lmsPath("imageproxy/https%3A%2F%2Fi.guim.co.uk%2Fa.jpg/image", SERVER, "_300x300_f"));
        assertEquals("imageproxy/mai/artist/1/image_300x300_f",
                ArtworkProvider.lmsPath("/imageproxy/mai/artist/1/image_300x300_f", SERVER, "_300x300_f"));
    }

    @Test
    public void externalImagesGoThroughImageproxy() {
        String path = ArtworkProvider.lmsPath("https://i.scdn.co/image/ab67?x=1&y=2", SERVER, "_300x300_f");
        assertEquals("imageproxy/https%3A%2F%2Fi.scdn.co%2Fimage%2Fab67%3Fx%3D1%26y%3D2/image_300x300_f", path);
        assertTrue(ArtworkProvider.isAllowedPath(path));
    }

    @Test
    public void allowsImagePaths() {
        assertTrue(ArtworkProvider.isAllowedPath("music/8d11da4a/cover_300x300_f"));
        assertTrue(ArtworkProvider.isAllowedPath("music/-12345/cover"));
        assertTrue(ArtworkProvider.isAllowedPath("music/8d11da4a/cover.jpg"));
        assertTrue(ArtworkProvider.isAllowedPath("imageproxy/http%3A%2F%2Fexample.com%2Fa.png/image.png"));
        assertTrue(ArtworkProvider.isAllowedPath("imageproxy/mai/artist/123/image_300x300_f"));
        assertTrue(ArtworkProvider.isAllowedPath("plugins/Podcast/html/images/icon_300x300_f.png"));
        assertTrue(ArtworkProvider.isAllowedPath("plugins/RadioFavourites/html/images/RadioFavourites_svg.png"));
        assertTrue(ArtworkProvider.isAllowedPath("html/images/radio.png"));
        assertTrue(ArtworkProvider.isAllowedPath("material/html/images/album.svg"));
    }

    @Test
    public void refusesOtherPaths() {
        // Full URLs: the provider must not be usable to reach other hosts
        assertFalse(ArtworkProvider.isAllowedPath("http://example.com/a.png"));
        assertFalse(ArtworkProvider.isAllowedPath("//example.com/a.png"));
        // LMS web pages run commands from p0..pN query parameters
        assertFalse(ArtworkProvider.isAllowedPath("status.html?p0=pause"));
        assertFalse(ArtworkProvider.isAllowedPath("music/1/cover?p0=pause"));
        assertFalse(ArtworkProvider.isAllowedPath("html/images/a.png?p0=stop"));
        assertFalse(ArtworkProvider.isAllowedPath("imageproxy/x/image?p0=stop"));
        assertFalse(ArtworkProvider.isAllowedPath("jsonrpc.js"));
        assertFalse(ArtworkProvider.isAllowedPath("settings/index.html"));
        assertFalse(ArtworkProvider.isAllowedPath("plugins/Foo/settings/basic.html"));
        assertFalse(ArtworkProvider.isAllowedPath("html/../prefs/server.prefs.png"));
        assertFalse(ArtworkProvider.isAllowedPath("music/1/download"));
        assertFalse(ArtworkProvider.isAllowedPath(""));
        assertFalse(ArtworkProvider.isAllowedPath(null));
    }

    @Test
    public void onlyAndroidAutoAndThisApp() {
        String own = "com.craigd.lmsmaterial.app";
        assertTrue(ArtworkProvider.isAllowedCaller(GEARHEAD, own));
        assertTrue(ArtworkProvider.isAllowedCaller(own, own));
        assertFalse(ArtworkProvider.isAllowedCaller("com.example.other", own));
        assertFalse(ArtworkProvider.isAllowedCaller(null, own));
    }

    @Test
    public void followsRedirectsOnlyOnTheServer() throws Exception {
        java.net.URL server = new java.net.URL(SERVER + "/imageproxy/mai/artist/1/image_300x300_f");
        assertTrue(ArtworkProvider.isSameServer(server, new java.net.URL(server, "/contributor/89a2beab/image_300x300_f")));
        assertTrue(ArtworkProvider.isSameServer(server, new java.net.URL(SERVER + "/imageproxy/mai/_artist/1/image_300x300_f")));
        assertFalse(ArtworkProvider.isSameServer(server, new java.net.URL("http://example.com:9000/a.png")));
        assertFalse(ArtworkProvider.isSameServer(server, new java.net.URL("http://192.168.1.8:80/a.png")));
        assertFalse(ArtworkProvider.isSameServer(server, new java.net.URL("https://192.168.1.8:9000/a.png")));
    }

    @Test
    public void wrapCarriesOnlyThePath() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = ArtworkProvider.wrap(context, "/music/8d11da4a/cover_300x300_f");
        assertEquals("content", uri.getScheme());
        assertEquals(ArtworkProvider.authority(context), uri.getAuthority());
        assertEquals(2, uri.getPathSegments().size());
        assertEquals("music/8d11da4a/cover_300x300_f", uri.getPathSegments().get(1));
    }

    @Test
    public void openFileRefusesAFullUrl() {
        Context context = RuntimeEnvironment.getApplication();
        ArtworkProvider provider = Robolectric.setupContentProvider(ArtworkProvider.class, ArtworkProvider.authority(context));
        Uri uri = new Uri.Builder().scheme("content").authority(ArtworkProvider.authority(context))
                .appendPath("lms").appendPath("http://example.com/a.png").build();
        try {
            provider.openFile(uri, "r");
            fail("Full URL accepted");
        } catch (FileNotFoundException expected) {
        }
    }
}
