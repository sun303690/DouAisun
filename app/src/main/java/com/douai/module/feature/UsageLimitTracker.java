package com.douai.module.feature;

import com.douai.module.config.UsageLimitSettings;

/**
 * Pure foreground-usage accounting for the rest reminder and the timed exit.
 *
 * <p>The tracker holds no Android reference so the budget arithmetic can be verified
 * deterministically. All timestamps are supplied by the caller.
 *
 * <p>Budget semantics:
 * <ul>
 *   <li>The reminder budget advances only while the host is in the foreground and restarts
 *       after every delivery. With repeating disabled it fires once per foreground session.</li>
 *   <li>The exit budget keeps accumulating until the user changes the setting, so touching
 *       the screen does not postpone the exit.</li>
 *   <li>The idle budget advances only while no touch is registered and resets as soon as the
 *       user interacts or the app returns to the foreground.</li>
 * </ul>
 */
public final class UsageLimitTracker {
    /** Reminder budget state. */
    private long reminderElapsedMs;
    /** Exit budget state. */
    private long exitElapsedMs;
    /** Idle budget state. */
    private long idleElapsedMs;
    /** Whether the single-shot reminder has already been delivered. */
    private boolean reminderDelivered;
    /** Anchor of the currently running foreground segment; {@code -1} when not in foreground. */
    private long foregroundAnchorMs = -1L;
    /** Anchor of the currently running idle segment; {@code -1} when no idle segment runs. */
    private long idleAnchorMs = -1L;

    /** Resets every budget and re-arms the single-shot reminder. */
    public void reset() {
        reminderElapsedMs = 0L;
        exitElapsedMs = 0L;
        idleElapsedMs = 0L;
        reminderDelivered = false;
        foregroundAnchorMs = -1L;
        idleAnchorMs = -1L;
    }

    /** Marks the start of a foreground segment at {@code nowMs}. */
    public void onForegroundStarted(long nowMs) {
        foregroundAnchorMs = nowMs;
        idleAnchorMs = nowMs;
    }

    /** Marks the end of a foreground segment at {@code nowMs}. */
    public void onForegroundStopped(long nowMs) {
        advance(nowMs);
        foregroundAnchorMs = -1L;
        idleAnchorMs = -1L;
    }

    /** Registers a user interaction, which restarts the idle budget at {@code nowMs}. */
    public void onUserInteraction(long nowMs) {
        idleElapsedMs = 0L;
        idleAnchorMs = nowMs;
    }

    /** Accumulates the foreground time since the last anchor. */
    public void advance(long nowMs) {
        if (foregroundAnchorMs < 0L) return;
        long delta = Math.max(0L, nowMs - foregroundAnchorMs);
        foregroundAnchorMs = nowMs;
        if (delta <= 0L) return;
        reminderElapsedMs += delta;
        exitElapsedMs += delta;
        if (idleAnchorMs >= 0L) idleElapsedMs += delta;
    }

    /**
     * Returns {@code true} when the rest reminder is due.
     *
     * <p>The budget is reset before the caller shows the reminder so a repeated reminder
     * restarts its own interval.
     */
    public boolean isReminderDue(UsageLimitSettings settings, long nowMs) {
        if (settings == null || !settings.reminderEnabled()) return false;
        advance(nowMs);
        if (reminderDelivered && !settings.repeatReminder) return false;
        long interval = settings.reminderIntervalMs();
        if (interval <= 0L || reminderElapsedMs < interval) return false;
        reminderElapsedMs = 0L;
        if (!settings.repeatReminder) reminderDelivered = true;
        return true;
    }

    /** Returns {@code true} when the idle exit budget is exhausted. */
    public boolean isIdleExitDue(UsageLimitSettings settings, long nowMs) {
        if (settings == null || !settings.idleExitEnabled()) return false;
        advance(nowMs);
        long budget = settings.idleBudgetMs();
        if (budget <= 0L || idleElapsedMs < budget) return false;
        idleElapsedMs = 0L;
        return true;
    }

    /** Returns {@code true} when the accumulated exit budget is exhausted. */
    public boolean isExitDue(UsageLimitSettings settings, long nowMs) {
        if (settings == null || !settings.exitEnabled()) return false;
        advance(nowMs);
        long budget = settings.exitBudgetMs();
        if (budget <= 0L) return false;
        return exitElapsedMs >= budget;
    }

    /** Milliseconds accumulated towards the exit budget. */
    public long exitElapsedMs() { return exitElapsedMs; }

    /** Milliseconds accumulated towards the reminder budget. */
    public long reminderElapsedMs() { return reminderElapsedMs; }

    /** Milliseconds accumulated towards the idle budget. */
    public long idleElapsedMs() { return idleElapsedMs; }

    /** Whether the single-shot reminder has already been delivered. */
    public boolean reminderDelivered() { return reminderDelivered; }
}
