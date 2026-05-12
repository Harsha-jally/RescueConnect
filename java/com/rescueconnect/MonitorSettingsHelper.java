package com.rescueconnect;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * MonitorSettingsHelper
 * ─────────────────────────────────────────────────────────────────────────────
 * Single source of truth for all user-configurable timing settings.
 * Stored in SharedPreferences so they survive app restarts and service kills.
 *
 * Settings managed:
 *   1. SOS Cancel Window        — time after auto-detection before SOS fires
 *                                  (used by EmergencyDetectionService)
 *   2. Monitor Check-in Interval — how often the "Are you safe?" ping appears
 *                                  (used by MonitorMeService)
 *   3. Monitor Response Timeout  — how long user has to reply before auto-SOS
 *                                  (used by MonitorMeService)
 *
 * All times stored and returned in MILLISECONDS.
 *
 * MCQ options presented to the user (label → ms value):
 *   "30 seconds"  →     30_000
 *   "1 minute"    →     60_000
 *   "2 minutes"   →    120_000
 *   "3 minutes"   →    180_000
 *   "5 minutes"   →    300_000
 */
public class MonitorSettingsHelper {

    private static final String PREFS_NAME = "monitor_settings";

    // SharedPreferences keys
    public static final String KEY_SOS_CANCEL_WINDOW      = "sos_cancel_window_ms";
    public static final String KEY_MONITOR_PING_INTERVAL  = "monitor_ping_interval_ms";
    public static final String KEY_MONITOR_RESPONSE_TIMEOUT = "monitor_response_timeout_ms";

    // Default values (original hardcoded values)
    public static final long DEFAULT_SOS_CANCEL_WINDOW       = 15_000L;  // 15 s
    public static final long DEFAULT_MONITOR_PING_INTERVAL   = 5 * 60_000L; // 5 min
    public static final long DEFAULT_MONITOR_RESPONSE_TIMEOUT = 2 * 60_000L; // 2 min

    // ── MCQ option arrays (parallel — index maps label ↔ ms value) ───────────

    public static final String[] TIME_LABELS = {
            "30 seconds",
            "1 minute",
            "2 minutes",
            "3 minutes",
            "5 minutes"
    };

    public static final long[] TIME_VALUES_MS = {
            30_000L,
            60_000L,
            120_000L,
            180_000L,
            300_000L
    };

    // ── Getters ───────────────────────────────────────────────────────────────

    public static long getSosCancelWindow(Context ctx) {
        return prefs(ctx).getLong(KEY_SOS_CANCEL_WINDOW, DEFAULT_SOS_CANCEL_WINDOW);
    }

    public static long getMonitorPingInterval(Context ctx) {
        return prefs(ctx).getLong(KEY_MONITOR_PING_INTERVAL, DEFAULT_MONITOR_PING_INTERVAL);
    }

    public static long getMonitorResponseTimeout(Context ctx) {
        return prefs(ctx).getLong(KEY_MONITOR_RESPONSE_TIMEOUT, DEFAULT_MONITOR_RESPONSE_TIMEOUT);
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    public static void setSosCancelWindow(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_SOS_CANCEL_WINDOW, ms).apply();
    }

    public static void setMonitorPingInterval(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_MONITOR_PING_INTERVAL, ms).apply();
    }

    public static void setMonitorResponseTimeout(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_MONITOR_RESPONSE_TIMEOUT, ms).apply();
    }

    // ── Utility — find the best matching label for a stored ms value ──────────

    /**
     * Returns the index into TIME_LABELS / TIME_VALUES_MS that best matches
     * the stored value (exact match, or 0 if not found).
     */
    public static int indexForValue(long ms) {
        for (int i = 0; i < TIME_VALUES_MS.length; i++) {
            if (TIME_VALUES_MS[i] == ms) return i;
        }
        return 0;
    }

    /** Formats a millisecond value into a human-readable label. */
    public static String labelForValue(long ms) {
        int idx = indexForValue(ms);
        return TIME_LABELS[idx];
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}