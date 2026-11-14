package com.craigd.lmsmaterial.app;

import java.util.List;
import java.util.Objects;

/**
 * The album IDs of Unplayed Albums, cached per library for a while.
 *
 * Only one fetch runs at a time, but clear() never waits for it: it is called on the main thread (network change,
 * app start), while a fetch can be waiting for LMS to answer a request sent on the network the phone just left,
 * until its timeout. Waiting for the fetch there froze the app until Android reported it as not responding.
 * A fetch that was running when clear() was called is not cached.
 */
class UnplayedAlbumsCache {
    interface Fetcher {
        /** The album IDs, or null if they could not be read. */
        List<String> fetch() throws Exception;
    }

    private static class Entry {
        final String libraryId;
        final long time;
        final List<String> ids;

        Entry(String libraryId, long time, List<String> ids) {
            this.libraryId = libraryId;
            this.time = time;
            this.ids = ids;
        }
    }

    private final long maxAgeMs;
    private final Object fetchLock = new Object(); // held during a fetch
    private final Object stateLock = new Object(); // held only briefly
    private int generation = 0;
    private Entry entry = null;

    UnplayedAlbumsCache(long maxAgeMs) {
        this.maxAgeMs = maxAgeMs;
    }

    /** The cached IDs if still valid for this library, else fetched; on failure the last IDs (if any). */
    List<String> get(String libraryId, long now, Fetcher fetcher) throws Exception {
        synchronized (fetchLock) {
            int gen;
            Entry current;
            synchronized (stateLock) {
                gen = generation;
                current = entry;
            }
            if (null!=current && Objects.equals(libraryId, current.libraryId) && now - current.time < maxAgeMs) {
                return current.ids;
            }
            List<String> ids = fetcher.fetch();
            if (null==ids) {
                return null==current ? null : current.ids;
            }
            synchronized (stateLock) {
                if (gen==generation) {
                    entry = new Entry(libraryId, now, ids);
                }
            }
            return ids;
        }
    }

    /** Drop the cached IDs, without waiting for a running fetch. */
    void clear() {
        synchronized (stateLock) {
            generation++;
            entry = null;
        }
    }

    /** Changes on every clear(): lets callers drop what they built from a fetch that was running meanwhile. */
    int generation() {
        synchronized (stateLock) {
            return generation;
        }
    }
}
