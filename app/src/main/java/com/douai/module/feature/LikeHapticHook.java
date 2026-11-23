package com.douai.module.feature;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Dyvv {@code playback.like_haptic.double_click.v1} 的移植：双击点赞时震一下。
 *
 * <p>宿主在 {@code VideoViewHolder} 与 {@code FeedImageViewHolder} 的
 * {@code handleDoubleClick(Aweme)} 里处理双击手势。进入该方法前先读一次
 * {@code Aweme.isLike()}：原本未点赞说明这次双击是点赞，放行后补一次短震动；
 * 原本已点赞说明这次双击是取消赞，不震。两条锚点同时覆盖视频与图文卡片。</p>
 */
public final class LikeHapticHook {

    static final String RULE_VIDEO = "adv_like_haptic_video";
    static final String RULE_IMAGE = "adv_like_haptic_image";

    private static final int DURATION_MS = 40;

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final WeakReference<Context> contextRef;
    private final AtomicLong vibrated = new AtomicLong();
    private volatile boolean enabled;

    private LikeHapticHook(Context context, ExtendedSettings settings, Consumer<String> log) {
        this.contextRef = new WeakReference<>(context == null ? null : context.getApplicationContext());
        this.enabled = settings != null && settings.likeHaptic;
        Consumer<String> sink = log == null ? ignored -> { } : log;
        this.log = sink;
    }

    public static LikeHapticHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Context context,
                                         ExtendedSettings settings, Consumer<String> log) throws Exception {
        LikeHapticHook hook = new LikeHapticHook(context, settings, log);
        int bound = 0;
        for (String rule : new String[] {RULE_VIDEO, RULE_IMAGE}) {
            try {
                hook.bind(framework, HostTargets.method(report, rule, loader), "douai." + rule);
                bound++;
            } catch (Throwable failure) {
                DouAiLog.failure("like_haptic", "installation", failure);
                hook.log.accept("Like haptic skipped " + rule + ": " + failure);
            }
        }
        if (bound == 0) {
            hook.log.accept("Like haptic hook skipped: no anchor resolved");
            return hook;
        }
        DouAiLog.event("like_haptic", "installed", "rules=" + bound, "");
        return hook;
    }

    private void bind(XposedInterface framework, Method target, String id) throws Exception {
        target.setAccessible(true);
        XposedInterface.HookHandle handle = framework.hook(target)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> intercept(chain, id));
        if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
        handles.add(handle);
        DouAiLog.event("like_haptic", "bind", id, target.toString());
    }

    public void setSettings(ExtendedSettings next) {
        this.enabled = next != null && next.likeHaptic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getVibratedCount() {
        return vibrated.get();
    }

    private Object intercept(XposedInterface.Chain chain, String id) throws Throwable {
        if (!enabled) return chain.proceed();
        boolean likedBefore = liked(chain.getArg(0));
        Object result = chain.proceed();
        if (likedBefore) return result;
        buzz(id);
        return result;
    }

    private static boolean liked(Object aweme) {
        if (aweme == null) return true;
        try {
            Object value = HostTargets.invoke(aweme, "isLike");
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void buzz(String id) {
        Context context = contextRef.get();
        if (context == null) return;
        try {
            Vibrator vibrator = vibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(DURATION_MS);
            }
            long total = vibrated.incrementAndGet();
            if (total == 1L) {
                DouAiLog.event("like_haptic", "vibrate", "double_click_like", "hook_id=" + id);
                log.accept("like haptic fired");
            }
        } catch (Throwable failure) {
            DouAiLog.failure("like_haptic", "vibrate", failure);
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) return manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // best effort during teardown
            }
        }
        handles.clear();
        if (cause != null) DouAiLog.failure("like_haptic", "install", cause);
    }
}
