package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 密友过滤：把指定 uid 的单聊会话从会话列表里剔除。
 *
 * <p>宿主入口 {@code BaseSessionListAdapter.LL(List, boolean)} 是 public final，
 * 所有子类共用同一份实现，也是唯一写入渲染列表的地方，因此只 hook 这一处。
 *
 * <p>会话条目 id 即 conversationId，单聊格式为 {@code 0:1:<uidA>:<uidB>}，
 * 只要任一分段命中名单就隐藏，无需再区分自己是哪一侧。
 */
public final class CloseFriendHook implements AutoCloseable {

    private final BehaviorHookGroup registration;
    private volatile LocalOptions options;
    private final Consumer<String> log;
    private volatile boolean closed;

    private CloseFriendHook(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log;
        this.registration = new BehaviorHookGroup("close_friend", log);
    }

    public static CloseFriendHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, LocalOptions options,
                                          Consumer<String> log) throws Exception {
        Method target = null;
        for (String id : new String[]{"adv_session_list_render", "adv_session_list_submit"}) {
            try {
                target = HostTargets.method(report, id, loader);
                break;
            } catch (Exception ignored) {
                // 规则可能只在部分版本可用，继续尝试下一个
            }
        }
        if (target == null) throw new NoSuchMethodException("adv_session_list_render");
        CloseFriendHook hook = new CloseFriendHook(options, log);
        try {
            hook.registration.add(framework, target, chain -> {
                if (hook.closed) return chain.proceed();
                LocalOptions current = hook.options;
                if (current == null || !current.enabled(LocalOptions.CLOSE_FRIEND_FILTER)) {
                    return chain.proceed();
                }
                Set<String> hidden = hiddenUids(current);
                if (hidden.isEmpty()) return chain.proceed();
                Object raw = chain.getArg(0);
                if (!(raw instanceof List)) return chain.proceed();
                List<?> filtered = filter((List<?>) raw, hidden);
                if (filtered == null) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = filtered;
                return chain.proceed(args);
            });
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
        log.accept("Close friend filter installed on " + target.getDeclaringClass().getName());
        return hook;
    }

    private static Set<String> hiddenUids(LocalOptions options) {
        return parseUids(options.text(LocalOptions.CLOSE_FRIEND_UIDS, ""));
    }

    /** 逗号、中文逗号、换行皆可分隔。 */
    static Set<String> parseUids(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split("[,，\\n\\r\\t]")) {
            String value = part.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values.isEmpty() ? Collections.<String>emptySet() : values;
    }

    /** 命中返回过滤后的新列表；未命中返回 null 表示保持原样。 */
    static List<?> filter(List<?> source, Set<String> hidden) {
        if (source == null || source.isEmpty() || hidden.isEmpty()) return null;
        List<Object> kept = new ArrayList<>(source.size());
        boolean removed = false;
        for (Object item : source) {
            if (item != null && matches(item, hidden)) {
                removed = true;
                continue;
            }
            kept.add(item);
        }
        return removed ? kept : null;
    }

    private static boolean matches(Object item, Set<String> hidden) {
        String conversationId = conversationId(item);
        if (conversationId == null) return false;
        int first = conversationId.indexOf(':');
        if (first < 0) return false;
        int second = conversationId.indexOf(':', first + 1);
        if (second < 0) return false;
        int third = conversationId.indexOf(':', second + 1);
        if (third < 0) return false;
        for (String uid : conversationId.substring(second + 1).split(":")) {
            String value = uid.trim();
            if (!value.isEmpty() && hidden.contains(value)) return true;
        }
        return false;
    }

    /** 条目 id 即会话 id；兼容 getConversationId()/getId() 两种取值。 */
    private static String conversationId(Object item) {
        Object value = HostTargets.invoke(item, "getConversationId");
        String text = asText(value);
        if (text != null) return text;
        value = HostTargets.read(item, "id");
        text = asText(value);
        if (text != null) return text;
        value = HostTargets.invoke(item, "getId");
        return asText(value);
    }

    private static String asText(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    public boolean isActive() {
        return !closed && registration.isActive();
    }

    public void setOptions(LocalOptions options) {
        this.options = options;
    }

    void log(String message) {
        if (message != null) log.accept(message);
    }

    @Override
    public void close() {
        closed = true;
        registration.close();
    }
}
