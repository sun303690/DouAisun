package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 密友过滤：把指定 uid 的单聊会话从会话列表里剔除，并支持随时恢复显示。
 *
 * <p>宿主入口 {@code BaseSessionListAdapter.LL(List, boolean)} 是 public final，
 * 所有子类共用同一份实现，也是唯一写入渲染列表的地方，因此只 hook 这一处。
 *
 * <p>两个方向：
 *
 * <ul>
 *   <li>进入时按 {@link CloseFriendState#isHidingActive(LocalOptions)} 决定是否剔除；
 *       自动隐藏开关打开即生效，不必等生命周期事件。</li>
 *   <li>{@link #refreshSessionList()} 记住每个 adapter 上一次拿到的<b>完整</b>列表，
 *       状态翻转后原样回灌一次（显示时回灌全量、隐藏时回灌剔除后的列表），
 *       因此用户点「显示密友会话」后列表立刻重绘，不需要杀进程。</li>
 * </ul>
 *
 * <p>会话条目 id 即 conversationId，单聊格式为 {@code 0:1:<uidA>:<uidB>}，
 * 只要任一分段命中名单就隐藏，无需再区分自己是哪一侧。
 */
public final class CloseFriendHook implements AutoCloseable {

    private static final int MAX_TRACKED = 8;

    private static volatile CloseFriendHook instance;

    /** Re-entrancy guard: the refresh replay itself must not be filtered again. */
    private static final ThreadLocal<Boolean> REPLAY = new ThreadLocal<>();

    private final BehaviorHookGroup registration;
    private volatile LocalOptions options;
    private volatile Method target;
    private final Consumer<String> log;
    private volatile boolean closed;

    /** One adapter plus the untouched list and args it was last submitted with. */
    private final List<Tracked> tracked = new ArrayList<>();

    private static final class Tracked {
        final WeakReference<Object> adapter;
        final Object[] args;

        Tracked(Object adapter, Object[] args) {
            this.adapter = new WeakReference<>(adapter);
            this.args = args;
        }
    }

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
        hook.target = target;
        try {
            hook.registration.add(framework, target, chain -> {
                if (hook.closed) return chain.proceed();
                if (Boolean.TRUE.equals(REPLAY.get())) return chain.proceed();
                LocalOptions current = hook.options;
                if (current == null || !current.enabled(LocalOptions.CLOSE_FRIEND_FILTER)) {
                    return chain.proceed();
                }
                Object raw = chain.getArg(0);
                if (!(raw instanceof List)) return chain.proceed();
                hook.remember(chain.getThisObject(), chain.getArgs().toArray(), (List<?>) raw);
                Set<String> hidden = hiddenUids(current);
                if (hidden.isEmpty()) return chain.proceed();
                if (!CloseFriendState.isHidingActive(current)) return chain.proceed();
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
        instance = hook;
        log.accept("Close friend filter installed on " + target.getDeclaringClass().getName());
        return hook;
    }

    public static CloseFriendHook current() {
        return instance;
    }

    /**
     * 状态翻转后让会话列表重新按当前状态渲染一次。
     *
     * <p>宿主 adapter 会缓存上一次传入的列表，光改标记不会重绘，所以这里拿原始参数回灌一次。
     * 回灌走宿主 public 方法本身的反射调用，由 {@link #REPLAY} 守卫避免被本 hook 再过滤一遍。
     */
    public static void refreshSessionList() {
        CloseFriendHook hook = instance;
        if (hook != null) hook.refreshAll();
    }

    private void refreshAll() {
        Method method = target;
        if (method == null || closed) return;
        LocalOptions current = options;
        boolean hide = current != null && current.enabled(LocalOptions.CLOSE_FRIEND_FILTER)
                && CloseFriendState.isHidingActive(current);
        Set<String> hidden = current == null ? Collections.<String>emptySet() : hiddenUids(current);

        List<Object[]> replays = new ArrayList<>();
        synchronized (tracked) {
            for (int i = tracked.size() - 1; i >= 0; i--) {
                Tracked item = tracked.get(i);
                Object adapter = item.adapter.get();
                if (adapter == null) {
                    tracked.remove(i);
                    continue;
                }
                Object[] args = item.args.clone();
                Object full = args.length > 0 ? args[0] : null;
                if (!(full instanceof List)) continue;
                List<?> wanted = full instanceof List ? (List<?>) full : Collections.emptyList();
                if (hide && !hidden.isEmpty()) {
                    List<?> trimmed = filter(wanted, hidden);
                    if (trimmed != null) wanted = trimmed;
                }
                args[0] = wanted;
                replays.add(new Object[]{adapter, args});
            }
        }
        if (replays.isEmpty()) return;

        Runnable body = () -> {
            for (Object[] replay : replays) {
                REPLAY.set(Boolean.TRUE);
                try {
                    method.invoke(replay[0], (Object[]) replay[1]);
                } catch (Throwable failure) {
                    log.accept("close friend list replay failed: " + failure);
                } finally {
                    REPLAY.remove();
                }
            }
        };
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(body);
        } catch (Throwable ignored) {
            body.run();
        }
        log.accept("close friend list refreshed hide=" + hide + " adapters=" + replays.size());
    }

    private void remember(Object adapter, Object[] args, List<?> full) {
        if (adapter == null || args.length == 0) return;
        synchronized (tracked) {
            for (Tracked item : tracked) {
                Object existing = item.adapter.get();
                if (existing == adapter) {
                    item.args[0] = full;
                    return;
                }
            }
            Object[] copy = args.clone();
            copy[0] = full;
            tracked.add(new Tracked(adapter, copy));
            while (tracked.size() > MAX_TRACKED) tracked.remove(0);
        }
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
        if (!removed) return null;
        CloseFriendState.FILTERED_ROWS.addAndGet(source.size() - kept.size());
        return kept;
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
        refreshAll();
    }

    void log(String message) {
        if (message != null) log.accept(message);
    }

    @Override
    public void close() {
        closed = true;
        synchronized (tracked) {
            tracked.clear();
        }
        if (instance == this) instance = null;
        registration.close();
    }
}
