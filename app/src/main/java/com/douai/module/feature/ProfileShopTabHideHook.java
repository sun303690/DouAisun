package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Drops the shop / 商城 tab from the profile tab list (Dyvv {@code block_profile_shop_default}).
 *
 * <p>Anchor: {@code LX/1EBE;->LJJJJLL(Lcom/ss/android/ugc/aweme/profile/model/User;)Ljava/util/List;}
 * returns the tab descriptors the profile pager renders. Each descriptor is a
 * {@code ProfileTabItem} whose {@code b} field carries the display name and {@code d} the id.
 * The hook copies the list, drops shop entries, and never mutates the host's own collection.</p>
 */
public final class ProfileShopTabHideHook {

    static final String RULE_TABS = "adv_profile_shop_tab_hide";

    private static final String[] SHOP_TOKENS = {"shop", "mall", "商城", "橱窗"};

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile ExtendedSettings settings;

    private ProfileShopTabHideHook(ExtendedSettings settings) {
        this.settings = settings;
    }

    public static ProfileShopTabHideHook install(XposedInterface framework, HostScanReport report,
                                                 ClassLoader loader, ExtendedSettings settings) {
        ProfileShopTabHideHook hook = new ProfileShopTabHideHook(settings);
        try {
            Method target = HostTargets.method(report, RULE_TABS, loader);
            if (target == null) {
                DouAiLog.event("profile_tabs", "skip", RULE_TABS, "rule not resolved");
                return hook;
            }
            target.setAccessible(true);
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setId("DouAi.ProfileTabs.Shop")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        if (!hook.enabled() || !(result instanceof Collection)) {
                            return result;
                        }
                        try {
                            List<Object> kept = new ArrayList<>();
                            for (Object item : (Collection<?>) result) {
                                if (!hook.isShopItem(item)) {
                                    kept.add(item);
                                }
                            }
                            return kept.size() == ((Collection<?>) result).size() ? result : kept;
                        }
                        catch (Throwable ignored) {
                            return result;
                        }
                    });
            if (handle == null) {
                throw new IllegalStateException("Framework returned an empty hook handle");
            }
            hook.handles.add(handle);
            DouAiLog.event("profile_tabs", "bind", "DouAi.ProfileTabs.Shop", target.toString());
        }
        catch (Throwable failure) {
            DouAiLog.event("profile_tabs", "bind_failed", RULE_TABS, String.valueOf(failure));
        }
        return hook;
    }

    public void setSettings(ExtendedSettings next) {
        this.settings = next;
    }

    public boolean isEnabled() {
        return enabled();
    }

    private boolean enabled() {
        ExtendedSettings current = settings;
        return current != null && current.profileShopTabHide;
    }

    public void unbind() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        handles.clear();
    }

    private boolean isShopItem(Object item) {
        if (item == null) return false;
        for (String field : new String[] {"b", "d", "a"}) {
            Object value = read(item, field);
            if (value instanceof String) {
                String lower = ((String) value).toLowerCase();
                for (String token : SHOP_TOKENS) {
                    if (lower.contains(token)) return true;
                }
            }
        }
        return false;
    }

    private Object read(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (Throwable ignored) {
            return null;
        }
    }
}
