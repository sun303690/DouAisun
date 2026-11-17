package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** 修改新构建的原生评论交互状态，不改动服务器返回或已打开的评论面板。 */
public final class CommentInteractionHook implements AutoCloseable {
    private final Field digg;
    private final Field bury;
    private final Field reply;
    private final Field input;
    private final Field itemClick;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile LocalOptions options;

    private CommentInteractionHook(Class<?> state, LocalOptions options, Consumer<String> log) throws Exception {
        this.digg = HostTargets.field(state, "isHasItemDigg");
        this.bury = HostTargets.field(state, "isHasItemBury");
        this.reply = HostTargets.field(state, "isHasItemReplyButton");
        this.input = HostTargets.field(state, "isHasBottomInputBar");
        this.itemClick = HostTargets.field(state, "isCanItemClick");
        for (Field field : new Field[]{digg, bury, reply, input, itemClick}) {
            if (field.getType() != boolean.class) throw new IllegalStateException("Not a boolean: " + field.getName());
        }
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
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
        if (current.enabled(LocalOptions.COMMENT_HIDE_DIGG)) digg.setBoolean(state, false);
        if (current.enabled(LocalOptions.COMMENT_HIDE_BURY)) bury.setBoolean(state, false);
        if (current.enabled(LocalOptions.COMMENT_HIDE_REPLY)) reply.setBoolean(state, false);
        if (current.enabled(LocalOptions.COMMENT_HIDE_INPUT)) input.setBoolean(state, false);
        if (current.enabled(LocalOptions.COMMENT_DISABLE_ITEM_CLICK)) itemClick.setBoolean(state, false);
    }

    @Override public void close() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable failure) { safeLog("Comment interaction unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}
