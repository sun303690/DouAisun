package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers the pure template engine behind the feed information bar. */
public class PostBarTemplateTest {

    @Test
    public void rendersEverySupportedPlaceholder() {
        PostBarTemplate.Values values = PostBarTemplate.Values.of(
                "2024-05-01 12:00:00", "广东", "01:30", "42", "");
        String rendered = PostBarTemplate.render("${time} ${location} ${duration} ${progress}", values);
        assertEquals("2024-05-01 12:00:00 广东 01:30 42", rendered);
    }

    @Test
    public void dropsUnknownPlaceholders() {
        PostBarTemplate.Values values = PostBarTemplate.Values.of("t", "l", "d", "p", "");
        String rendered = PostBarTemplate.render("${time} ${unknown} ${author}", values);
        assertEquals("t", rendered.trim());
    }

    @Test
    public void keepsUnknownBracesAsLiteralText() {
        PostBarTemplate.Values values = PostBarTemplate.Values.of("t", "l", "d", "p", "");
        String rendered = PostBarTemplate.render("hello {world}", values);
        assertEquals("hello {world}", rendered.trim());
    }

    @Test
    public void missingValuesCollapseToEmpty() {
        PostBarTemplate.Values values = PostBarTemplate.Values.of("", "", "", "", "");
        String rendered = PostBarTemplate.render("${time}|${location}", values);
        assertEquals("|", rendered.trim());
    }

    @Test
    public void capsRenderedLength() {
        StringBuilder template = new StringBuilder();
        for (int i = 0; i < 200; i++) template.append("x");
        PostBarTemplate.Values values = PostBarTemplate.Values.of("t", "l", "d", "p", "");
        String rendered = PostBarTemplate.renderCapped(template.toString(), values);
        assertTrue(rendered.length() <= PostBarTemplate.MAX_LENGTH);
    }
}
