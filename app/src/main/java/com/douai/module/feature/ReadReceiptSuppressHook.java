package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostTargets;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 已读回执拦截（Dyvv {@code chat.read.transport.v2}）。
 *
 * <p>宿主把所有 IM 请求统一交给
 * {@code LX/1T2Q;->i3(ILcom/bytedance/im/core/proto/RequestBody;[Ljava/lang/Object;)J} 发送。
 * {@code RequestBody} 用不同 body 字段区分业务，
 * {@code mark_conversation_read_body} / {@code batch_mark_read_body} 等即为「上报已读」。
 * 拦截这些请求，对方看不到「已读」，本地读取与未读计数逻辑不受影响。
 */
public final class ReadReceiptSuppressHook implements AutoCloseable {
    static final String RULE = "adv_read_receipt_suppress";

    private static final String[] READ_BODY_FIELDS = {
            "mark_conversation_read_body",
            "batch_mark_read_body",
            "mark_stranger_conversation_read_body",
            "mark_stranger_all_conversation_read_body",
            "mark_csb_conv_read_body",
    };

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicLong suppressed = new AtomicLong(0L);
    private final AtomicLong lastTrace = new AtomicLong(0L);
    private final Consumer<String> log;
    private volatile boolean enabled;

    private ReadReceiptSuppressHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.readReceiptSuppress;
        this.log = log;
    }

    public static ReadReceiptSuppressHook install(XposedInterface framework, HostScanReport report,
                                                  ClassLoader loader, ExtendedSettings settings,
                                                  Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        Method transport = HostTargets.method(report, RULE, loader);
        if (transport.getReturnType() != long.class) {
            throw new IllegalStateException("Read receipt transport signature changed: "
                    + HostTargets.descriptor(transport));
        }
        transport.setAccessible(true);

        ReadReceiptSuppressHook hook = new ReadReceiptSuppressHook(settings, sink);
        try {
            XposedInterface.HookHandle handle = framework.hook(transport)
                    .setId("DouAi.Im.ReadReceiptSuppress")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("read_receipt_suppress", "installed", HostTargets.descriptor(transport),
                    "method=" + transport.getName());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.readReceiptSuppress;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getSuppressedCount() {
        return suppressed.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object[] args = chain.getArgs();
        Object requestBody = args != null && args.length > 1 ? args[1] : null;
        if (!enabled || requestBody == null || !isReadReceiptRequest(requestBody)) {
            return chain.proceed();
        }
        long total = suppressed.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastTrace.get();
        if (now - last > 3000L && lastTrace.compareAndSet(last, now)) {
            DouAiLog.event("read_receipt_suppress", "suppressed", "read_body", "total=" + total);
            if (log != null) log.accept("已读回执已拦截 " + total + " 次");
        }
        return 0L;
    }

    private static boolean isReadReceiptRequest(Object requestBody) {
        for (String name : READ_BODY_FIELDS) {
            try {
                Field field = requestBody.getClass().getField(name);
                if (field.get(requestBody) != null) return true;
            } catch (NoSuchFieldException ignored) {
                // 宿主版本不同字段可能缺失，继续尝试下一个
            } catch (Throwable failure) {
                DouAiLog.failure("read_receipt_suppress", "field:" + name, failure);
                return false;
            }
        }
        return false;
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                cause.addSuppressed(ignored);
            }
        }
        handles.clear();
    }

    @Override
    public void close() {
        unhookAll(new IllegalStateException("ReadReceiptSuppressHook closed"));
    }
}
