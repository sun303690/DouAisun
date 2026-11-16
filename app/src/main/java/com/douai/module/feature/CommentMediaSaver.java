package com.douai.module.feature;

import android.content.Context;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Saves the media attached to a single comment. The host exposes an unwatermarked comment video
 * only through CommentVideoStruct#b (an Aweme), and a comment picture only through
 * CommentImageStruct#f (a UrlModel); neither is reachable from the native comment menu, so the
 * URLs are read here and written through MediaStoreDownloader.
 */
public final class CommentMediaSaver {
    private static final String IMAGE_URL_MODEL = "com.ss.android.ugc.aweme.base.model.UrlModel";

    private final Class<?> commentImageClass;
    private final Class<?> commentVideoClass;
    private final Field imageUrlField;
    private final Field videoAwemeField;
    private final Consumer<String> log;

    private CommentMediaSaver(Class<?> commentImageClass, Class<?> commentVideoClass,
                              Field imageUrlField, Field videoAwemeField, Consumer<String> log) {
        this.commentImageClass = commentImageClass;
        this.commentVideoClass = commentVideoClass;
        this.imageUrlField = imageUrlField;
        this.videoAwemeField = videoAwemeField;
        this.log = log == null ? ignored -> { } : log;
    }

    /** Returns null when the host no longer exposes either comment media anchor. */
    public static CommentMediaSaver install(HostScanReport report, ClassLoader loader,
                                            Consumer<String> log) {
        try {
            Class<?> imageClass = resolveClass(report, "comment_image_model", loader);
            Class<?> videoClass = resolveClass(report, "comment_video_model", loader);
            Field imageUrl = imageClass.getField("f");
            Field videoAweme = videoClass.getField("b");
            if (!isUrlModel(imageUrl) || !"com.ss.android.ugc.aweme.feed.model.Aweme".equals(
                    videoAweme.getType().getName())) {
                throw new IllegalStateException("Comment media fields changed");
            }
            return new CommentMediaSaver(imageClass, videoClass, imageUrl, videoAweme, log);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (log != null) log.accept("Comment media anchors unavailable: " + unavailable.getMessage());
            return null;
        }
    }

    /** One media entry in the comment, in the on-screen order. */
    public static final class Asset {
        public final String url;
        public final boolean image;
        public final int index;

        Asset(String url, boolean image, int index) {
            this.url = url;
            this.image = image;
            this.index = index;
        }
    }

    public boolean hasMedia(Object comment) {
        return collect(comment, false).size() > 0;
    }

    /**
     * Lists the media URLs of one comment. Only https/http URLs survive, because the downloader
     * rejects everything else and a partial save would lose the gallery order.
     */
    public List<Asset> collect(Object comment, boolean imagesOnly) {
        ArrayList<Asset> assets = new ArrayList<>();
        if (comment == null) return assets;
        try {
            List<?> pictures = listField(comment, "image_list");
            int index = 0;
            for (Object picture : pictures) {
                if (!commentImageClass.isInstance(picture)) continue;
                index++;
                String url = firstUrl(imageUrlField.get(picture));
                if (MediaStoreDownloader.isHttpUrl(url)) assets.add(new Asset(url, true, index));
            }
            if (imagesOnly) return assets;
            List<?> videos = listField(comment, "video_list");
            if (!videos.isEmpty()) {
                Object first = videos.get(0);
                if (commentVideoClass.isInstance(first)) {
                    Object aweme = videoAwemeField.get(first);
                    String url = aweme == null ? null : videoUrl(aweme);
                    if (MediaStoreDownloader.isHttpUrl(url)) assets.add(new Asset(url, false, 0));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            DouAiLog.failure("comment_media", "collect", failure);
            log.accept("Could not read comment media: " + failure.getMessage());
            return new ArrayList<>();
        }
        return assets;
    }

    /** Saves every asset of the comment. Returns the number of downloads accepted by MediaStore. */
    public int save(Context context, Object comment) {
        DouAiLog.Operation operation = DouAiLog.begin("comment_media", "save_comment_media");
        List<Asset> assets = collect(comment, false);
        if (assets.isEmpty()) {
            operation.finish(DouAiLog.Outcome.SKIPPED, "no_comment_media", "assets=0");
            return 0;
        }
        if (context == null) {
            operation.finish(DouAiLog.Outcome.FAILED, "no_context", "assets=" + assets.size());
            return 0;
        }
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        String stem = fileNameStem(comment);
        int saved = 0;
        for (Asset asset : assets) {
            String name = asset.image ? stem + "_" + asset.index + ".jpg" : stem + ".mp4";
            boolean ok = asset.image
                    ? MediaStoreDownloader.downloadImage(app, asset.url, name, log)
                    : MediaStoreDownloader.download(app, asset.url, name, false, log);
            if (ok) saved++;
        }
        operation.finish(saved == assets.size() ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.FAILED,
                saved == 0 ? "download_rejected" : "download_submitted",
                "assets=" + assets.size() + ";saved=" + saved + ";images_only=false");
        return saved;
    }

    private static String fileNameStem(Object comment) {
        String text = null;
        try {
            Object value = jsonValue(comment, "text");
            if (value instanceof String) text = ((String) value).trim();
        } catch (RuntimeException ignored) {
            text = null;
        }
        if (text == null || text.isEmpty()) return "comment";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < text.length() && builder.length() < 24; index++) {
            char value = text.charAt(index);
            if (Character.isLetterOrDigit(value)) builder.append(value);
        }
        return builder.length() == 0 ? "comment" : "comment_" + builder;
    }

    private static String videoUrl(Object aweme) {
        try {
            Object video = jsonValue(aweme, "video");
            String url = video == null ? null : firstUrlValue(jsonValue(video, "play_addr"));
            if (url == null && video != null) url = firstUrlValue(jsonValue(video, "download_addr"));
            return url;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object jsonValue(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!name.equals(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                String methodName = method.getName();
                if (method.getParameterCount() != 0
                        || !(methodName.equals(name) || methodName.equals("get" + capitalize(name))
                        || methodName.equals("get_" + name))) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstUrlValue(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        Object raw = value instanceof Iterable<?> ? value : jsonValue(value, "url_list");
        if (!(raw instanceof Iterable<?>)) return null;
        for (Object entry : (Iterable<?>) raw) {
            if (!(entry instanceof String)) continue;
            String text = ((String) entry).trim();
            if (!text.isEmpty()) return text;
        }
        return null;
    }

    private static String firstUrl(Object urlModel) {
        return urlModel == null ? null : firstUrlValue(urlModel);
    }

    private static List<?> listField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!name.equals(field.getName())) continue;
                field.setAccessible(true);
                Object value = field.get(target);
                return value instanceof List ? (List<?>) value : new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    private static boolean isUrlModel(Field field) {
        for (Class<?> type = field.getType(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (IMAGE_URL_MODEL.equals(type.getName())) return true;
        }
        return false;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Class<?> resolveClass(HostScanReport report, String ruleId,
                                         ClassLoader loader) throws ClassNotFoundException {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        if (!candidate.getMethodName().isEmpty()) {
            throw new IllegalStateException("Expected a class rule: " + ruleId);
        }
        String descriptor = candidate.getClassDescriptor();
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'),
                false, loader);
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }
}
