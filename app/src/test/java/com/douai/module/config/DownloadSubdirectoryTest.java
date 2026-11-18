package com.douai.module.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the album sub-folder sanitiser. Kept as a plain JUnit test (no Robolectric) because the
 * logic under test is a pure function and Robolectric cannot boot inside the sandbox.
 */
public class DownloadSubdirectoryTest {

    @Test
    public void blankFallsBackToDefault() {
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory(""));
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory("   "));
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory(null));
    }

    @Test
    public void plainNameIsKept() {
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory("DouAi"));
        assertEquals("MyVideos", LocalOptions.sanitiseSubdirectory("MyVideos"));
        assertEquals("中文目录", LocalOptions.sanitiseSubdirectory("中文目录"));
    }

    @Test
    public void pathSeparatorsAreStripped() {
        assertEquals("etcpasswd", LocalOptions.sanitiseSubdirectory("etc/passwd"));
        assertEquals("etcpasswd", LocalOptions.sanitiseSubdirectory("etc\\passwd"));
        assertEquals("abcd", LocalOptions.sanitiseSubdirectory("a:b*c?d"));
        assertEquals("abcde", LocalOptions.sanitiseSubdirectory("a\"b<c>d|e"));
    }

    @Test
    public void dotSegmentsFallBackToDefault() {
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory("."));
        assertEquals("DouAi", LocalOptions.sanitiseSubdirectory(".."));
    }

    @Test
    public void lengthIsCappedAtForty() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) longName.append('a');
        assertEquals(40, LocalOptions.sanitiseSubdirectory(longName.toString()).length());
    }
}
