package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds the relative folder path used below a MediaStore download root.
 * The template never carries a file extension and never escapes the root it is appended to.
 */
public final class DownloadPathBuilder {
    private DownloadPathBuilder() { }

    /**
     * Album segment below the system folder: the configured sub-folder, or an empty string when the
     * user asked to keep the system album untouched.
     */
    public static String albumSegment(LocalOptions options) {
        if (options == null) return LocalOptions.DEFAULT_DOWNLOAD_SUBDIRECTORY;
        if (options.downloadSystemAlbum()) return "";
        return options.downloadSubdirectory();
    }

    /**
     * Relative path for a media row: the system directory, the album segment and the templated
     * folders. The result never ends with a separator and never contains a traversal segment.
     */
    public static String relativePath(LocalOptions options, String systemDirectory, long nowMillis) {
        String sub = subPath(options, nowMillis);
        if (systemDirectory == null || systemDirectory.isEmpty()) return sub;
        return sub.isEmpty() ? systemDirectory : systemDirectory + '/' + sub;
    }

    /**
     * Path below the system directory: the album segment plus the templated folders.
     * Empty when the user asked to keep the system album and no template is configured.
     */
    public static String subPath(LocalOptions options, long nowMillis) {
        StringBuilder path = new StringBuilder();
        String album = albumSegment(options);
        if (!album.isEmpty()) {
            path.append(album);
            path.append('/');
        }
        String folder = templateFolder(options, nowMillis);
        if (!folder.isEmpty()) {
            path.append(folder);
            path.append('/');
        }
        String result = path.toString();
        return result.isEmpty() ? "" : result.substring(0, result.length() - 1);
    }

    /**
     * Expands the configured folder template into a single relative folder.
     * The template is sanitised first, then the {@code #date#} placeholder renders the configured
     * date pattern. Only path separators are removed afterwards, so a time stamp may keep its colons.
     */
    public static String templateFolder(LocalOptions options, long nowMillis) {
        if (options == null) return "";
        String template = options.downloadPathTemplate();
        if (template.isEmpty()) return "";
        String date = new SimpleDateFormat(options.downloadDateFormat(), Locale.ROOT).format(new Date(nowMillis));
        String expanded = template.replace("#date#", date).replace("{date}", date);
        return expanded.replace("/", "").replace("\\", "").replace(":", "-");
    }
}
