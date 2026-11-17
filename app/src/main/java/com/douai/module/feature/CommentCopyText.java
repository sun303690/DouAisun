package com.douai.module.feature;

/** Removes only a reply mention at the beginning of copied comment text. */
public final class CommentCopyText {
    private CommentCopyText() { }

    public static String stripLeadingMention(String value) {
        if (value == null || value.isEmpty()) return value;
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        if (start >= value.length() || value.charAt(start) != '@') return value;
        int end = start + 1;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (Character.isWhitespace(c) || c == ':' || c == '：' || c == '，' || c == ',') break;
            if (c == '@' || c == '\n' || c == '\r') return value;
            end++;
        }
        if (end == start + 1 || end == value.length() || end - start > 65) return value;
        char delimiter = value.charAt(end);
        if (delimiter != ':' && delimiter != '：' && delimiter != '，' && delimiter != ','
                && !Character.isWhitespace(delimiter)) return value;
        int content = end;
        if (content < value.length() && (value.charAt(content) == ':' || value.charAt(content) == '：'
                || value.charAt(content) == '，' || value.charAt(content) == ',')) content++;
        while (content < value.length() && Character.isWhitespace(value.charAt(content))) content++;
        return content == value.length() ? value : value.substring(content);
    }
}
