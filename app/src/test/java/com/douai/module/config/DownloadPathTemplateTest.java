package com.douai.module.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Covers the path-template and date-format sanitisers used by the download folder builder.
 * Plain JUnit only: the logic under test is pure and Robolectric cannot boot in the sandbox.
 */
public class DownloadPathTemplateTest {

    @Test
    public void blankTemplateFallsBackToDefault() {
        assertEquals("", LocalOptions.sanitisePathTemplate(""));
        assertEquals("", LocalOptions.sanitisePathTemplate("   "));
        assertEquals("", LocalOptions.sanitisePathTemplate(null));
    }

    @Test
    public void plainFolderIsKept() {
        assertEquals("Videos", LocalOptions.sanitisePathTemplate("Videos"));
        assertEquals("a/b", LocalOptions.sanitisePathTemplate("a/b"));
        assertEquals("中文目录", LocalOptions.sanitisePathTemplate("中文目录"));
    }

    @Test
    public void traversalSegmentsAreDropped() {
        assertEquals("b", LocalOptions.sanitisePathTemplate("../b"));
        assertEquals("a/c", LocalOptions.sanitisePathTemplate("a/./c"));
        assertEquals("", LocalOptions.sanitisePathTemplate(".."));
    }

    @Test
    public void illegalCharactersAreStripped() {
        assertEquals("abcd", LocalOptions.sanitisePathTemplate("a:b*c?d"));
        assertEquals("abcde", LocalOptions.sanitisePathTemplate("a\"b<c>d|e"));
        assertEquals("ab", LocalOptions.sanitisePathTemplate("a\\b"));
    }

    @Test
    public void segmentLengthIsCappedAtForty() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) longName.append('a');
        assertEquals(40, LocalOptions.sanitisePathTemplate(longName.toString()).length());
    }

    @Test
    public void dateFormatKeepsLettersAndFallsBack() {
        assertEquals("yyyyMMdd", LocalOptions.sanitiseDateFormat("yyyy-MM-dd"));
        assertEquals("yyyyMMdd", LocalOptions.sanitiseDateFormat("yyyy-MM-dd"));
        assertEquals("yyyy-MM-dd_HH-mm-ss", LocalOptions.sanitiseDateFormat(""));
        assertEquals("yyyy-MM-dd_HH-mm-ss", LocalOptions.sanitiseDateFormat(null));
        assertEquals("yyyy-MM-dd_HH-mm-ss", LocalOptions.sanitiseDateFormat("12345"));
    }

    @Test
    public void optionsReadConfiguredValues() {
        Map<String, Object> values = new HashMap<>();
        values.put(LocalOptions.DOWNLOAD_SYSTEM_ALBUM, Boolean.TRUE);
        values.put(LocalOptions.DOWNLOAD_PATH_TEMPLATE, "shots/#date#");
        values.put(LocalOptions.DOWNLOAD_DATE_FORMAT, "yyyyMMdd");
        LocalOptions options = new LocalOptions(values);
        assertTrue(options.downloadSystemAlbum());
        assertEquals("shots/#date#", options.downloadPathTemplate());
        assertEquals("yyyyMMdd", options.downloadDateFormat());
    }
}
