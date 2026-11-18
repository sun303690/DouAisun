package com.douai.module.feature;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template engine for the feed information bar.
 *
 * <p>The placeholder set and the validation rules mirror the reference module: only
 * {@code time}, {@code location}, {@code duration}, {@code progress} and {@code originalText}
 * are accepted, the rendered text is capped at 256 characters and control characters are
 * rejected.</p>
 */
public final class PostBarTemplate {

    /** Maximum length of a rendered information bar line. */
    public static final int MAX_LENGTH = 256;

    /** Placeholder prefix used by every supported token. */
    public static final String PREFIX = "${";

    /** Placeholder suffix used by every supported token. */
    public static final String SUFFIX = "}";

    /** Placeholders understood by the information bar. */
    public static final List<String> PLACEHOLDERS = Collections.unmodifiableList(Arrays.asList(
            "time", "location", "duration", "progress", "originalText"));

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]*)\\}");

    /** Values substituted into a template. */
    public static final class Values {
        private final String time;
        private final String location;
        private final String duration;
        private final String progress;
        private final String originalText;

        private Values(String time, String location, String duration, String progress, String originalText) {
            this.time = time;
            this.location = location;
            this.duration = duration;
            this.progress = progress;
            this.originalText = originalText;
        }

        /** Builds a value holder, mapping every {@code null} to an empty string. */
        public static Values of(String time, String location, String duration, String progress, String originalText) {
            return new Values(clean(time), clean(location), clean(duration), clean(progress), clean(originalText));
        }

        private static String clean(String value) { return value == null ? "" : value; }

        String lookup(String name) {
            switch (name) {
                case "time": return time;
                case "location": return location;
                case "duration": return duration;
                case "progress": return progress;
                case "originalText": return originalText;
                default: return "";
            }
        }
    }

    private PostBarTemplate() { }

    /** Lists the placeholder names used by a template, in order of appearance. */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) return names;
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    /** Tells whether the template relies on the live playback progress. */
    public static boolean usesProgress(String template) {
        return placeholders(template).contains("progress");
    }

    /**
     * Validates a template the same way the reference module does.
     *
     * @param template the raw template
     * @return {@code true} when the template only uses supported placeholders
     */
    public static boolean isValid(String template) {
        if (template == null) return false;
        if (template.length() > MAX_LENGTH) return false;
        for (int i = 0; i < template.length(); i++) {
            if (Character.isISOControl(template.charAt(i))) return false;
        }
        // every "${" must open a supported token, otherwise the template is rejected
        int cursor = 0;
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            if (template.indexOf(PREFIX, cursor) != matcher.start()) return false;
            if (!PLACEHOLDERS.contains(matcher.group(1))) return false;
            cursor = matcher.end();
        }
        return template.indexOf(PREFIX, cursor) < 0;
    }

    /**
     * Renders a template.
     *
     * @param template the raw template
     * @param values   the values substituted into the placeholders
     * @return the rendered line, never {@code null}
     */
    public static String render(String template, Values values) {
        if (template == null || values == null) return "";
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = values.lookup(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Renders a template and caps the result at {@link #MAX_LENGTH} characters.
     *
     * @param template the raw template
     * @param values   the values substituted into the placeholders
     * @return the rendered line
     */
    public static String renderCapped(String template, Values values) {
        String rendered = render(template, values);
        if (rendered.length() <= MAX_LENGTH) return rendered;
        return rendered.substring(0, MAX_LENGTH);
    }
}
