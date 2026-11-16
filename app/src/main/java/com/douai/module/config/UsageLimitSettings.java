package com.douai.module.config;

import java.util.Locale;

/**
 * Foreground-usage limits: rest reminders, timed exit, and screen keeping.
 *
 * <p>Values live in the host-local preference store and are read through {@link LocalOptions}, so
 * the reminder and exit budgets can be evaluated without touching any host private API.
 */
public final class UsageLimitSettings {
    /** Reminder interval in minutes; {@code 0} disables the reminder. */
    public static final String REMINDER_MINUTES = "usage_reminder_minutes";
    /** Repeat the reminder every interval instead of once per foreground session. */
    public static final String REPEAT_REMINDER = "usage_repeat_reminder";
    /** Custom reminder text. */
    public static final String REMINDER_TEXT = "usage_reminder_text";
    /** Vibrate together with the reminder. */
    public static final String REMINDER_VIBRATE = "usage_reminder_vibrate";
    /** Long-video threshold in seconds; {@code 0} disables the long-video reminder. */
    public static final String LONG_SECONDS = "usage_long_seconds";
    /** Exit budget in minutes; {@code 0} disables the timed exit. */
    public static final String EXIT_MINUTES = "usage_exit_minutes";
    /** Idle budget in minutes; {@code 0} disables the idle exit. */
    public static final String IDLE_MINUTES = "usage_idle_minutes";
    /** Show a cancellable countdown before the timed exit. */
    public static final String EXIT_COUNTDOWN = "usage_exit_countdown";
    /** Keep the screen on while the host is in the foreground. */
    public static final String KEEP_SCREEN = "usage_keep_screen";
    /** Also put the device to sleep when the timed exit fires; needs root. */
    public static final String ROOT_SLEEP = "usage_root_sleep";

    public static final String DEFAULT_REMINDER_TEXT = "你已使用抖音一段时间，休息一下吧。";
    public static final int MAX_MINUTES = 1440;
    public static final int MAX_SECONDS = 86400;

    public final int reminderMinutes;
    public final boolean repeatReminder;
    public final String reminderText;
    public final boolean reminderVibrate;
    public final int longSeconds;
    public final int exitMinutes;
    public final int idleMinutes;
    public final boolean exitCountdown;
    public final boolean keepScreen;
    public final boolean rootSleep;

    private UsageLimitSettings(LocalOptions options) {
        reminderMinutes = clampMinutes(options.integer(REMINDER_MINUTES, 0));
        repeatReminder = options.enabled(REPEAT_REMINDER);
        reminderText = readText(options);
        reminderVibrate = options.enabled(REMINDER_VIBRATE);
        longSeconds = clampSeconds(options.integer(LONG_SECONDS, 0));
        exitMinutes = clampMinutes(options.integer(EXIT_MINUTES, 0));
        idleMinutes = clampMinutes(options.integer(IDLE_MINUTES, 0));
        exitCountdown = options.enabled(EXIT_COUNTDOWN, true);
        keepScreen = options.enabled(KEEP_SCREEN);
        rootSleep = options.enabled(ROOT_SLEEP);
    }

    public static UsageLimitSettings readFrom(LocalOptions options) {
        return new UsageLimitSettings(options);
    }

    public boolean reminderEnabled() { return reminderMinutes > 0; }

    public boolean exitEnabled() { return exitMinutes > 0 || idleMinutes > 0; }

    public boolean idleExitEnabled() { return idleMinutes > 0; }

    public boolean longVideoReminderEnabled() { return longSeconds > 0; }

    public long reminderIntervalMs() { return reminderMinutes * 60_000L; }

    public long exitBudgetMs() { return exitMinutes * 60_000L; }

    public long idleBudgetMs() { return idleMinutes * 60_000L; }

    public long longVideoThresholdMs() { return longSeconds * 1000L; }

    private static String readText(LocalOptions options) {
        String trimmed = options.text(REMINDER_TEXT, "").trim();
        return trimmed.isEmpty() ? DEFAULT_REMINDER_TEXT : trimmed;
    }

    private static int clampMinutes(int minutes) {
        if (minutes < 0) return 0;
        return Math.min(minutes, MAX_MINUTES);
    }

    private static int clampSeconds(int seconds) {
        if (seconds < 0) return 0;
        return Math.min(seconds, MAX_SECONDS);
    }

    /** Human readable minute count used by the settings screen. */
    public static String describeMinutes(int minutes) {
        return String.format(Locale.US, "%d 分钟", minutes);
    }

    /** Human readable second count used by the settings screen. */
    public static String describeSeconds(int seconds) {
        if (seconds % 60 == 0) return String.format(Locale.US, "%d 分钟", seconds / 60);
        return String.format(Locale.US, "%d 秒", seconds);
    }
}
