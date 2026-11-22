package com.douai.module.feature;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedInterface;

/**
 * Touch companion for {@link ImmersivePlaybackUi}.
 *
 * <p>Hiding a view is not enough on the feed page: a hidden node still sits inside the gesture
 * layout, so a tap that lands on it is swallowed before it ever reaches the video layer. Two hooks
 * close that gap:
 *
 * <ul>
 *   <li>{@code LongPressLayout#dispatchTouchEvent} — the long press menu is suppressed for the
 *       duration of the gesture by clearing the host's own enable flags on {@code ACTION_DOWN} and
 *       writing the original values back on {@code ACTION_UP} / {@code ACTION_CANCEL}. Playback rate
 *       gestures keep working because the chain still runs.</li>
 *   <li>{@code ViewGroup#dispatchTransformedTouchEvent} — a child that this module has hidden must
 *       not eat {@code ACTION_DOWN}. Only the DOWN is dropped; the rest of the stream is left alone,
 *       because a target that received DOWN but not MOVE/UP leaves the native player stuck in a
 *       pressed state.</li>
 * </ul>
 */
public final class ImmersiveTouchGuard implements AutoCloseable {

    /** Resolves whether a view is currently hidden by the immersive overlay. */
    public interface HiddenLookup {
        boolean isHidden(View view);
    }

    private static final String LONG_PRESS_ENABLE = "longPressEnable";
    private static final String ENABLE_LONG_CLICK = "enableLongClick";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final List<Field> longPressFlags = new ArrayList<>();
    private final Map<View, boolean[]> savedFlags = Collections.synchronizedMap(new WeakHashMap<>());
    private final HiddenLookup hiddenLookup;
    private final Consumer<String> log;
    private volatile boolean closed;

    private ImmersiveTouchGuard(HiddenLookup hiddenLookup, Consumer<String> log) {
        this.hiddenLookup = hiddenLookup;
        this.log = log == null ? ignored -> { } : log;
    }

    public static ImmersiveTouchGuard install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, HiddenLookup hiddenLookup,
                                             Consumer<String> log) throws Exception {
        ImmersiveTouchGuard guard = new ImmersiveTouchGuard(hiddenLookup, log);
        try {
            guard.installLongPressHook(framework, report, loader);
            guard.installDispatchHook(framework);
            return guard;
        } catch (Throwable failure) {
            guard.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void installLongPressHook(XposedInterface framework, HostScanReport report,
                                      ClassLoader loader) throws Exception {
        Class<?> layout;
        try {
            layout = HostTargets.type(report, "adv_gesture_touch_layout", loader);
        } catch (Throwable failure) {
            log.accept("LongPressLayout unavailable, long press menu not suppressed: "
                    + describe(failure));
            return;
        }
        for (String name : new String[] {ENABLE_LONG_CLICK, LONG_PRESS_ENABLE}) {
            try {
                Field field = layout.getDeclaredField(name);
                if (field.getType() == boolean.class) {
                    field.setAccessible(true);
                    longPressFlags.add(field);
                }
            } catch (Throwable failure) {
                log.accept("LongPressLayout." + name + " missing: " + describe(failure));
            }
        }
        if (longPressFlags.isEmpty()) {
            log.accept("LongPressLayout exposes no enable flag, gesture guard is DOWN-only");
        }
        Method dispatch;
        try {
            dispatch = layout.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        } catch (Throwable failure) {
            log.accept("LongPressLayout#dispatchTouchEvent unavailable: " + describe(failure));
            return;
        }
        dispatch.setAccessible(true);
        Method finalDispatch = dispatch;
        XposedInterface.HookHandle handle = framework.hook(dispatch)
                .setId("DouAi.Immersive.LongPress")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> interceptLongPress(chain, finalDispatch));
        if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
        handles.add(handle);
        log.accept("LongPressLayout gesture guard installed; flags=" + longPressFlags.size());
    }

    private void installDispatchHook(XposedInterface framework) throws Exception {
        Method dispatch = ViewGroup.class.getDeclaredMethod("dispatchTransformedTouchEvent",
                MotionEvent.class, boolean.class, View.class);
        dispatch.setAccessible(true);
        XposedInterface.HookHandle handle = framework.hook(dispatch)
                .setId("DouAi.Immersive.TouchThrough")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(this::interceptDispatch);
        if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
        handles.add(handle);
        log.accept("Hidden child touch guard installed");
    }

    private Object interceptLongPress(XposedInterface.Chain chain, Method dispatch) throws Throwable {
        if (closed) return chain.proceed();
        Object self = chain.getThisObject();
        Object arg = chain.getArg(0);
        if (!(self instanceof View) || !(arg instanceof MotionEvent)) return chain.proceed();
        View host = (View) self;
        MotionEvent event = (MotionEvent) arg;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            suppressLongPress(host);
        }
        try {
            return chain.proceed();
        } finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                restoreLongPress(host);
            }
        }
    }

    private Object interceptDispatch(XposedInterface.Chain chain) throws Throwable {
        if (closed) return chain.proceed();
        Object child = chain.getArg(2);
        Object arg = chain.getArg(0);
        if (!(child instanceof View) || !(arg instanceof MotionEvent)) return chain.proceed();
        MotionEvent event = (MotionEvent) arg;
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return chain.proceed();
        if (!hiddenLookup.isHidden((View) child)) return chain.proceed();
        // Claim the DOWN so the hidden node never enters its pressed state; the rest of the
        // gesture is delivered normally to whatever the system targets next.
        return true;
    }

    private void suppressLongPress(View host) {
        if (longPressFlags.isEmpty()) return;
        synchronized (savedFlags) {
            if (savedFlags.containsKey(host)) return;
        }
        // Remember each flag's own value: a page that ships one of them off must not be switched on.
        boolean[] originals = new boolean[longPressFlags.size()];
        for (int index = 0; index < longPressFlags.size(); index++) {
            Field field = longPressFlags.get(index);
            originals[index] = true;
            try {
                originals[index] = field.getBoolean(host);
                field.setBoolean(host, false);
            } catch (Throwable failure) {
                DouAiLog.failure("immersive_playback", "suppress_" + field.getName(), failure);
            }
        }
        synchronized (savedFlags) {
            savedFlags.put(host, originals);
        }
    }

    private void restoreLongPress(View host) {
        if (longPressFlags.isEmpty()) return;
        boolean[] originals;
        synchronized (savedFlags) {
            originals = savedFlags.remove(host);
        }
        if (originals == null) return;
        for (int index = 0; index < longPressFlags.size() && index < originals.length; index++) {
            Field field = longPressFlags.get(index);
            try {
                field.setBoolean(host, originals[index]);
            } catch (Throwable failure) {
                DouAiLog.failure("immersive_playback", "restore_" + field.getName(), failure);
            }
        }
    }

    /** Drops the tracked gesture state without touching the hooks. */
    public void resetGesture() {
        synchronized (savedFlags) {
            savedFlags.clear();
        }
    }

    public boolean isActive() {
        return !closed && !handles.isEmpty();
    }

    @Override
    public void close() {
        closed = true;
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable failure) {
                DouAiLog.failure("immersive_playback", "unhook", failure);
            }
        }
        handles.clear();
        resetGesture();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
