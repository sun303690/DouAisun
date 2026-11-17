package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 按用户规则隐藏原生评论。
 *
 * <p>过滤点在宿主评论分页结果 {@code CommentItemList.getItems()}：命中规则的评论项从返回值里剔除，
 * 未命中时原样返回宿主的列表，不做任何包装。宿主持有的原始数据模型不被改写，
 * 因此后续分页、刷新仍以服务端数据为准。
 *
 * <p>判定逻辑集中在 {@link CommentFilterMatcher}，该类不依赖 Android，便于用普通 JVM 单元测试覆盖。
 */
public final class CommentFilterHook implements AutoCloseable {
    private final Field textField;
    private final Field userField;
    private final Field userIdField;
    private final Field adField;
    private final Field hotField;
    private final Field highlightField;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private volatile LocalOptions options;

    private CommentFilterHook(Field textField, Field userField, Field userIdField, Field adField,
                             Field hotField, Field highlightField, LocalOptions options, Consumer<String> log) {
        this.textField = textField;
        this.userField = userField;
        this.userIdField = userIdField;
        this.adField = adField;
        this.hotField = hotField;
        this.highlightField = highlightField;
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static CommentFilterHook install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, LocalOptions options,
                                            Consumer<String> log) throws Exception {
        Class<?> commentClass = HostTargets.type(report, "comment_model", loader);
        Class<?> listClass = HostTargets.type(report, "comment_reply_thread_response", loader);
        Method getItems = listClass.getMethod("getItems");
        if (getItems == null || getItems.getReturnType() != List.class) {
            throw new IllegalStateException("Comment list accessor is unavailable");
        }
        Field textField = requireStringField(commentClass, "text");
        Field userField = HostTargets.field(commentClass, "user");
        Field adField = HostTargets.field(commentClass, "adComponent");
        Field hotField = HostTargets.field(commentClass, "isHot");
        Field highlightField = HostTargets.field(commentClass, "isHighlight");
        Field userIdField;
        try {
            userIdField = HostTargets.field(userField.getType(), "uid");
            if (userIdField.getType() != String.class) userIdField = null;
        } catch (NoSuchFieldException missing) {
            userIdField = null;
        }
        for (Field field : new Field[]{textField, userField, adField, hotField, highlightField}) {
            field.setAccessible(true);
        }
        if (userIdField != null) userIdField.setAccessible(true);

        CommentFilterHook hook = new CommentFilterHook(textField, userField, userIdField, adField,
                hotField, highlightField, options, log);
        try {
            hook.add(framework, getItems);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private void add(XposedInterface framework, Method getItems) throws Exception {
        XposedInterface.HookHandle handle = framework.hook(getItems).setId("DouAi.Comment.Filter")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object source = chain.proceed();
                    if (!(source instanceof List)) return source;
                    try {
                        List<?> filtered = filter((List<?>) source);
                        return filtered == null ? source : filtered;
                    } catch (Throwable failure) {
                        safeLog("Comment filter skipped: " + failure.getMessage());
                        return source;
                    }
                });
        if (handle == null) throw new IllegalStateException("Empty comment filter handle");
        handles.add(handle);
    }

    /** @return 过滤后的列表，无命中时返回 null 表示调用方应保留宿主原值。 */
    private List<Object> filter(List<?> source) throws ReflectiveOperationException {
        if (source.isEmpty()) return null;
        CommentFilterMatcher matcher = buildMatcher();
        if (matcher == null) return null;
        List<Object> kept = new ArrayList<>(source.size());
        int removed = 0;
        for (Object item : source) {
            if (item != null && matcher.shouldHide(describe(item))) {
                removed++;
                continue;
            }
            kept.add(item);
        }
        if (removed == 0) return null;
        if (kept.isEmpty()) {
            safeLog("Comment filter removed all " + removed + " item(s)");
            return Collections.emptyList();
        }
        safeLog("Comment filter removed " + removed + " item(s)");
        return kept;
    }

    /** Collects the comparable facts of one comment row. */
    private CommentFilterMatcher.Facts describe(Object comment) throws ReflectiveOperationException {
        CommentFilterMatcher.Facts facts = new CommentFilterMatcher.Facts();
        Object text = textField.get(comment);
        facts.text = text instanceof String ? (String) text : null;
        facts.authorId = readUserId(comment);
        facts.advertisement = adField.get(comment) != null;
        facts.hot = Boolean.TRUE.equals(hotField.get(comment));
        facts.highlight = Boolean.TRUE.equals(highlightField.get(comment));
        return facts;
    }

    private String readUserId(Object comment) throws ReflectiveOperationException {
        if (userIdField == null) return null;
        Object user = userField.get(comment);
        if (user == null) return null;
        Object uid = userIdField.get(user);
        return uid instanceof String ? (String) uid : null;
    }

    private CommentFilterMatcher buildMatcher() {
        LocalOptions current = options;
        if (current == null) return null;
        if (!current.enabled(LocalOptions.COMMENT_FILTER_ENABLED)) return null;
        boolean ads = current.enabled(LocalOptions.COMMENT_FILTER_ADS);
        boolean hot = current.enabled(LocalOptions.COMMENT_FILTER_HOT);
        String keywords = current.text(LocalOptions.COMMENT_FILTER_KEYWORDS, "");
        String authors = current.text(LocalOptions.COMMENT_FILTER_AUTHORS, "");
        String allow = current.text(LocalOptions.COMMENT_FILTER_ALLOW, "");
        if (!ads && !hot && keywords.trim().isEmpty() && authors.trim().isEmpty()) return null;
        return new CommentFilterMatcher(ads, hot, keywords, authors, allow);
    }

    private static Field requireStringField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = HostTargets.field(owner, name);
        if (field.getType() != String.class) {
            throw new IllegalStateException("Comment field is not a string: " + name);
        }
        return field;
    }

    public void setOptions(LocalOptions options) {
        this.options = options;
    }

    @Override public void close() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable failure) { safeLog("Comment filter unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void safeLog(String message) {
        Consumer<String> sink = log;
        if (sink == null) return;
        try { sink.accept(message); } catch (Throwable ignored) { }
    }
}
