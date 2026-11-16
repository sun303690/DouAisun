package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
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

    /**
     * Vibrates the device when the host app holds the vibrate permission.
     *
     * <p>The module runs inside the host process, so the host manifest decides whether the
     * permission is granted. The check keeps the call safe when the host does not declare it.
     */
    @SuppressLint("MissingPermission")
    private static void vibrate(Context context) {
        try {
            if (!hasVibratePermission(context)) return;
            Vibrator vibrator = resolveVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            vibrator.vibrate(VibrationEffect.createOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {
            // Vibration is optional feedback only.
        }
    }

    private static boolean hasVibratePermission(Context context) {
        try {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            return false;
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
