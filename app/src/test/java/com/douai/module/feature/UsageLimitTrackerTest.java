package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.UsageLimitSettings;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Verifies the foreground-usage accounting used by the rest reminder and the timed exit. */
public class UsageLimitTrackerTest {
    private static final long MINUTE_MS = 60_000L;

    private static UsageLimitSettings settings(Map<String, Object> values) {
        return UsageLimitSettings.readFrom(new LocalOptions(new HashMap<>(values)));
    }

    @Test
    public void reminderFiresOnceWhenRepeatingIsDisabled() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.REMINDER_MINUTES, 30);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);

        assertFalse(tracker.isReminderDue(config, 29 * MINUTE_MS));
        assertTrue(tracker.isReminderDue(config, 30 * MINUTE_MS));
        assertFalse(tracker.isReminderDue(config, 59 * MINUTE_MS));
        assertTrue(tracker.reminderDelivered());
    }

    @Test
    public void reminderRepeatsWhenRepeatingIsEnabled() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.REMINDER_MINUTES, 30);
            put(UsageLimitSettings.REPEAT_REMINDER, true);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);

        assertTrue(tracker.isReminderDue(config, 30 * MINUTE_MS));
        assertFalse(tracker.reminderDelivered());
        assertTrue(tracker.isReminderDue(config, 60 * MINUTE_MS));
    }

    @Test
    public void backgroundTimeDoesNotCountTowardsTheReminder() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.REMINDER_MINUTES, 30);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);
        tracker.onForegroundStopped(10 * MINUTE_MS);
        tracker.onForegroundStarted(20 * MINUTE_MS);

        assertFalse(tracker.isReminderDue(config, 30 * MINUTE_MS));
        assertTrue(tracker.isReminderDue(config, 40 * MINUTE_MS));
    }

    @Test
    public void exitBudgetKeepsAccumulatingAcrossSessions() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.EXIT_MINUTES, 60);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);
        tracker.onForegroundStopped(30 * MINUTE_MS);
        tracker.onForegroundStarted(60 * MINUTE_MS);

        assertFalse(tracker.isExitDue(config, 60 * MINUTE_MS));
        assertTrue(tracker.isExitDue(config, 90 * MINUTE_MS));
        assertEquals(30 * MINUTE_MS, tracker.exitElapsedMs());
    }

    @Test
    public void idleBudgetResetsAfterInteraction() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.IDLE_MINUTES, 10);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);

        assertFalse(tracker.isIdleExitDue(config, 9 * MINUTE_MS));
        tracker.onUserInteraction(9 * MINUTE_MS);
        assertFalse(tracker.isIdleExitDue(config, 15 * MINUTE_MS));
        assertTrue(tracker.isIdleExitDue(config, 19 * MINUTE_MS));
    }

    @Test
    public void disabledBudgetsNeverTrigger() {
        UsageLimitSettings config = settings(new HashMap<String, Object>());
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);

        assertFalse(config.reminderEnabled());
        assertFalse(config.exitEnabled());
        assertFalse(config.idleExitEnabled());
        assertFalse(tracker.isReminderDue(config, 600 * MINUTE_MS));
        assertFalse(tracker.isExitDue(config, 600 * MINUTE_MS));
        assertFalse(tracker.isIdleExitDue(config, 600 * MINUTE_MS));
    }

    @Test
    public void resetClearsEveryBudget() {
        UsageLimitSettings config = settings(new HashMap<String, Object>() {{
            put(UsageLimitSettings.REMINDER_MINUTES, 30);
            put(UsageLimitSettings.EXIT_MINUTES, 60);
        }});
        UsageLimitTracker tracker = new UsageLimitTracker();
        tracker.onForegroundStarted(0L);
        tracker.advance(20 * MINUTE_MS);

        tracker.reset();

        assertEquals(0L, tracker.reminderElapsedMs());
        assertEquals(0L, tracker.exitElapsedMs());
        assertFalse(tracker.reminderDelivered());
    }
}
