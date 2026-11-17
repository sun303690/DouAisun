package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class CommentCopyTextTest {
    @Test public void stripsOnlyLeadingMention() {
        assertEquals("你好", CommentCopyText.stripLeadingMention("@张三 你好"));
        assertEquals("你好", CommentCopyText.stripLeadingMention("@张三：你好"));
        assertEquals("你好", CommentCopyText.stripLeadingMention(" @张三, 你好"));
        assertEquals("你好 @李四", CommentCopyText.stripLeadingMention("@张三 你好 @李四"));
    }

    @Test public void leavesUnrelatedTextUntouched() {
        for (String value : new String[]{"普通评论 @张三", "@张三", "@张三@李四 你好", "@张三你好", "@张三 ", ""}) {
            assertEquals(value, CommentCopyText.stripLeadingMention(value));
        }
        assertEquals(null, CommentCopyText.stripLeadingMention(null));
    }
}
