package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowManager;

import com.douai.module.config.UsageLimitSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.ref.WeakReference;

/**
 * Foreground-usage driven rest reminders and timed exits.
 *
 * <p>The reminder and the exit budget advance only while the host app is in the foreground.
 * The reminder restarts after every delivery (or every interval when repeating is enabled),
 * while the exit budget keeps accumulating until the user changes the setting.
 *
 * <p>The accounting itself lives in {@link UsageLimitTracker} so it can be verified without an
 * Android runtime; this class only wires the tracker to the host lifecycle.
 */
public final class UsageLimitController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    /** Poll interval while the host is in the foreground. */
    private static final long TICK_INTERVAL_MS = 1000L;
    /** Exit warning length; the countdown can be cancelled by the user. */
    private static final long EXIT_WARNING_MS = 10_000L;

    private final Application application;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private final UsageLimitTracker tracker = new UsageLimitTracker();

    private volatile UsageLimitSettings settings;
    private volatile boolean foreground;
    private long exitWarningUntil;
    private boolean exitWarningActive;
    private boolean keepScreenApplied;
    private boolean closed;

    private UsageLimitController(Application application, UsageLimitSettings settings) {
        this.application = application;
        this.settings = settings;
    }

    public static UsageLimitController install(Context context, UsageLimitSettings settings) {
        if (context == null) throw new IllegalArgumentException("Target context is required");
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) throw new IllegalStateException("Target Application unavailable");
        Application application = (Application) appContext;
        UsageLimitController controller = new UsageLimitController(application, settings);
        application.registerActivityLifecycleCallbacks(controller);
        controller.scheduleTick();
        return controller;
    }

    /** Applies refreshed preferences without reinstalling the lifecycle observer. */
    public void setSettings(UsageLimitSettings settings) {
        if (closed) return;
        this.settings = settings == null ? this.settings : settings;
        if (!this.settings.keepScreen) restoreKeepScreen();
        if (!this.settings.reminderEnabled()) tracker.reset();
        if (!this.settings.exitEnabled()) {
            exitWarningActive = false;
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (closed || activity == null) return;
        if (!application.getPackageName().equals(activity.getPackageName())) return;
        resumedActivity = new WeakReference<>(activity);
        if (!foreground) {
            foreground = true;
            tracker.onForegroundStarted(SystemClock.elapsedRealtime());
            DouAiLog.event("usage_limit", "foreground", "resumed",
                    "reminder_ms=" + tracker.reminderElapsedMs() + ";exit_ms=" + tracker.exitElapsedMs());
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (closed || activity == null) return;
        if (!application.getPackageName().equals(activity.getPackageName())) return;
        pause();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (closed || activity == null) return;
        if (!application.getPackageName().equals(activity.getPackageName())) return;
        pause();
    }

    private void pause() {
        if (!foreground) return;
        tracker.onForegroundStopped(SystemClock.elapsedRealtime());
        foreground = false;
    }

    private void scheduleTick() {
        if (closed) return;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tick, TICK_INTERVAL_MS);
    }

    private void tick() {
        if (closed) return;
        try {
            if (foreground) {
                long now = SystemClock.elapsedRealtime();
                applyKeepScreen();
                evaluateReminder(now);
                evaluateExit(now);
            }
        } catch (RuntimeException failure) {
            DouAiLog.failure("usage_limit", "tick", failure);
        } finally {
            scheduleTick();
        }
    }

    private void evaluateReminder(long now) {
        if (!tracker.isReminderDue(settings, now)) return;
        Activity activity = resumedActivity.get();
        DouAiLog.event("usage_limit", "reminder", "delivered",
                "interval_ms=" + settings.reminderIntervalMs() + ";repeat=" + settings.repeatReminder
                        + ";vibrate=" + settings.reminderVibrate);
        if (activity != null) {
            UsageReminderNotifier.show(activity, settings.reminderText, settings.reminderVibrate);
        }
    }

    private void evaluateExit(long now) {
        if (!settings.exitEnabled()) {
            exitWarningActive = false;
            return;
        }
        if (exitWarningActive) {
            if (now >= exitWarningUntil) performExit();
            return;
        }
        boolean due = tracker.isIdleExitDue(settings, now);
        if (!due) due = tracker.isExitDue(settings, now);
        if (!due) return;
        exitWarningActive = true;
        exitWarningUntil = now + EXIT_WARNING_MS;
        Activity activity = resumedActivity.get();
        DouAiLog.event("usage_limit", "exit", "warning",
                "budget_ms=" + settings.exitBudgetMs() + ";countdown_ms=" + EXIT_WARNING_MS);
        if (activity != null) {
            UsageExitPrompt.show(activity, EXIT_WARNING_MS, this::performExit, this::cancelExitWarning);
        } else {
            performExit();
        }
    }

    private void cancelExitWarning() {
        exitWarningActive = false;
        DouAiLog.event("usage_limit", "exit", "cancelled", null);
    }

    private void performExit() {
        if (!exitWarningActive) return;
        exitWarningActive = false;
        Activity activity = resumedActivity.get();
        DouAiLog.event("usage_limit", "exit", "performed", "elapsed_ms=" + tracker.exitElapsedMs());
        if (activity == null) return;
        try {
            Intent exit = new Intent(Intent.ACTION_MAIN);
            exit.addCategory(Intent.CATEGORY_HOME);
            exit.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(exit);
            activity.finishAndRemoveTask();
        } catch (RuntimeException failure) {
            DouAiLog.failure("usage_limit", "exit", failure);
        }
    }

    private void applyKeepScreen() {
        if (!settings.keepScreen) {
            restoreKeepScreen();
            return;
        }
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Window window = activity.getWindow();
            if (window == null || keepScreenApplied) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            keepScreenApplied = true;
        } catch (RuntimeException failure) {
            DouAiLog.failure("usage_limit", "keep_screen", failure);
        }
    }

    private void restoreKeepScreen() {
        if (!keepScreenApplied) return;
        keepScreenApplied = false;
        Activity activity = resumedActivity.get();
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            if (window != null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (RuntimeException ignored) {
            // Restoring the host window must never crash the module.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        restoreKeepScreen();
        application.unregisterActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
