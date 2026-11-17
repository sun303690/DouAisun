package com.douai.module.feature;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a native comment has to be hidden by the comment filter.
 *
 * <p>The matcher is intentionally free of Android dependencies so the decision table can be
 * verified by plain JVM tests. Facts are collected by the hook and handed over as a value object.
 */
public final class CommentFilterMatcher {
    /** The comparable facts of one comment row. */
    public static final class Facts {
        /** Comment body text. */
        public String text;
        /** Author identifier, empty when the host does not expose one. */
        public String authorId;
        /** True when the comment carries an advertisement component. */
        public boolean advertisement;
        /** True when the host marks the comment as hot. */
        public boolean hot;
        /** True when the host marks the comment as highlighted. */
        public boolean highlight;
    }

    private final boolean adsEnabled;
    private final boolean hotEnabled;
    private final Set<String> keywords;
    private final Set<String> blockedAuthors;
    private final Set<String> allowedAuthors;

    public CommentFilterMatcher(boolean adsEnabled, boolean hotEnabled, String keywords,
                                String blockedAuthors, String allowedAuthors) {
        this.adsEnabled = adsEnabled;
        this.hotEnabled = hotEnabled;
        this.keywords = normalizeSet(keywords);
        this.blockedAuthors = normalizeSet(blockedAuthors);
        this.allowedAuthors = normalizeSet(allowedAuthors);
    }

    /** Returns {@code true} when the comment has to be hidden. */
    public boolean shouldHide(Facts facts) {
        if (facts == null) return false;
        String author = normalize(facts.authorId);
        if (!author.isEmpty() && allowedAuthors.contains(author)) return false;
        if (!author.isEmpty() && blockedAuthors.contains(author)) return true;
        if (adsEnabled && facts.advertisement) return true;
        if (hotEnabled && (facts.hot || facts.highlight)) return true;
        if (facts.text == null || facts.text.isEmpty()) return false;
        String haystack = facts.text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) return true;
        }
        return false;
    }

    /** Splits a comma separated list into trimmed, lower-cased, non-empty entries. */
    public static Set<String> normalizeSet(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split("[,，\\n]")) {
            String value = normalize(part);
            if (!value.isEmpty()) values.add(value);
        }
        return values.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
