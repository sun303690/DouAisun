package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.FeatureLogCatalog;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 密友其余露出入口的统一过滤：消息搜索、转发选人、顶部头像、底部未读角标、通话横幅。
 *
 * <p>与 {@link CloseFriendHook}（会话列表）和 {@link CloseFriendPushHook}（推送四类）互补，
 * 覆盖 dyguard 里分散在 {@code B3}、{@code e4}、{@code n} 三个类里的过滤点。
 *
 * <p>设计原则：
 * <ul>
 *   <li>只按<b>稳定类名</b>加载，绝不硬编码混淆类名（每个宿主版本都会变）。</li>
 *   <li>列表类入口不猜方法名，按签名匹配「唯一参数是 {@code List}、返回 {@code void/boolean}」
 *       的实例方法全部挂上，命中不到就当该入口不存在。</li>
 *   <li>过滤判定复用 {@link CloseFriendHook#filter(List, Set)}，会话 id 解析只有一份实现。</li>
 *   <li>任一入口装配失败只记日志，不影响其余入口，也不阻断宿主主流程。</li>
 * </ul>
 *
 * <p>所有开关缺省全关，关掉后行为与移植前完全一致。
 */
public final class CloseFriendListHook implements AutoCloseable {

    /** 搜索结果页适配器。 */
    private static final String SEARCH_ADAPTER =
            "com.ss.android.ugc.aweme.im.search.ui.v2.SearchResultAdapterV2";
    /** 转发面板选人适配器。 */
    private static final String SHARE_ADAPTER =
            "com.ss.android.ugc.aweme.im.share.sharelist.IMShareContactsListAdapter";
    /** 消息页顶部头像（skylight）适配器。 */
    private static final String SKYLIGHT_ADAPTER =
            "com.ss.android.ugc.aweme.message2.onlinelist_refactor.DiffListAdapter";
    /** 消息页「互动消息」聚合信息，承载不可见评论摘要。 */
    private static final String AGGREGATE_INFO =
            "com.ss.android.ugc.aweme.message2.onlinelist.aggregate.AggregatedInfo";
    /** 未读计数计算器，{@code shouldCount(Conversation, String)} 决定单条会话是否计入角标。 */
    private static final String UNREAD_CALCULATOR = "com.bytedance.im.core.client.UnreadCountCalculator";

    /** 通话横幅入口候选，按版本取第一个能加载到的。 */
    private static final String[] CALL_BANNER_TARGETS = {
            "com.ss.android.ugc.aweme.im.sdk.chat.voip.CallBannerManager",
            "com.ss.android.ugc.aweme.im.voip.banner.CallBannerManager",
            "com.ss.android.ugc.aweme.im.voip.CallBannerManager",
            "com.ss.android.ugc.aweme.im.banner.CallBannerManager",
    };

    private static volatile CloseFriendListHook instance;

    /** 重放期间置位，避免改参后的调用被本 hook 再过滤一次。 */
    private static final ThreadLocal<Boolean> REPLAY = new ThreadLocal<>();

    private final BehaviorHookGroup group;
    private final XposedInterface framework;
    private final ClassLoader loader;
    private final Consumer<String> log;
    private final String feature;

    private volatile LocalOptions options;
    private volatile boolean closed;

    private CloseFriendListHook(XposedInterface framework, ClassLoader loader, LocalOptions options,
                                Consumer<String> log) {
        this.framework = framework;
        this.loader = loader;
        this.options = options;
        this.log = log;
        this.feature = FeatureLogCatalog.forHook("DouAi.Advanced.close_friend_list");
        this.group = new BehaviorHookGroup("close_friend_list", log);
    }

    /**
     * 是否有任一入口需要装配。
     *
     * <p>hook 本身按当前配置实时判定开关，这里只看用户是否开过任意一项，
     * 全关时不注册任何钩子，避免给宿主多余开销。
     */
    public static boolean wanted(LocalOptions options) {
        return options != null && (options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_SEARCH)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_FORWARD)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_SKYLIGHT)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_BADGE)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_CALL_BANNER)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_INTERACTION));
    }

    public static CloseFriendListHook install(XposedInterface framework, ClassLoader loader,
                                              LocalOptions options, Consumer<String> log) {
        if (framework == null || loader == null) return null;
        CloseFriendListHook candidate = new CloseFriendListHook(framework, loader, options, log);
        candidate.register();
        if (candidate.group.isActive()) {
            instance = candidate;
            try { log.accept("close friend list filter installed"); } catch (Throwable ignored) { }
            return candidate;
        }
        try {
            log.accept("close friend list filter not installed: "
                    + (candidate.group.installationFailure().isEmpty() ? "no matching entry"
                    : candidate.group.installationFailure()));
        } catch (Throwable ignored) { }
        candidate.close();
        return null;
    }

    public static CloseFriendListHook current() {
        return instance;
    }

    /**
     * 装配全部入口。
     *
     * <p>不按 {@link #wanted} 提前返回：用户可能先关后开，届时不该重启宿主才生效。
     * 装配成本只是几十次 {@code loadClass} + 方法签名匹配，一次性开销可忽略。
     */
    private void register() {
        hookListEntry(SEARCH_ADAPTER, LocalOptions.CLOSE_FRIEND_HIDE_SEARCH, "消息搜索");
        hookListEntry(SHARE_ADAPTER, LocalOptions.CLOSE_FRIEND_HIDE_FORWARD, "转发选人");
        hookListEntry(SKYLIGHT_ADAPTER, LocalOptions.CLOSE_FRIEND_HIDE_SKYLIGHT, "顶部头像");
        hookListEntry(AGGREGATE_INFO, LocalOptions.CLOSE_FRIEND_HIDE_INTERACTION, "互动摘要");
        hookUnreadBadge();
        hookCallBanner();
    }

    // ---------------------------------------------------------------- 列表类入口

    /** 挂一个「列表数据交给适配器前先滤一遍」的钩子。 */
    private int hookListEntry(String className, String optionKey, String label) {
        Class<?> adapter;
        try {
            adapter = loader.loadClass(className);
        } catch (Throwable error) {
            note("密友" + label + "：目标类缺失，跳过 " + className);
            return 0;
        }
        int hooked = 0;
        for (Method method : adapter.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !List.class.isAssignableFrom(params[0])) continue;
            Class<?> returned = method.getReturnType();
            if (returned != void.class && returned != boolean.class) continue;
            if (!hookListMethod(method, optionKey, label)) continue;
            hooked++;
        }
        if (hooked == 0) note("密友" + label + "：未找到列表提交方法，跳过");
        else note("密友" + label + "：已装配 " + hooked + " 个提交点");
        return hooked;
    }

    private boolean hookListMethod(Method method, String optionKey, String label) {
        try {
            method.setAccessible(true);
        } catch (Throwable error) {
            return false;
        }
        try {
            group.add(framework, method, chain -> {
                LocalOptions live = options;
                if (closed || live == null || !live.enabled(optionKey)) return chain.proceed();
                if (Boolean.TRUE.equals(REPLAY.get())) return chain.proceed();
                Object raw = chain.getArg(0);
                if (!(raw instanceof List)) return chain.proceed();
                Set<String> hidden = hiddenUids(live);
                if (hidden.isEmpty()) return chain.proceed();
                List<?> filtered = CloseFriendHook.filter((List<?>) raw, hidden);
                if (filtered == null) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = filtered;
                REPLAY.set(Boolean.TRUE);
                try {
                    return chain.proceed(args);
                } finally {
                    REPLAY.remove();
                }
            });
            return true;
        } catch (Throwable error) {
            note("密友" + label + "：装配失败 " + error.getClass().getSimpleName());
            return false;
        }
    }

    // ---------------------------------------------------------------- 未读角标

    /**
     * 底部未读角标：宿主逐会话判定 {@code shouldCount(Conversation, String)}，
     * 这里把密友会话改判为不计数，总角标自然少掉这部分。
     */
    private void hookUnreadBadge() {
        Class<?> calculator;
        try {
            calculator = loader.loadClass(UNREAD_CALCULATOR);
        } catch (Throwable error) {
            note("密友底部未读：目标类缺失，跳过");
            return;
        }
        Method target = null;
        for (Method method : calculator.getDeclaredMethods()) {
            if (!"shouldCount".equals(method.getName())) continue;
            if (method.getParameterCount() != 2) continue;
            if (method.getReturnType() != boolean.class) continue;
            target = method;
            break;
        }
        if (target == null) {
            note("密友底部未读：shouldCount 签名缺失，跳过");
            return;
        }
        try {
            target.setAccessible(true);
        } catch (Throwable error) {
            note("密友底部未读：方法不可访问，跳过");
            return;
        }
        try {
            group.add(framework, target, chain -> {
                Object result = chain.proceed();
                LocalOptions live = options;
                if (closed || live == null) return result;
                if (!live.enabled(LocalOptions.CLOSE_FRIEND_HIDE_BADGE)) return result;
                if (!Boolean.TRUE.equals(result)) return result;
                Set<String> hidden = hiddenUids(live);
                if (hidden.isEmpty()) return result;
                Object conversation = chain.getArg(0);
                if (conversation == null) return result;
                if (CloseFriendHook.filter(Collections.singletonList(conversation), hidden) == null) {
                    return result;
                }
                DouAiLog.event(feature, "unread_excluded", "close_friend", CloseFriendState.summary());
                return Boolean.FALSE;
            });
            note("密友底部未读：已装配");
        } catch (Throwable error) {
            note("密友底部未读：装配失败 " + error.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 通话横幅

    /**
     * 通话横幅：候选类里所有 show/display 前缀方法在开关打开时直接吞掉。
     *
     * <p>横幅的宿主类在各版本间搬过几次家，这里逐个候选尝试，全部缺失只记一条日志。
     * 来电弹窗本身由 {@link CloseFriendPushHook} 的 {@code checkChatCall} 负责，两者不重叠。
     */
    private void hookCallBanner() {
        int hooked = 0;
        for (String className : CALL_BANNER_TARGETS) {
            Class<?> type;
            try {
                type = loader.loadClass(className);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
                String name = method.getName();
                if (!name.startsWith("show") && !name.startsWith("display")) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    group.add(framework, method, chain -> {
                        LocalOptions live = options;
                        if (closed || live == null
                                || !live.enabled(LocalOptions.CLOSE_FRIEND_HIDE_CALL_BANNER)) {
                            return chain.proceed();
                        }
                        DouAiLog.event(feature, "call_banner_skipped", name,
                                CloseFriendState.summary());
                        return null;
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // 同名重载失败不影响其它入口
                }
            }
        }
        if (hooked > 0) note("密友来电横幅：已装配 " + hooked + " 个入口");
        else note("密友来电横幅：候选类全部缺失，跳过");
    }

    // ---------------------------------------------------------------- 公共

    private static Set<String> hiddenUids(LocalOptions options) {
        return CloseFriendHook.parseUids(options.text(LocalOptions.CLOSE_FRIEND_UIDS, ""));
    }

    private void note(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }

    public boolean isActive() {
        return !closed && group.isActive();
    }

    public String installationFailure() {
        return group.installationFailure();
    }

    public void setOptions(LocalOptions options) {
        this.options = options;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (instance == this) instance = null;
        group.close();
    }
}
