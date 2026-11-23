package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.FeatureLogCatalog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * 密友推送过滤：在宿主内推送检查规则上拦掉密友来信的<b>通知、横幅、来电、已读</b>推送。
 *
 * <p>宿主 41.0.0 的检查器 {@code IMPushFilterCheckMsgRules}（父类 {@code BaseImPushCheckRules}）
 * 由 {@code BaseImPushCheckRules.LIZ(1TQo)} 反射遍历执行，<b>返回第一个非 null 的 CheckResult 即终止</b>：
 * {@code null} 放行，{@code CheckResult(false, reason)} 拦截。这是宿主自带的关闭通道，
 * 比 dyguard 那种「通知栏轮询后 cancel」更干净，也不依赖混淆类名以外的实现细节。
 *
 * <p>四个 check 都是 <b>无参实例方法</b>，消息体来自实例字段 {@code LJFF:Message}，
 * 因此 hook 必须用 {@code chain.getThisObject()} 取实例再读字段，而不是从参数取。
 *
 * <p>四个开关互相独立，缺省全关，行为与移植前完全一致。
 */
public final class CloseFriendPushHook implements AutoCloseable {

    private static final String RULES_CLASS =
            "com.ss.android.ugc.aweme.im.sdk.notification.immessage.pushchecker.filter"
                    + ".IMPushFilterCheckMsgRules";
    private static final String CHECK_RESULT_CLASS =
            "com.ss.android.ugc.aweme.innerpush.api.model.CheckResult";
    private static final String MESSAGE_CLASS = "com.bytedance.im.core.model.Message";
    /** 检查器实例上保存待检消息的字段，见 IMPushFilterCheckMsgRules.LIZJ 的赋值。 */
    private static final String MESSAGE_FIELD = "LJFF";

    private static volatile CloseFriendPushHook instance;

    private final BehaviorHookGroup group;
    private final Consumer<String> log;
    private final String feature;
    private final ClassLoader loader;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private Class<?> messageClass;
    private Constructor<?> checkResultCtor;
    private Field messageField;
    private Method conversationIdOf;
    private Method senderOf;
    private Method securitySenderOf;

    /**
     * 四个开关的启停全部在 hook 内按当前配置判定，安装本身不设门禁。
     *
     * <p>否则用户在设置里先关后开就得重启宿主才生效，与会话列表 hook 的体验不一致。
     */
    public static boolean wanted(LocalOptions options) {
        return options != null && (options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_NOTIFICATION)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_BANNER)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_CALL)
                || options.enabled(LocalOptions.CLOSE_FRIEND_HIDE_READ));
    }

    public static CloseFriendPushHook install(XposedInterface framework, HostScanReport report,
                                              ClassLoader loader, LocalOptions options,
                                              Consumer<String> log) {
        CloseFriendPushHook hook = new CloseFriendPushHook(framework, loader, options, log);
        hook.register();
        if (hook.group.isActive()) {
            instance = hook;
            log.accept("close friend push filter installed");
            return hook;
        }
        log.accept("close friend push filter not installed: "
                + (hook.group.installationFailure().isEmpty()
                ? "no matching check method" : hook.group.installationFailure()));
        return null;
    }

    public static CloseFriendPushHook current() {
        return instance;
    }

    private CloseFriendPushHook(XposedInterface framework, ClassLoader loader,
                                LocalOptions options, Consumer<String> log) {
        this.group = new BehaviorHookGroup("close_friend_push", log);
        this.loader = loader;
        this.options = options;
        this.log = log;
        this.feature = FeatureLogCatalog.forHook("DouAi.Advanced.close_friend_push");
        this.framework = framework;
        resolve();
    }

    private final XposedInterface framework;

    private void resolve() {
        try {
            messageClass = Class.forName(MESSAGE_CLASS, false, loader);
            conversationIdOf = noArg(messageClass, "getConversationId");
            senderOf = noArg(messageClass, "getSender");
            securitySenderOf = noArg(messageClass, "getSecuritySender");
        } catch (Throwable error) {
            messageClass = null;
            DouAiLog.failure(feature, "resolve_message", error);
        }
        try {
            Class<?> resultClass = Class.forName(CHECK_RESULT_CLASS, false, loader);
            checkResultCtor = resultClass.getConstructor(boolean.class, String.class);
        } catch (Throwable error) {
            checkResultCtor = null;
            DouAiLog.failure(feature, "resolve_check_result", error);
        }
    }

    private void register() {
        if (messageClass == null || checkResultCtor == null) {
            log.accept("close friend push filter skipped: host model classes unavailable");
            return;
        }
        Class<?> rules;
        try {
            rules = Class.forName(RULES_CLASS, false, loader);
        } catch (Throwable error) {
            group.fail(error);
            return;
        }
        messageField = findField(rules, MESSAGE_FIELD);
        if (messageField == null) {
            log.accept("close friend push filter skipped: field " + MESSAGE_FIELD + " missing");
            return;
        }
        // 四个检查互相独立，缺一个不影响其余。
        hookOne(rules, "checkExtCloseNotification", LocalOptions.CLOSE_FRIEND_HIDE_NOTIFICATION);
        hookOne(rules, "checkHideStatus", LocalOptions.CLOSE_FRIEND_HIDE_BANNER);
        hookOne(rules, "checkChatCall", LocalOptions.CLOSE_FRIEND_HIDE_CALL);
        hookOne(rules, "checkReadStatus", LocalOptions.CLOSE_FRIEND_HIDE_READ);
    }

    private void hookOne(Class<?> rules, String name, String optionKey) {
        Method target = noArg(rules, name);
        if (target == null) return;
        try {
            group.add(framework, target, chain -> {
                LocalOptions live = options;
                if (closed || live == null || !live.enabled(optionKey)) return chain.proceed();
                Object message = readMessage(chain.getThisObject());
                if (message == null || !isFriend(live, message)) return chain.proceed();
                Object blocked = blocked(name);
                if (blocked == null) return chain.proceed();
                CloseFriendState.FILTERED_PUSHES.incrementAndGet();
                DouAiLog.event(feature, "blocked", name, CloseFriendState.summary());
                return blocked;
            });
        } catch (Throwable error) {
            group.fail(error);
        }
    }

    private Object readMessage(Object rules) {
        if (rules == null || messageField == null) return null;
        try {
            Object value = messageField.get(rules);
            return messageClass != null && messageClass.isInstance(value) ? value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 会话 id 的任一分段命中名单，或发送者命中名单，即视为密友来信。 */
    private boolean isFriend(LocalOptions live, Object message) {
        try {
            String conversationId = asText(conversationIdOf, message);
            if (CloseFriendState.conversationMatches(live, conversationId)) return true;
            String uid = asText(securitySenderOf, message);
            if (uid != null && CloseFriendState.isCloseFriend(live, uid)) return true;
            Object sender = senderOf == null ? null : senderOf.invoke(message);
            return sender != null && CloseFriendState.isCloseFriend(live, String.valueOf(sender));
        } catch (Throwable error) {
            return false;
        }
    }

    private Object blocked(String reason) {
        try {
            return checkResultCtor.newInstance(false, "close_friend:" + reason);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String asText(Method getter, Object target) {
        if (getter == null) return null;
        try {
            Object value = getter.invoke(target);
            if (value instanceof String) {
                String text = ((String) value).trim();
                return text.isEmpty() ? null : text;
            }
            return null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 沿父类链找无参方法，兼容真实实现落在父类的版本。 */
    private static Method noArg(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                return method;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
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
        closed = true;
        if (instance == this) instance = null;
        group.close();
    }
}
