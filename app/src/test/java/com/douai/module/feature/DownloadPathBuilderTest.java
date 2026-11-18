package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.douai.module.config.LocalOptions;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Covers the download folder builder: the album segment, the system-album switch and the
 * date placeholder expansion. Plain JUnit only, no Android framework involved.
 */
public class DownloadPathBuilderTest {

    private static LocalOptions options(String subdirectory, boolean systemAlbum, String template, String format) {
        Map<String, Object> values = new HashMap<>();
        values.put(LocalOptions.DOWNLOAD_SUBDIRECTORY, subdirectory);
        values.put(LocalOptions.DOWNLOAD_SYSTEM_ALBUM, systemAlbum);
        values.put(LocalOptions.DOWNLOAD_PATH_TEMPLATE, template);
        values.put(LocalOptions.DOWNLOAD_DATE_FORMAT, format);
        return new LocalOptions(values);
    }

    @Test
    public void albumSegmentUsesConfiguredName() {
        assertEquals("DouAi", DownloadPathBuilder.albumSegment(options("DouAi", false, "", "")));
        assertEquals("Mine", DownloadPathBuilder.albumSegment(options("Mine", false, "", "")));
    }

    @Test
    public void systemAlbumSwitchClearsTheSegment() {
        assertEquals("", DownloadPathBuilder.albumSegment(options("DouAi", true, "", "")));
    }

    @Test
    public void relativePathCombinesDirectoryAlbumAndTemplate() {
        LocalOptions options = options("DouAi", false, "shots", "yyyyMMdd");
        assertEquals("DCIM/DouAi/shots",
                DownloadPathBuilder.relativePath(options, "DCIM", 0L));
    }

    @Test
    public void systemAlbumKeepsOnlyTheDirectory() {
        LocalOptions options = options("DouAi", true, "", "");
        assertEquals("DCIM", DownloadPathBuilder.relativePath(options, "DCIM", 0L));
    }

    @Test
    public void templateIsAppendedBelowTheAlbum() {
        LocalOptions options = options("DouAi", false, "shots", "yyyyMMdd");
        assertEquals("DouAi/shots", DownloadPathBuilder.subPath(options, 0L));
    }

    @Test
    public void datePlaceholderIsExpanded() {
        LocalOptions options = options("DouAi", false, "#date#", "yyyy");
        String folder = DownloadPathBuilder.templateFolder(options, 0L);
        assertTrue(folder, folder.matches("\\d{4}"));
    }

    @Test
    public void templateCannotIntroduceSeparators() {
        LocalOptions options = options("DouAi", false, "a/b", "yyyy");
        assertEquals("ab", DownloadPathBuilder.templateFolder(options, 0L));
    }

    @Test
    public void emptyOptionsFallBackToDefaults() {
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_SUBDIRECTORY,
                DownloadPathBuilder.albumSegment(null));
        assertEquals("", DownloadPathBuilder.templateFolder(null, 0L));
    }
}
