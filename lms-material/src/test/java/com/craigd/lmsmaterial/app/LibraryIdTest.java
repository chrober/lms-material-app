package com.craigd.lmsmaterial.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LibraryIdTest {
    @Test
    public void all() {
        assertNull(LmsBrowseHelper.normalizeLibraryId(null));
        assertNull(LmsBrowseHelper.normalizeLibraryId(""));
        assertNull(LmsBrowseHelper.normalizeLibraryId("-1"));
        assertNull(LmsBrowseHelper.normalizeLibraryId("0"));
        assertNull(LmsBrowseHelper.normalizeLibraryId("__ALL__"));
    }

    @Test
    public void invalid() {
        assertNull(LmsBrowseHelper.normalizeLibraryId("a'b"));
        assertNull(LmsBrowseHelper.normalizeLibraryId("a b"));
        assertNull(LmsBrowseHelper.normalizeLibraryId("x');alert(1);('"));
    }

    @Test
    public void library() {
        assertEquals("8f1c2a3b", LmsBrowseHelper.normalizeLibraryId("8f1c2a3b"));
        assertEquals("8f1c2a3b", LmsBrowseHelper.normalizeLibraryId(" 8f1c2a3b "));
    }
}
