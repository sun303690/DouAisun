package com.douai.module.feature;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.io.File;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Rewrites Douyin playback sources to a loopback proxy so the original (highest bitrate) variant
 * is used and can be cached locally.
 *
 * <p>The hook only rewrites plain, unencrypted video sources. Everything else keeps the host URL.
 */
public final class VideoProxyHook implements AutoCloseable {

    /** Configuration key for the local video proxy. */
    public static final String KEY_ENABLED = "video_proxy_enabled";
    /** Configuration key for the number of parallel range connections. */
    public static final String KEY_CONNECTIONS = "video_proxy_connections";
    /** Configuration key for the whole-file cache budget in megabytes. */
    public static final String KEY_CACHE_BUDGET_MB = "video_proxy_cache_budget_mb";
    /** Configuration key that limits caching to unmetered networks. */
    public static final String KEY_WIFI_ONLY = "video_proxy_wifi_only";

    static final int DEFAULT_CONNECTIONS = 4;
    static final int DEFAULT_CACHE_BUDGET_MB = 512;

    private final BehaviorHookGroup hooks;
    private final LocalVideoProxy proxy = new LocalVideoProxy();
    private final Application application;
    private volatile LocalOptions options;
    private volatile boolean closed;
    private volatile int rewrites;

    private VideoProxyHook(Application application, LocalOptions options, Consumer<String> log) {
        this.application = application;
        this.options = options;
        this.hooks = new BehaviorHookGroup("video_proxy", log == null ? ignored -> { } : log);
    }

    /** Installs the proxy hook for the given host report. */
    public static VideoProxyHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Context context, LocalOptions options,
                                         Consumer<String> log) throws Exception {
        if (framework == null) throw new IllegalArgumentException("Framework is required");
        if (context == null) throw new IllegalArgumentException("Target context is required");
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) {
            throw new IllegalStateException("Target Application unavailable");
        }
        VideoProxyHook hook = new VideoProxyHook((Application) appContext, options, log);
        try {
            Method aweme = HostTargets.method(report, "feed_item_bind_aweme", loader);
            hook.hooks.add(framework, aweme, chain -> {
                Object work = chain.getArg(0);
                // The rewrite has to happen before the host reads the playback address.
                hook.onWork(work);
                return chain.proceed();
            });
        } catch (Throwable failure) {
            hook.close();
            throw failure;
        }
        return hook;
    }

    private void onWork(Object aweme) {
        if (closed || aweme == null) return;
        LocalOptions current = options;
        if (current == null || !current.enabled(KEY_ENABLED)) return;
        int connections = VideoProxyCachePolicy.parseConnections(
                current.text(KEY_CONNECTIONS, String.valueOf(DEFAULT_CONNECTIONS)));
        if (connections <= 0) return;
        String upstream = VideoProxySource.originalUrl(aweme);
        if (upstream == null) return;
        if (!proxy.running() && !proxy.start(connections)) {
            DouAiLog.event("video_proxy", "server", "start_failed", upstream);
            return;
        }
        String loopback = proxy.register(upstream);
        if (loopback == null) return;
        int replaced = VideoProxyRewriter.rewrite(aweme, upstream, loopback);
        if (replaced <= 0) {
            proxy.unregister(loopback);
            return;
        }
        rewrites++;
        DouAiLog.event("video_proxy", "rewrite", "registered",
                "rewrites=" + rewrites + ";replaced=" + replaced);
    }

    /** Applies the latest configuration to the running proxy. */
    public void applyOptions(LocalOptions updated) {
        options = updated;
        LocalOptions current = updated;
        if (current == null) return;
        int budget = VideoProxyCachePolicy.parseBudgetMb(
                current.text(KEY_CACHE_BUDGET_MB, String.valueOf(DEFAULT_CACHE_BUDGET_MB)));
        boolean wifiOnly = current.enabled(KEY_WIFI_ONLY);
        File directory = new File(application.getCacheDir(), "douai-video-proxy");
        proxy.configureCache(directory, new VideoProxyCachePolicy(current.enabled(KEY_ENABLED), budget, wifiOnly));
        proxy.setUnmeteredNetwork(isUnmetered());
    }

    /** Returns the number of playback URLs rewritten so far. */
    public int rewriteCount() {
        return rewrites;
    }

    private boolean isUnmetered() {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    application.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(
                    manager.getActiveNetwork());
            if (capabilities == null) return true;
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (RuntimeException failure) {
            // An unknown network state must not block caching entirely.
            return true;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            hooks.close();
        } catch (RuntimeException ignored) {
            // Closing a partially installed hook must not break module shutdown.
        }
        proxy.stop();
    }
}
