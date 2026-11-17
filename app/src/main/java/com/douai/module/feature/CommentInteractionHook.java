package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** 修改新构建的原生评论交互状态，不改动服务器返回或已打开的评论面板。 */
public final class CommentInteractionHook implements AutoCloseable {
    /** 一条开关规则：配置开启时把宿主布尔字段写成指定值。 */
    private static final class Rule {
        final Field field;
        final boolean value;
        Rule(Field field, boolean value) { this.field = field; this.value = value; }
    }

    /** 配置键到字段规则的对应表；缺字段只跳过该项，不影响其余开关。 */
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile LocalOptions options;

    private CommentInteractionHook(Class<?> state, LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
        // 交互类字段：false 即关闭按钮或点击响应。
        bind(state, "isHasItemDigg", LocalOptions.COMMENT_HIDE_DIGG, false);
        bind(state, "isHasItemBury", LocalOptions.COMMENT_HIDE_BURY, false);
        bind(state, "isHasItemReplyButton", LocalOptions.COMMENT_HIDE_REPLY, false);
        bind(state, "isHasBottomInputBar", LocalOptions.COMMENT_HIDE_INPUT, false);
        bind(state, "isCanItemClick", LocalOptions.COMMENT_DISABLE_ITEM_CLICK, false);
        bind(state, "isCanItemLongClick", LocalOptions.COMMENT_DISABLE_ITEM_LONG_CLICK, false);
        bind(state, "isCanAvatarClick", LocalOptions.COMMENT_DISABLE_AVATAR_CLICK, false);
        bind(state, "isCanShowExternalBar", LocalOptions.COMMENT_HIDE_EXTERNAL_BAR, false);
        bind(state, "needAvatarVerify", LocalOptions.COMMENT_DISABLE_AVATAR_VERIFY, false);
        // 宿主自身的隐藏与强制显示标记，true 才是生效值。
        bind(state, "hideItemDiggCount", LocalOptions.COMMENT_HIDE_DIGG_COUNT, true);
        bind(state, "hideItemDiggCountExcludeMySelf", LocalOptions.COMMENT_HIDE_DIGG_COUNT_EXCLUDE_SELF, true);
        bind(state, "showCommentCount", LocalOptions.COMMENT_SHOW_COMMENT_COUNT, true);
        bind(state, "isForceShowTitle", LocalOptions.COMMENT_FORCE_SHOW_TITLE, true);
    }

    private void bind(Class<?> state, String fieldName, String key, boolean value) {
        Field field;
        try {
            field = HostTargets.field(state, fieldName);
        } catch (NoSuchFieldException missing) {
            safeLog("Comment field missing: " + fieldName);
            return;
        }
        if (field.getType() != boolean.class) {
            safeLog("Comment field is not a boolean: " + fieldName);
            return;
        }
        rules.put(key, new Rule(field, value));
    }

    public static CommentInteractionHook install(XposedInterface framework, HostScanReport report,
                                                  ClassLoader loader, LocalOptions options,
                                                  Consumer<String> log) throws Exception {
        Class<?> state = HostTargets.type(report, "comment_interaction_state", loader);
        Class<?> builder = HostTargets.type(report, "comment_interaction_builder", loader);
        Constructor<?> fromBuilder = state.getDeclaredConstructor(builder);
        fromBuilder.setAccessible(true);
        CommentInteractionHook hook = new CommentInteractionHook(state, options, log);
        try {
            hook.add(framework, fromBuilder, "DouAi.Comment.InteractionState");
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private void add(XposedInterface framework, Constructor<?> constructor, String id) throws Exception {
        XposedInterface.HookHandle handle = framework.hook(constructor).setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try { apply(chain.getThisObject()); }
                    catch (Throwable failure) { safeLog("Comment interaction unchanged: " + failure.getMessage()); }
                    return result;
                });
        if (handle == null) throw new IllegalStateException("Empty comment interaction handle");
        handles.add(handle);
    }

    public void setOptions(LocalOptions options) { this.options = options; }

    private void apply(Object state) throws IllegalAccessException {
        if (state == null) return;
        LocalOptions current = options;
        if (current == null) return;
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            if (!current.enabled(entry.getKey())) continue;
            Rule rule = entry.getValue();
            rule.field.setBoolean(state, rule.value);
        }
    }

    @Override public void close() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable failure) { safeLog("Comment interaction unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void safeLog(String message) {
        Consumer<String> sink = log;
        if (sink == null) return;
        try { sink.accept(message); } catch (Throwable ignored) { }
    }
}
