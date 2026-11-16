package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.douai.module.ui.miuix.MiuixDialogBuilder;

/**
 * Cancellable countdown shown before a timed exit.
 *
 * <p>The dialog closes itself when the countdown ends. Cancelling it keeps the host running; the
 * caller decides what to do next, so the exit path stays in one place.
 */
final class UsageExitPrompt {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Runs when the countdown ends without being cancelled. */
    interface TimeoutListener {
        void onTimeout();
    }

    /** Runs when the user cancels the countdown. */
    interface CancelListener {
        void onCancel();
    }

    private UsageExitPrompt() { }

    static void show(Activity activity, long countdownMs, TimeoutListener onTimeout,
            CancelListener onCancel) {
        if (activity == null) return;
        MAIN.post(() -> present(activity, countdownMs, onTimeout, onCancel));
    }

    private static void present(Activity activity, long countdownMs, TimeoutListener onTimeout,
            CancelListener onCancel) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            long seconds = Math.max(1L, countdownMs / 1000L);
            MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
            builder.setTitle("即将退出抖音");
            builder.setMessage(seconds + " 秒后退出抖音，可取消本次退出。");
            builder.setNegativeButton("取消", (view, which) -> {
                if (onCancel != null) onCancel.onCancel();
            });
            builder.setPositiveButton("立即退出", (view, which) -> {
                if (onTimeout != null) onTimeout.onTimeout();
            });
            AlertDialog dialog = builder.create();
            dialog.setOnDismissListener(ignored -> {
                // Dismissing the dialog without a button press means the countdown was cancelled.
            });
            dialog.show();
            MAIN.postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (onTimeout != null) onTimeout.onTimeout();
            }, countdownMs);
        } catch (RuntimeException ignored) {
            // A dialog failure must not block the exit path.
            if (onTimeout != null) onTimeout.onTimeout();
        }
    }
}
