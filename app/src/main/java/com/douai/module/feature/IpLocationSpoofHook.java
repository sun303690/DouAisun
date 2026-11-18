package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Rewrites the IP attribution read from the shared {@code User} model.
 *
 * <p>Host probe (41.0.0, {@code classes37.dex}): {@code User} declares the attribution as plain
 * {@code @JvmField}-style members and only carries Kotlin's synthetic
 * {@code getIpLocation$annotations} / {@code getProvince$annotations} / {@code getCity$annotations}
 * methods. Those synthetic methods return void, are never called by the UI, and therefore cannot be
 * intercepted to change what is displayed. The module intercepts a real
 * {@code getXxx()} only when the host declares one; when it does not, the hook reports the reason
 * instead of pretending to work, and the attribution spoof stays limited to the feed information
 * bar, which reads the configured value directly.</p>
 */
public final class IpLocationSpoofHook {

    /** Host model that owns the attribution. */
    private static final String USER_CLASS = "com.ss.android.ugc.aweme.profile.model.User";

    /** The attribution fields and the getter names the host may expose for them. */
    private static final String[] TARGET_FIELDS = {"ipLocation", "province", "city"};

    private volatile LocalOptions options;
    private final Consumer<String> log;
    private final List<BehaviorHookGroup> groups = new ArrayList<>();
    private volatile boolean closed;

    private IpLocationSpoofHook(LocalOptions options, Consumer<String> log) {
        this.options = options == null ? new LocalOptions(java.util.Collections.emptyMap()) : options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static IpLocationSpoofHook install(XposedInterface framework, HostScanReport report,
                                              ClassLoader loader, LocalOptions options,
                                              Consumer<String> log) {
        IpLocationSpoofHook hook = new IpLocationSpoofHook(options, log);
        hook.installModelGetters(framework, loader);
        return hook;
    }

    /** Applies a freshly published configuration without reinstalling the hooks. */
    public void setOptions(LocalOptions options) {
        if (options != null) this.options = options;
    }

    private void installModelGetters(XposedInterface framework, ClassLoader loader) {
        BehaviorHookGroup group = new BehaviorHookGroup("ip_location_spoof", log);
        int hooked = 0;
        try {
            Class<?> user = Class.forName(USER_CLASS, false, loader);
            for (String field : TARGET_FIELDS) {
                Method getter = findGetter(user, field);
                if (getter == null) continue;
                final String target = field;
                group.add(framework, getter, chain -> {
                    Object result = chain.proceed();
                    if (closed) return result;
                    String spoofed = spoofedValue(target);
                    return spoofed == null ? result : spoofed;
                });
                hooked++;
            }
            if (hooked == 0) {
                group.close();
                log.accept("IP attribution spoof inactive: " + USER_CLASS
                        + " exposes no callable attribution getter (field-only model)");
                return;
            }
            groups.add(group);
            log.accept("IP attribution spoof installed on " + hooked + " getters");
        } catch (Throwable failure) {
            group.close();
            try {
                log.accept("IP attribution spoof unavailable: " + failure.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                // The host may be unloading, logging is best effort.
            }
        }
    }

    /** Resolves the configured replacement for one field, or null when nothing is configured. */
    private String spoofedValue(String field) {
        LocalOptions config = options;
        String value = config.ipLocation();
        if (value.isEmpty()) return null;
        if ("ipLocation".equals(field)) return value;
        if (!config.ipLocationSpoofCity()) return null;
        if ("city".equals(field)) {
            String city = config.ipLocationCity();
            return city.isEmpty() ? null : city;
        }
        String province = config.ipLocationProvince();
        return province.isEmpty() ? null : province;
    }

    /** Finds a callable String getter for a field; synthetic {@code $annotations} methods are skipped. */
    private static Method findGetter(Class<?> owner, String field) {
        String capitalised = field.isEmpty() ? field
                : Character.toUpperCase(field.charAt(0)) + field.substring(1);
        String expected = "get" + capitalised;
        for (Class<?> current = owner; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(expected)) continue;
                if (method.getParameterCount() != 0) continue;
                if (method.getReturnType() != String.class) continue;
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    public void close() {
        closed = true;
        for (BehaviorHookGroup group : groups) group.close();
        groups.clear();
    }
}
