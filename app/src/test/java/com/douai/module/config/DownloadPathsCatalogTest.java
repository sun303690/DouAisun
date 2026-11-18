package com.douai.module.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the download path settings that were ported from the reference module: the album
 * sub-folder, the system album fallback switch, the folder template and the date pattern.
 * Plain JUnit only - every assertion below is a pure function.
 */
public class DownloadPathsCatalogTest {

    @Test
    public void everyDownloadPathKeyIsRegisteredWithItsType() {
        assertEquals("string", ConfigKeys.TYPES.get(LocalOptions.DOWNLOAD_SUBDIRECTORY));
        assertEquals("boolean", ConfigKeys.TYPES.get(LocalOptions.DOWNLOAD_SYSTEM_ALBUM));
        assertEquals("string", ConfigKeys.TYPES.get(LocalOptions.DOWNLOAD_PATH_TEMPLATE));
        assertEquals("string", ConfigKeys.TYPES.get(LocalOptions.DOWNLOAD_DATE_FORMAT));
    }

    @Test
    public void defaultsMatchTheDocumentedBehaviour() {
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_SUBDIRECTORY,
                ConfigurationCatalog.settings().get(LocalOptions.DOWNLOAD_SUBDIRECTORY).defaultValue);
        assertEquals(false,
                ConfigurationCatalog.settings().get(LocalOptions.DOWNLOAD_SYSTEM_ALBUM).defaultValue);
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_PATH_TEMPLATE,
                ConfigurationCatalog.settings().get(LocalOptions.DOWNLOAD_PATH_TEMPLATE).defaultValue);
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_DATE_FORMAT,
                ConfigurationCatalog.settings().get(LocalOptions.DOWNLOAD_DATE_FORMAT).defaultValue);
    }

    @Test
    public void templateSanitiserDropsTraversalAndIllegalCharacters() {
        assertEquals("2026", LocalOptions.sanitisePathTemplate("2026"));
        assertEquals("etc/passwd", LocalOptions.sanitisePathTemplate("etc/passwd"));
        assertEquals("etcpasswd", LocalOptions.sanitisePathTemplate("etc\\passwd"));
        assertEquals("", LocalOptions.sanitisePathTemplate(".."));
        assertEquals("", LocalOptions.sanitisePathTemplate(""));
        assertEquals("", LocalOptions.sanitisePathTemplate(null));
    }

    @Test
    public void templateSanitiserKeepsTheDatePlaceholder() {
        assertTrue(LocalOptions.sanitisePathTemplate("#date#").contains("#date#"));
    }

    @Test
    public void dateFormatKeepsOnlyPatternLetters() {
        assertEquals("yyyyMMdd", LocalOptions.sanitiseDateFormat("yyyy-MM-dd"));
        assertEquals("yyyyMMddHHmmss", LocalOptions.sanitiseDateFormat("yyyy_MM_dd_HH_mm_ss"));
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_DATE_FORMAT, LocalOptions.sanitiseDateFormat(""));
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_DATE_FORMAT, LocalOptions.sanitiseDateFormat(null));
        assertEquals(LocalOptions.DEFAULT_DOWNLOAD_DATE_FORMAT,
                LocalOptions.sanitiseDateFormat("...###"));
    }
}
