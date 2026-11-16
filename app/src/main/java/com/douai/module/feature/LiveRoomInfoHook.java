package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.TextUtils;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Ports the DouX live room suite: the exact audience count and a long-press information panel.
 *
 * <p>Every host touch point is an un-obfuscated live SDK symbol verified against Douyin 41.0.0:
 * the audience widget {@code LiveRoomAudienceWidget#Q7(J,String,String,Z)}, the player
 * {@code LivePlayerClient#getCurrentResolution()/isPlaying()}, the room model {@code Room} and the
 * widget base {@code Widget#dataCenter}. Only the audience count view field {@code i} is
 * obfuscated; it is matched structurally as the field typed {@code StrokeTextView}.</p>
 */
public final class LiveRoomInfoHook implements AutoCloseable {

    /** Option key that enables the long-press information panel. */
    public static final String OPTION = "live_room_info";
    /** Option key that renders the exact viewer count instead of the abbreviated one. */
    public static final String OPTION_COUNT = "live_room_exact_count";

    private static final String AUDIENCE_WIDGET =
            "com.bytedance.android.live.room.widget.LiveRoomAudienceWidget";
    private static final String AUDIENCE_UPDATE = "Q7";
    private static final String STROKE_TEXT_VIEW =
            "com.bytedance.android.livesdk.chatroom.ui.StrokeTextView";
    private static final String ROOM = "com.bytedance.android.livesdkapi.depend.model.live.Room";
    private static final String PLAYER_CLIENT =
            "com.bytedance.android.livesdk.player.LivePlayerClient";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final ClassLoader loader;
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile TextView countView;
    private volatile Object player;
    private volatile Object dataCenter;
    private volatile boolean closed;

    private LiveRoomInfoHook(ClassLoader loader, LocalOptions options, Consumer<String> log) {
        this.loader = loader;
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    /**
     * Installs the live room hooks. Returns {@code null} when the host audience widget is absent,
     * so a Douyin build without live support degrades to "feature unavailable" instead of failing
     * module start.
     */
    public static LiveRoomInfoHook install(XposedInterface framework, ClassLoader loader,
                                           LocalOptions options, Consumer<String> log) {
        if (framework == null || loader == null) return null;
        LiveRoomInfoHook hook = new LiveRoomInfoHook(loader, options, log);
        try {
            hook.attach(framework);
        } catch (Throwable failure) {
            hook.close();
            hook.log.accept("live room info hook unavailable: " + failure);
            return null;
        }
        return hook;
    }

    private void attach(XposedInterface framework) throws Exception {
        Class<?> widget = Class.forName(AUDIENCE_WIDGET, false, loader);
        Method update = widget.getDeclaredMethod(AUDIENCE_UPDATE,
                long.class, String.class, String.class, boolean.class);
        handles.add(framework.hook(update).intercept(chain -> {
            Object widgetInstance = chain.getThisObject();
            List<Object> args = chain.getArgs();
            Object result = chain.proceed();
            capture(widgetInstance, args);
            return result;
        }));

        Class<?> playerClient = Class.forName(PLAYER_CLIENT, false, loader);
        handles.add(framework.hook(playerClient.getDeclaredMethod("getCurrentResolution"))
                .intercept(chain -> {
                    player = chain.getThisObject();
                    return chain.proceed();
                }));
        handles.add(framework.hook(playerClient.getDeclaredMethod("isPlaying"))
                .intercept(chain -> {
                    player = chain.getThisObject();
                    return chain.proceed();
                }));
        log.accept("live room info hook installed");
    }

    /**
     * Captures the count view and the widget data centre after each audience update, and rewrites
     * the rendered count to the exact number when that option is on.
     */
    private void capture(Object widget, List<Object> args) {
        if (widget == null) return;
        try {
            TextView count = countViewOf(widget);
            if (count != null) {
                countView = count;
                applyExactCount(count, args);
                if (enabled()) attachLongPress(count);
            }
            Object centre = readField(widget, "dataCenter");
            if (centre != null) dataCenter = centre;
        } catch (RuntimeException failure) {
            log.accept("live room capture failed: " + failure);
        }
    }

    /**
     * Overwrites the abbreviated count label with the exact number carried by the first argument.
     * The native update always runs first, so this only replaces the rendered text.
     */
    private void applyExactCount(TextView view, List<Object> args) {
        if (!exactCountEnabled() || args == null || args.size() != 4) return;
        if (!(args.get(0) instanceof Number)) return;
        long count = ((Number) args.get(0)).longValue();
        if (count <= 0) return;
        String exact = formatCount(count);
        CharSequence shown = view.getText();
        if (shown != null && exact.contentEquals(shown)) return;
        view.setText(exact);
    }

    /** Resolves the obfuscated count field as the single {@code StrokeTextView} on the widget. */
    private TextView countViewOf(Object widget) {
        Class<?> stroke;
        try {
            stroke = Class.forName(STROKE_TEXT_VIEW, false, loader);
        } catch (ClassNotFoundException missing) {
            return null;
        }
        for (Class<?> current = widget.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!stroke.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(widget);
                    if (value instanceof TextView) return (TextView) value;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Installs the panel long press once per view, preserving any native listener. */
    private void attachLongPress(TextView view) {
        view.setOnLongClickListener(target -> {
            showPanel(target.getContext());
            return true;
        });
        view.setLongClickable(true);
    }

    /** Builds the rows shown in the panel, empty when nothing has been captured yet. */
    public Map<String, String> describe() {
        Map<String, String> rows = new LinkedHashMap<>();
        Object room = room();
        if (room != null) {
            put(rows, "房间号", invoke(room, "getRoomId"));
            put(rows, "直播间", invoke(room, "getTitle"));
            put(rows, "拉流地址", invoke(room, "buildPullUrl", "origin"));
        }
        Object current = player;
        if (current != null) {
            put(rows, "当前分辨率", invoke(current, "getCurrentResolution"));
            Object playing = invoke(current, "isPlaying");
            if (playing instanceof Boolean) {
                rows.put("播放状态", ((Boolean) playing) ? "播放中" : "已暂停");
            }
        }
        TextView view = countView;
        if (view != null) {
            CharSequence text = view.getText();
            if (text != null && text.length() > 0) rows.put("在线人数", text.toString());
        }
        return rows;
    }

    /** Reads the current room from the widget data centre. */
    private Object room() {
        Object centre = dataCenter;
        if (centre == null) return null;
        Object room = invoke(centre, "get", "data_room", null);
        return room != null ? room : invoke(centre, "get", "data_room");
    }

    /** True when the panel can be shown: enabled and a live session has been captured. */
    public boolean available() {
        return enabled() && (dataCenter != null || player != null || countView != null);
    }

    private void showPanel(Context context) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) return;
        Map<String, String> rows = describe();
        if (rows.isEmpty()) return;
        final StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> row : rows.entrySet()) {
            if (body.length() > 0) body.append('\n');
            body.append(row.getKey()).append("：").append(row.getValue());
        }
        AlertDialog dialog = new MiuixDialogBuilder(activity, true)
                .setTitle("直播信息")
                .setMessage(body.toString())
                .setPositiveButton("复制", (target, which) -> copy(activity, body.toString()))
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        DouAiLog.event("live_room_info", "panel_shown", "rows=" + rows.size(), "long_press=true");
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("直播信息", text));
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    /** Applies a refreshed configuration snapshot. */
    public void applyOptions(LocalOptions current) {
        this.options = current;
        if (!enabled() && !exactCountEnabled()) {
            countView = null;
            player = null;
            dataCenter = null;
        }
    }

    /** Renders a viewer count with a thousands separator. */
    static String formatCount(long count) {
        return String.format(Locale.ROOT, "%,d", count);
    }

    private boolean enabled() {
        return options != null && options.enabled(OPTION);
    }

    private boolean exactCountEnabled() {
        return options != null && options.enabled(OPTION_COUNT);
    }

    private void put(Map<String, String> rows, String label, Object value) {
        String text = text(value);
        if (text == null) return;
        rows.put(label, text);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text) || "0".equals(text)) return null;
        return text;
    }

    private Object invoke(Object target, String name, Object... args) {
        if (target == null) return null;
        Method method = findMethod(target.getClass(), name, args.length);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int arity) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() != arity) continue;
                return method;
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                // The field may live on a superclass: keep walking up.
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // The host may already have torn the hook down.
            }
        }
        handles.clear();
        countView = null;
        player = null;
        dataCenter = null;
    }
}
