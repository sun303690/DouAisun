package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers the cache key mapping used by the local video proxy. */
public class VideoCacheKeyTest {

    @Test
    public void blankUrlHasNoKey() {
        assertNull(VideoCacheKey.keyFor(null));
        assertNull(VideoCacheKey.keyFor("   "));
        assertNull(VideoCacheKey.fileNameFor(null));
        assertNull(VideoCacheKey.fileNameFor(""));
    }

    @Test
    public void sameUrlProducesSameKey() {
        String first = VideoCacheKey.keyFor("https://v.example.com/video/123.mp4");
        String second = VideoCacheKey.keyFor("https://v.example.com/video/123.mp4");
        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(first + ".mp4", VideoCacheKey.fileNameFor(first));
    }

    @Test
    public void signatureParametersDoNotChangeTheKey() {
        String signed = VideoCacheKey.keyFor(
                "https://v.example.com/video/123.mp4?sign=aaa&t=1");
        String resigned = VideoCacheKey.keyFor(
                "https://v.example.com/video/123.mp4?sign=bbb&t=2");
        assertEquals(signed, resigned);
    }

    @Test
    public void differentPathsProduceDifferentKeys() {
        assertFalse(VideoCacheKey.keyFor("https://v.example.com/video/1.mp4")
                .equals(VideoCacheKey.keyFor("https://v.example.com/video/2.mp4")));
    }

    @Test
    public void normalisationKeepsThePathAndDropsTheQuery() {
        assertEquals("https://v.example.com/video/123.mp4",
                VideoCacheKey.normalise("https://v.example.com/video/123.mp4?sign=abc"));
        assertEquals("https://v.example.com/video/123.mp4",
                VideoCacheKey.normalise("HTTPS://V.EXAMPLE.COM/video/123.mp4"));
    }

    @Test
    public void normalisationKeepsThePort() {
        assertEquals("http://127.0.0.1:8080/video/1.mp4",
                VideoCacheKey.normalise("http://127.0.0.1:8080/video/1.mp4"));
    }

    @Test
    public void normalisationHandlesUrlsWithoutAPath() {
        assertEquals("https://v.example.com", VideoCacheKey.normalise("https://v.example.com"));
        assertTrue(VideoCacheKey.keyFor("https://v.example.com").length() == 64);
    }
}