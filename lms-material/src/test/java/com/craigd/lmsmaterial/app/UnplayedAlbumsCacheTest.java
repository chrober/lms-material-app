package com.craigd.lmsmaterial.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UnplayedAlbumsCacheTest {
    private static final long DAY = 24 * 60 * 60 * 1000L;

    @Test
    public void cachedPerLibraryAndAge() throws Exception {
        UnplayedAlbumsCache cache = new UnplayedAlbumsCache(DAY);
        AtomicInteger fetches = new AtomicInteger();
        UnplayedAlbumsCache.Fetcher fetcher = () -> Arrays.asList("a" + fetches.incrementAndGet());

        assertEquals(Arrays.asList("a1"), cache.get("lib", 0, fetcher));
        assertEquals(Arrays.asList("a1"), cache.get("lib", DAY - 1, fetcher));
        assertEquals(Arrays.asList("a2"), cache.get("other", DAY - 1, fetcher));
        assertEquals(Arrays.asList("a3"), cache.get("other", 2 * DAY, fetcher));
        assertEquals(3, fetches.get());
    }

    @Test
    public void failedFetchKeepsLastIds() throws Exception {
        UnplayedAlbumsCache cache = new UnplayedAlbumsCache(DAY);
        assertNull(cache.get("lib", 0, () -> null));
        cache.get("lib", 0, () -> Arrays.asList("a"));
        assertEquals(Arrays.asList("a"), cache.get("lib", 2 * DAY, () -> null));
    }

    // 2026-11-06: reset() on the main thread waited for a fetch whose request hung after a network change: ANR.
    @Test(timeout = 10000)
    public void clearDoesNotWaitForRunningFetch() throws Exception {
        UnplayedAlbumsCache cache = new UnplayedAlbumsCache(DAY);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<List<String>> result = new AtomicReference<>();
        Thread fetch = new Thread(() -> {
            try {
                result.set(cache.get("lib", 0, () -> {
                    fetching.countDown();
                    release.await(); // a request that gets no answer
                    return Arrays.asList("stale");
                }));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fetch.start();
        assertTrue(fetching.await(5, TimeUnit.SECONDS));

        int before = cache.generation();
        long start = System.nanoTime();
        cache.clear();
        assertTrue("clear() waited for the fetch", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertNotEquals(before, cache.generation());

        release.countDown();
        fetch.join();
        assertEquals(Arrays.asList("stale"), result.get()); // the caller still gets its answer...
        // ...but it isn't cached: the next get fetches again
        assertEquals(Arrays.asList("fresh"), cache.get("lib", 1, () -> Arrays.asList("fresh")));
    }
}
