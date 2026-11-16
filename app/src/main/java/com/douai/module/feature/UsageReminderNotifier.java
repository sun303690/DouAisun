package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

/**
 * Rest reminder delivery.
 *
 * <p>The reminder is delivered as an in-app toast so that it never depends on a notification
 * permission the host app may not hold.
 */
final class UsageReminderNotifier {
    private UsageReminderNotifier() { }

    static void show(Activity activity, String text, boolean vibrate) {
        if (activity == null) return;
        try {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        } catch (RuntimeException ignored) {
            // A toast failure must not break the timer.
        }
        if (vibrate) vibrate(activity);
    }

    private static void vibrate(Context context) {
        try {
            Vibrator vibrator = resolveVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(400L);
            }
        } catch (RuntimeException ignored) {
            // Vibration is optional feedback only.
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator resolveVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
