package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Live room clean mode. Hides the gift tray, gift effects, public screen, ranking list, commerce
 * cards and ad widgets so only the video remains, mirroring the DouX live clean switches.
 *
 * <p>The live room is a widget tree: every panel is a subclass of
 * {@code LiveRecyclableWidget}, so one hook on its lifecycle is enough to hide each panel whose
 * switch is on. Matching is done on the widget class name, which the live SDK keeps readable even
 * though the surrounding code is obfuscated.
 */
public final class LiveCleanHook {
    public static final String OPTION = "live_clean";
    public static final String OPTION_GIFT_TRAY = "live_clean_gift_tray";
    public static final String OPTION_GIFT_EFFECT = "live_clean_gift_effect";
    public static final String OPTION_PUBLIC_SCREEN = "live_clean_public_screen";
    public static final String OPTION_RANK = "live_clean_rank";
    public static final String OPTION_COMMERCE = "live_clean_commerce";
    public static final String OPTION_AD = "live_clean_ad";

    private static final String RECYCLABLE_WIDGET =
            "com.bytedance.android.live.core.tetris.widgets.LiveRecyclableWidget";

    /** Switch key to the class name fragment that identifies the panel. */
    private static final Map<String, String> COMPONENTS = new LinkedHashMap<>();

    static {
        COMPONENTS.put(OPTION_GIFT_TRAY, "gifttraywidget");
        COMPONENTS.put(OPTION_GIFT_EFFECT, "gifteffect");
        COMPONENTS.put(OPTION_PUBLIC_SCREEN, "publicscreen");
        COMPONENTS.put(OPTION_RANK, "rankwidget");
        COMPONENTS.put(OPTION_COMMERCE, "commerce");
        COMPONENTS.put(OPTION_AD, "liveadwidget");
    }

    private final XposedInterface framework;
    private final LocalOptions options;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    public LiveCleanHook(XposedInterface framework, LocalOptions options, Consumer<String> log) {
        this.framework = framework;
        this.options = options;
        this.log = log;
    }

    public void install() {
        try {
            Class<?> widget = Class.forName(RECYCLABLE_WIDGET, false, null);
            hookLifecycle(widget);
        } catch (Throwable missing) {
            log.accept("live clean: widget base absent: " + missing);
        }
    }

    private void hookLifecycle(Class<?> widget) {
        int hooked = 0;
        for (java.lang.reflect.Method method : widget.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("onCreate") && !name.equals("onAttachedToWindow")) continue;
            try {
                handles.add(framework.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    applyTo(chain.getThisObject());
                    return result;
                }));
                hooked++;
            } catch (Throwable failure) {
                log.accept("live clean: hook " + name + " failed: " + failure);
            }
        }
        if (hooked == 0) log.accept("live clean: no lifecycle seam on the widget base");
    }

    public void applyOptions(LocalOptions ignored) {
        if (!enabled() && !anyComponentEnabled()) return;
        log.accept("live clean: preferences applied");
    }

    /** Hides the panel when its switch is on. Safe to call repeatedly. */
    public void applyTo(Object widget) {
        if (!enabled() || widget == null) return;
        try {
            String name = widget.getClass().getName().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : COMPONENTS.entrySet()) {
                if (!options.enabled(entry.getKey())) continue;
                if (!name.contains(entry.getValue())) continue;
                Object view = readField(widget, "contentView");
                if (!(view instanceof android.view.View)) view = readField(widget, "containerView");
                if (!(view instanceof android.view.View)) view = widget;
                ((android.view.View) view).setVisibility(android.view.View.GONE);
                log.accept("live clean hid " + entry.getKey());
                return;
            }
        } catch (RuntimeException failure) {
            log.accept("live clean apply failed: " + failure);
        }
    }

    public void unhook() {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        handles.clear();
    }

    private boolean enabled() {
        return options.enabled(OPTION);
    }

    private boolean anyComponentEnabled() {
        for (String key : COMPONENTS.keySet()) {
            if (options.enabled(key)) return true;
        }
        return false;
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                type = type.getSuperclass();
            } catch (Throwable failure) {
                return null;
            }
        }
        return null;
    }
}
