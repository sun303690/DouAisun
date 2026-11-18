package com.douai.module.config;

import android.content.Context;

import com.douai.module.diagnostics.DouAiLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Stores the configuration snapshot that existed right before the last restore, so a user can undo
 * an accidental import. Only one undo point is kept: the next restore replaces it.
 *
 * <p>The snapshot uses the very same envelope as a normal configuration export, so the undo path
 * reuses {@link ConfigTransfer#inspect} and {@link ConfigTransfer#apply}. Value types therefore stay
 * intact instead of being flattened into strings.
 */
public final class ConfigUndoPoint {
    private static final String FILE_NAME = "config_undo_point.json";

    private ConfigUndoPoint() { }

    private static File file(Context context) {
        if (context == null) return null;
        File directory = context.getFilesDir();
        if (directory == null) return null;
        return new File(directory, FILE_NAME);
    }

    /** True when an undo point is available. */
    public static boolean has(Context context) {
        File target = file(context);
        return target != null && target.isFile() && target.length() > 0;
    }

    /**
     * Records the current configuration as the undo point. Called before a restore is applied so the
     * previous state can be restored later. Secrets are never written to disk.
     */
    public static void record(Context context, SettingsStore store) {
        File target = file(context);
        if (target == null || store == null) return;
        String encoded;
        try {
            encoded = ConfigTransfer.export(store, false);
        } catch (RuntimeException failure) {
            DouAiLog.failure("config_undo", "record", failure);
            return;
        }
        byte[] data = encoded.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream stream = new FileOutputStream(target, false)) {
            stream.write(data);
            stream.flush();
            stream.getFD().sync();
        } catch (IOException unavailable) {
            DouAiLog.failure("config_undo", "record", unavailable);
            // A missing undo point must never block the restore itself.
        }
    }

    /**
     * Reads the stored undo point.
     *
     * @return the snapshot, or {@code null} when nothing is stored or the file cannot be read
     */
    public static String read(Context context) {
        File target = file(context);
        if (target == null || !target.isFile()) return null;
        try {
            byte[] data = new byte[(int) Math.min(target.length(), ConfigTransfer.MAX_BYTES)];
            int read = 0;
            try (FileInputStream stream = new FileInputStream(target)) {
                while (read < data.length) {
                    int count = stream.read(data, read, data.length - read);
                    if (count == -1) break;
                    read += count;
                }
            }
            if (read == 0) return null;
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unavailable) {
            DouAiLog.failure("config_undo", "read", unavailable);
            return null;
        }
    }

    /**
     * Builds the preview that restores the undo point.
     *
     * @return the preview, or {@code null} when no undo point is stored or it cannot be parsed
     */
    public static ConfigBackup.Preview preview(SettingsStore store, Context context) {
        String encoded = read(context);
        if (encoded == null) return null;
        try {
            return ConfigBackup.inspect(store, encoded, ConfigTransfer.Mode.REPLACE);
        } catch (RuntimeException unavailable) {
            DouAiLog.failure("config_undo", "preview", unavailable);
            return null;
        }
    }

    /** Drops the undo point once it has been consumed. */
    public static void clear(Context context) {
        File target = file(context);
        if (target == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        } catch (RuntimeException unavailable) {
            DouAiLog.failure("config_undo", "clear", unavailable);
        }
    }
}
