package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UsageLimitSettingsTest {
    private SharedPreferences preferences() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("usage-limit-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return prefs;
    }

    @Test
    public void startsDisabledWithSafeDefaults() {
        UsageLimitSettings settings = UsageLimitSettings.readFrom(LocalOptions.readFrom(preferences()));
        assertFalse(settings.reminderEnabled());
        assertFalse(settings.exitEnabled());
        assertFalse(settings.keepScreen);
        assertFalse(settings.repeatReminder);
        assertFalse(settings.reminderVibrate);
        assertTrue(settings.exitCountdown);
        assertEquals(UsageLimitSettings.DEFAULT_REMINDER_TEXT, settings.reminderText);
    }

    @Test
    public void readsMinutesFromNumericAndStringValues() {
        SharedPreferences prefs = preferences();
        prefs.edit().putInt(UsageLimitSettings.REMINDER_MINUTES, 30).commit();
        UsageLimitSettings numeric = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertTrue(numeric.reminderEnabled());
        assertEquals(30, numeric.reminderMinutes);
        assertEquals(30L * 60_000L, numeric.reminderIntervalMs());

        prefs.edit().putString(UsageLimitSettings.REMINDER_MINUTES, "45").commit();
        UsageLimitSettings textual = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertEquals(45, textual.reminderMinutes);
    }

    @Test
    public void invalidMinutesFallBackToDisabled() {
        SharedPreferences prefs = preferences();
        prefs.edit().putString(UsageLimitSettings.EXIT_MINUTES, "not-a-number").commit();
        UsageLimitSettings settings = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertFalse(settings.exitEnabled());
        assertEquals(0, settings.exitMinutes);
    }

    @Test
    public void negativeAndOversizedMinutesAreClamped() {
        SharedPreferences prefs = preferences();
        prefs.edit().putInt(UsageLimitSettings.EXIT_MINUTES, -5)
                .putInt(UsageLimitSettings.REMINDER_MINUTES, 999999).commit();
        UsageLimitSettings settings = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertEquals(0, settings.exitMinutes);
        assertEquals(UsageLimitSettings.MAX_MINUTES, settings.reminderMinutes);
    }

    @Test
    public void blankReminderTextFallsBackToDefault() {
        SharedPreferences prefs = preferences();
        prefs.edit().putString(UsageLimitSettings.REMINDER_TEXT, "   ").commit();
        UsageLimitSettings settings = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertEquals(UsageLimitSettings.DEFAULT_REMINDER_TEXT, settings.reminderText);
    }

    @Test
    public void customReminderTextIsPreserved() {
        SharedPreferences prefs = preferences();
        prefs.edit().putString(UsageLimitSettings.REMINDER_TEXT, " 该歇会儿了 ").commit();
        UsageLimitSettings settings = UsageLimitSettings.readFrom(LocalOptions.readFrom(prefs));
        assertEquals("该歇会儿了", settings.reminderText);
    }

    @Test
    public void readsFromRawSnapshotValues() {
        Map<String, Object> values = new HashMap<>();
        values.put(UsageLimitSettings.REMINDER_MINUTES, 20);
        values.put(UsageLimitSettings.REPEAT_REMINDER, Boolean.TRUE);
        values.put(UsageLimitSettings.KEEP_SCREEN, Boolean.TRUE);
        values.put(UsageLimitSettings.EXIT_COUNTDOWN, Boolean.FALSE);
        UsageLimitSettings settings = UsageLimitSettings.readFrom(new LocalOptions(values));
        assertTrue(settings.reminderEnabled());
        assertTrue(settings.repeatReminder);
        assertTrue(settings.keepScreen);
        assertFalse(settings.exitCountdown);
    }

    @Test
    public void emptySnapshotKeepsEveryLimitDisabled() {
        UsageLimitSettings settings = UsageLimitSettings.readFrom(
                new LocalOptions(Collections.<String, Object>emptyMap()));
        assertFalse(settings.reminderEnabled());
        assertFalse(settings.exitEnabled());
        assertFalse(settings.keepScreen);
    }

    @Test
    public void idleAndLongVideoBudgetsDefaultToDisabled() {
        UsageLimitSettings settings = UsageLimitSettings.readFrom(
                new LocalOptions(Collections.<String, Object>emptyMap()));
        assertFalse(settings.idleExitEnabled());
        assertFalse(settings.longVideoReminderEnabled());
        assertEquals(0, settings.idleMinutes);
        assertEquals(0, settings.longSeconds);
    }

    @Test
    public void idleAndLongVideoBudgetsAreReadFromTheSnapshot() {
        Map<String, Object> values = new HashMap<>();
        values.put(UsageLimitSettings.IDLE_MINUTES, 15);
        values.put(UsageLimitSettings.LONG_SECONDS, 600);
        UsageLimitSettings settings = UsageLimitSettings.readFrom(new LocalOptions(values));
        assertTrue(settings.idleExitEnabled());
        assertTrue(settings.longVideoReminderEnabled());
        assertEquals(15, settings.idleMinutes);
        assertEquals(600, settings.longSeconds);
        assertEquals(15L * 60_000L, settings.idleBudgetMs());
        assertEquals(600_000L, settings.longVideoThresholdMs());
    }

    @Test
    public void oversizedSecondsAreClamped() {
        Map<String, Object> values = new HashMap<>();
        values.put(UsageLimitSettings.LONG_SECONDS, 999999);
        UsageLimitSettings settings = UsageLimitSettings.readFrom(new LocalOptions(values));
        assertEquals(UsageLimitSettings.MAX_SECONDS, settings.longSeconds);
    }

    @Test
    public void exitIsEnabledWhenEitherBudgetIsConfigured() {
        Map<String, Object> values = new HashMap<>();
        values.put(UsageLimitSettings.IDLE_MINUTES, 5);
        UsageLimitSettings settings = UsageLimitSettings.readFrom(new LocalOptions(values));
        assertTrue(settings.exitEnabled());
        assertFalse(settings.exitEnabled() && settings.exitMinutes > 0);
    }
}
