package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 修改原生评论项的独立显隐与交互能力开关。
 *
 * <p>宿主把评论项的可见性与可点击性集中在评论交互状态对象
 * （规则锚点 {@code comment_interaction_state}）里，这里按用户开关改写对应布尔位，
 * 宿主在构造评论行时便会读取到新的取值。宿主该对象共 19 个字段，
 * 本类只使用其中已核实存在的开关位，未打开的开关保持宿主原值。
 */
public final class CommentItemVisibilityHook implements AutoCloseable {
    private final Field hideItemDigg;
    private final Field hideItemDiggCount;
    private final Field hideItemDiggCountExcludeMySelf;
    private final Field canAvatarClick;
    private final Field canItemLongClick;
    private final Field canShowExternalBar;
    private final Field forceShowTitle;
    private final Field showCommentCount;
    private final Field needAvatarVerify;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile LocalOptions options;

    private CommentItemVisibilityHook(Class<?> state, LocalOptions options, Consumer<String> log) throws Exception {
        this.hideItemDigg = HostTargets.field(state, "hideItemDigg");
        this.hideItemDiggCount = HostTargets.field(state, "hideItemDiggCount");
        this.hideItemDiggCountExcludeMySelf = HostTargets.field(state, "hideItemDiggCountExcludeMySelf");
        this.canAvatarClick = HostTargets.field(state, "isCanAvatarClick");
        this.canItemLongClick = HostTargets.field(state, "isCanItemLongClick");
        this.canShowExternalBar = HostTargets.field(state, "isCanShowExternalBar");
        this.forceShowTitle = HostTargets.field(state, "isForceShowTitle");
        this.showCommentCount = HostTargets.field(state, "showCommentCount");
        this.needAvatarVerify = HostTargets.field(state, "needAvatarVerify");
        for (Field field : new Field[]{hideItemDigg, hideItemDiggCount, hideItemDiggCountExcludeMySelf,
                canAvatarClick, canItemLongClick, canShowExternalBar, forceShowTitle, showCommentCount,
                needAvatarVerify}) {
            if (field.getType() != boolean.class) {
                throw new IllegalStateException("Not a boolean: " + field.getName());
            }
            field.setAccessible(true);
        }
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static CommentItemVisibilityHook install(XposedInterface framework, HostScanReport report,
                                                    ClassLoader loader, LocalOptions options,
                                                    Consumer<String> log) throws Exception {
        Class<?> state = HostTargets.type(report, "comment_interaction_state", loader);
        Class<?> builder = HostTargets.type(report, "comment_interaction_builder", loader);
        Constructor<?> fromBuilder = state.getDeclaredConstructor(builder);
        fromBuilder.setAccessible(true);
        CommentItemVisibilityHook hook = new CommentItemVisibilityHook(state, options, log);
        try {
            hook.add(framework, fromBuilder);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private void add(XposedInterface framework, Constructor<?> constructor) throws Exception {
        XposedInterface.HookHandle handle = framework.hook(constructor).setId("DouAi.Comment.ItemVisibility")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        apply(chain.getThisObject());
                    } catch (Throwable failure) {
                        safeLog("Comment item visibility unchanged: " + failure.getMessage());
                    }
                    return result;
                });
        if (handle == null) throw new IllegalStateException("Empty comment item visibility handle");
        handles.add(handle);
    }

    private void apply(Object state) throws ReflectiveOperationException {
        if (state == null) return;
        LocalOptions current = options;
        if (current == null) return;
        if (current.enabled(LocalOptions.COMMENT_HIDE_DIGG_COUNT)) {
            hideItemDiggCount.setBoolean(state, true);
        }
        if (current.enabled(LocalOptions.COMMENT_HIDE_DIGG_COUNT_EXCLUDE_SELF)) {
            hideItemDiggCountExcludeMySelf.setBoolean(state, true);
        }
        if (current.enabled(LocalOptions.COMMENT_DISABLE_AVATAR_CLICK)) {
            canAvatarClick.setBoolean(state, false);
        }
        if (current.enabled(LocalOptions.COMMENT_DISABLE_ITEM_LONG_CLICK)) {
            canItemLongClick.setBoolean(state, false);
        }
        if (current.enabled(LocalOptions.COMMENT_HIDE_EXTERNAL_BAR)) {
            canShowExternalBar.setBoolean(state, false);
        }
        if (current.enabled(LocalOptions.COMMENT_FORCE_SHOW_TITLE)) {
            forceShowTitle.setBoolean(state, true);
        }
        if (current.enabled(LocalOptions.COMMENT_SHOW_COMMENT_COUNT)) {
            showCommentCount.setBoolean(state, true);
        }
        if (current.enabled(LocalOptions.COMMENT_DISABLE_AVATAR_VERIFY)) {
            needAvatarVerify.setBoolean(state, false);
        }
    }

    public void setOptions(LocalOptions options) {
        this.options = options;
    }

    @Override public void close() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable failure) { safeLog("Comment item visibility unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}
