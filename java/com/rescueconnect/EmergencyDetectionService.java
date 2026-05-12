package com.rescueconnect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EmergencyDetectionService
 * ─────────────────────────────────────────────────────────────────────────────
 * Runs as a persistent foreground service even when the app is closed.
 * Monitors FOUR independent threat signals simultaneously:
 *
 *   1. FREE-FALL + IMPACT  — phone dropped / thrown (accelerometer near-zero
 *      for ≥ 200 ms, followed immediately by a hard landing spike ≥ 22 m/s²)
 *
 *   2. CRASH / COLLISION   — single violent spike ≥ 30 m/s² or a rate-of-change
 *      (jerk) ≥ 35 m/s² between consecutive samples (vehicle crash, hard fall)
 *
 *   3. SCREAM / PANIC      — microphone amplitude ≥ 18 000 (out of 32 767)
 *      for 3 consecutive 400 ms polling windows (~1.2 s of sustained sound)
 *
 *   4. SHAKE TO SOS        — rapid repeated shaking detected via accelerometer.
 *      Fires when SHAKE_COUNT_REQUIRED (default 8) shake peaks exceed
 *      SHAKE_THRESHOLD_MS2 within SHAKE_WINDOW_MS (default 3 000 ms).
 *      Each peak must be separated by at least SHAKE_MIN_GAP_MS (300 ms)
 *      to avoid counting a single jolt multiple times.
 *
 * LOCATION ACCURACY FIX
 * ─────────────────────────────────────────────────────────────────────────────
 * The service maintains a continuous HIGH_ACCURACY location subscription
 * (every 10 s, fastest 5 s). The freshest fix is cached in `cachedLocation`.
 * When SOS fires, SosDispatcher is given this warm cache directly — no waiting
 * for a cold GPS fix, and no 0,0 coordinate bugs from stale last-known location.
 *
 * When ANY signal fires:
 *   • A high-priority "SOS in N s — tap CANCEL if safe" notification appears
 *     immediately (vibration + red LED + ongoing, cannot be dismissed by swipe).
 *   • If not cancelled within the configured window, SosDispatcher sends the SOS
 *     (SMS to contacts + Pushy broadcast to volunteers + Firestore alert).
 *   • A 60-second cooldown prevents duplicate bursts from the same event.
 *
 * Restarted by Android if killed (START_STICKY) and on device boot via
 * BootReceiver.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Required AndroidManifest.xml additions
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   <!-- Permissions -->
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 *   <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 *
 *   <!-- Service declaration inside <application> -->
 *   <service
 *       android:name=".EmergencyDetectionService"
 *       android:foregroundServiceType="dataSync"
 *       android:exported="false"
 *       android:stopWithTask="false" />
 *
 *   <!-- Boot receiver inside <application> -->
 *   <receiver
 *       android:name=".BootReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *           <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *       </intent-filter>
 *   </receiver>
 */
public class EmergencyDetectionService extends Service implements SensorEventListener {

    private static final String TAG = "EmergencyDetect";

    // ── Intent actions ────────────────────────────────────────────────────────
    public static final String ACTION_CANCEL_SOS = "com.rescueconnect.CANCEL_SOS";

    // ── Notification channels & IDs ───────────────────────────────────────────
    private static final String CH_FOREGROUND = "emergency_detect_fg_channel";
    private static final String CH_CANCEL     = "sos_cancel_countdown_channel";
    private static final int    NOTIF_FG      = 9001;
    private static final int    NOTIF_CANCEL  = 9002;

    // ── Accelerometer thresholds ──────────────────────────────────────────────
    /** Below this magnitude (m/s²) for FREE_FALL_MIN_MS → free-fall state */
    private static final float FREE_FALL_THRESHOLD_MS2 = 2.5f;
    /** Impact spike (m/s²) that confirms a drop after free-fall */
    private static final float IMPACT_THRESHOLD_MS2    = 22.0f;
    /** Single-sample spike (m/s²) → crash / violent collision */
    private static final float CRASH_THRESHOLD_MS2     = 30.0f;
    /** Delta between consecutive magnitudes (m/s²) → rapid jerk */
    private static final float JERK_THRESHOLD_MS2      = 35.0f;
    /** Minimum free-fall duration in ms before we look for the landing impact */
    private static final long  FREE_FALL_MIN_MS        = 200L;

    // ── Audio thresholds ──────────────────────────────────────────────────────
    /** MediaRecorder.getMaxAmplitude() value that indicates a scream (0–32767) */
    private static final int  SCREAM_AMPLITUDE_THRESHOLD = 18_000;
    /** Consecutive polling hits that must exceed threshold to fire SOS */
    private static final int  SCREAM_HITS_REQUIRED       = 3;
    /** Polling interval for the audio amplitude check (ms) */
    private static final long AUDIO_POLL_INTERVAL_MS     = 400L;

    // ── Shake-to-SOS ─────────────────────────────────────────────────────────
    /** Accelerometer magnitude (m/s²) that counts as one shake peak.
     *  Kept well below CRASH_THRESHOLD_MS2 (30) so shaking never accidentally
     *  triggers the crash detector instead. */
    private static final float SHAKE_THRESHOLD_MS2  = 15.0f;
    /** Number of qualifying shake peaks required within the window to fire SOS */
    private static final int   SHAKE_COUNT_REQUIRED = 6;
    /** Rolling time window (ms) within which peaks must be counted */
    private static final long  SHAKE_WINDOW_MS      = 4_000L;
    /** Minimum gap (ms) between two counted peaks — prevents one jolt = many counts */
    private static final long  SHAKE_MIN_GAP_MS     =250L;

    // ── Location subscription ─────────────────────────────────────────────────
    /** Interval for continuous HIGH_ACCURACY location updates (ms) */
    private static final long LOCATION_INTERVAL_MS         = 10_000L;
    /** Fastest interval — accepts passive fixes from other apps for free (ms) */
    private static final long LOCATION_FASTEST_INTERVAL_MS = 5_000L;
    /** Cached location older than this is considered stale at SOS dispatch (ms) */
    private static final long LOCATION_MAX_AGE_MS          = 60_000L;

    // ── SOS timing ───────────────────────────────────────────────────────────
    /** Suppress further triggers for this long after SOS fires (ms) */
    private static final long SOS_COOLDOWN_MS = 60_000L;

    // ── Runtime — sensors & audio ─────────────────────────────────────────────
    private SensorManager sensorManager;
    private Sensor        accelerometer;
    private MediaRecorder mediaRecorder;
    private Handler       mainHandler;
    private Runnable      audioPollRunnable;

    // Accelerometer state machine
    private boolean inFreeFall      = false;
    private long    freeFallStartMs = 0L;
    private float   prevMagnitude   = SensorManager.GRAVITY_EARTH;

    // Audio hold counter
    private int screamHitCount = 0;

    // ── Runtime — location ────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocation;
    private LocationCallback            locationCallback;
    /** Freshest known location, continuously refreshed by the subscription */
    private volatile Location cachedLocation = null;

    // ── Runtime — SOS lifecycle ───────────────────────────────────────────────
    private long                lastSosTriggerMs = 0L;
    private final AtomicBoolean pendingSos       = new AtomicBoolean(false);

    // ── Runtime — shake-to-SOS ───────────────────────────────────────────────
    /** Timestamps of each qualifying shake peak within the rolling window */
    private final java.util.ArrayDeque<Long> shakePeakTimes = new java.util.ArrayDeque<>();
    /** Timestamp of the last counted shake peak — enforces SHAKE_MIN_GAP_MS */
    private long lastShakePeakMs = 0L;
    /** True while ≥2 shake peaks are queued — suppresses crash/jerk SOS so a
     *  shake gesture is not misclassified as a crash before it completes. */
    private boolean shakingActive = false;

    // ──────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler   = new Handler(Looper.getMainLooper());
        fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null)
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle CANCEL action fired from the countdown notification button
        if (intent != null && ACTION_CANCEL_SOS.equals(intent.getAction())) {
            cancelPendingSos();
            return START_STICKY;
        }

        // Elevate to foreground immediately — prevents Android from killing us
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_FG, buildForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_FG, buildForegroundNotification());
        }

        registerAccelerometer();
        startAudioMonitoring();
        startLocationUpdates();

        Log.d(TAG, "✅ EmergencyDetectionService started"
                + " — accelerometer + audio + location + shake-to-SOS active");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopAudioMonitoring();
        stopLocationUpdates();
        mainHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "EmergencyDetectionService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ──────────────────────────────────────────────────────────────────────────

    private Notification buildForegroundNotification() {
        createChannel(CH_FOREGROUND, "Emergency Monitor",
                NotificationManager.IMPORTANCE_LOW,
                "RescueConnect background safety monitoring", false);

        Intent openApp = new Intent(this, HomeActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH_FOREGROUND)
                .setSmallIcon(R.mipmap.ic_emergency)
                .setContentTitle("🛡️ RescueConnect — Protection Active")
                .setContentText("Monitoring • Shake phone rapidly for SOS")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openPending)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SIGNAL 1 — Accelerometer: free-fall / crash / jerk
    // ──────────────────────────────────────────────────────────────────────────

    private void registerAccelerometer() {
        if (accelerometer != null) {
            // SENSOR_DELAY_GAME ≈ 20 ms — good balance of accuracy vs battery
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "Accelerometer registered");
        } else {
            Log.w(TAG, "No accelerometer — drop/crash detection unavailable");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x         = event.values[0];
        float y         = event.values[1];
        float z         = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        long  now       = System.currentTimeMillis();
        float jerk      = Math.abs(magnitude - prevMagnitude);

        // Free-fall: sensor reads ≈ 0 because it is also falling (no relative force)
        if (magnitude < FREE_FALL_THRESHOLD_MS2) {
            if (!inFreeFall) {
                inFreeFall      = true;
                freeFallStartMs = now;
                Log.v(TAG, "Free-fall started magnitude=" + magnitude);
            }
        } else {
            if (inFreeFall) {
                long duration = now - freeFallStartMs;
                inFreeFall = false;
                Log.v(TAG, "Free-fall ended duration=" + duration + "ms landing=" + magnitude);
                if (duration >= FREE_FALL_MIN_MS && magnitude > IMPACT_THRESHOLD_MS2)
                    triggerAutoSos("📱 Phone Drop Detected", "drop");
            }
        }

        // Single violent spike → crash / hard fall
        // Suppressed while a shake gesture is in progress (shakingActive=true)
        // so repeated shakes are not misread as a crash before the count completes.
        if (!shakingActive && magnitude > CRASH_THRESHOLD_MS2) {
            Log.v(TAG, "Crash spike magnitude=" + magnitude);
            triggerAutoSos("💥 Sudden Crash / Collision Detected", "crash");
        }

        // High rate-of-change → rapid movement then sudden stop
        if (!shakingActive && jerk > JERK_THRESHOLD_MS2) {
            Log.v(TAG, "High jerk=" + jerk + " prev=" + prevMagnitude + " curr=" + magnitude);
            triggerAutoSos("⚡ Rapid Movement / Sudden Stop Detected", "rapid_stop");
        }

        // Shake-to-SOS: uses the same magnitude, separate peak-counting logic
        checkShake(magnitude);

        prevMagnitude = magnitude;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* unused */ }

    // ──────────────────────────────────────────────────────────────────────────
    // SIGNAL 2 — Audio: scream / panic detection
    // ──────────────────────────────────────────────────────────────────────────

    private void startAudioMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — audio monitoring skipped");
            return;
        }

        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            // Temp file — we only poll amplitude, content is never read or stored
            String tmpPath = getFilesDir().getAbsolutePath() + "/rc_audio_monitor.3gp";
            mediaRecorder.setOutputFile(tmpPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            Log.d(TAG, "Audio monitoring started");
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "MediaRecorder setup failed: " + e.getMessage());
            releaseMediaRecorder();
            return;
        }

        audioPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaRecorder == null) return;
                try {
                    int amplitude = mediaRecorder.getMaxAmplitude();
                    if (amplitude > SCREAM_AMPLITUDE_THRESHOLD) {
                        screamHitCount++;
                        Log.v(TAG, "High amplitude=" + amplitude
                                + " hit=" + screamHitCount + "/" + SCREAM_HITS_REQUIRED);
                        if (screamHitCount >= SCREAM_HITS_REQUIRED) {
                            screamHitCount = 0;
                            triggerAutoSos("🔊 Scream / Panic Sound Detected", "scream");
                        }
                    } else {
                        // Decay rather than hard reset — avoids single-poll spikes
                        if (screamHitCount > 0) screamHitCount--;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Audio poll error: " + e.getMessage());
                }
                mainHandler.postDelayed(this, AUDIO_POLL_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(audioPollRunnable, AUDIO_POLL_INTERVAL_MS);
    }

    private void stopAudioMonitoring() {
        if (audioPollRunnable != null) {
            mainHandler.removeCallbacks(audioPollRunnable);
            audioPollRunnable = null;
        }
        releaseMediaRecorder();
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop();    } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SIGNAL 3 — Location: continuous HIGH_ACCURACY warm cache
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Subscribes to HIGH_ACCURACY location updates every 10 s.
     *
     * A foreground service holds a CPU/GPS wake-lock, which allows the GPS driver
     * to deliver fixes reliably even indoors. The freshest fix is stored in
     * `cachedLocation` and used directly at SOS dispatch time — no cold-start
     * latency and no stale 0,0 coordinates reaching Firestore or the SMS.
     */
    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        boolean hasFine   = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "No location permission — warm cache disabled");
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                .setWaitForAccurateLocation(false) // return best available immediately
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc != null
                        && (loc.getLatitude() != 0.0 || loc.getLongitude() != 0.0)) {
                    cachedLocation = loc;
                    Log.v(TAG, "📍 Cache updated: "
                            + loc.getLatitude() + "," + loc.getLongitude()
                            + " acc=" + loc.getAccuracy() + "m");
                }
            }
        };

        fusedLocation.requestLocationUpdates(request, locationCallback,
                Looper.getMainLooper());
        Log.d(TAG, "Location updates started — HIGH_ACCURACY every "
                + (LOCATION_INTERVAL_MS / 1000) + "s");
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) {
            fusedLocation.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Location updates stopped");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SIGNAL 4 — Shake-to-SOS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called from onSensorChanged with the current accelerometer magnitude.
     *
     * Logic:
     *   • A "shake peak" is any sample whose magnitude exceeds SHAKE_THRESHOLD_MS2.
     *   • Peaks closer together than SHAKE_MIN_GAP_MS are ignored (debounce).
     *   • Qualifying peaks are time-stamped and pushed into shakePeakTimes.
     *   • Entries older than SHAKE_WINDOW_MS are pruned on every call.
     *   • When shakePeakTimes.size() >= SHAKE_COUNT_REQUIRED → SOS fires.
     *
     * This runs on the sensor thread (called from onSensorChanged), which is
     * fine because triggerAutoSos is thread-safe and the deque access is
     * confined to this single thread.
     */
    private void checkShake(float magnitude) {
        long now = System.currentTimeMillis();

        // Prune stale peaks first so shakingActive reflects the current window
        while (!shakePeakTimes.isEmpty()
                && now - shakePeakTimes.peekFirst() > SHAKE_WINDOW_MS) {
            shakePeakTimes.pollFirst();
        }

        // Update shakingActive: suppress crash/jerk once we see ≥2 peaks building up
        shakingActive = shakePeakTimes.size() >= 2;

        if (magnitude < SHAKE_THRESHOLD_MS2) return;

        // Debounce: ignore if too soon after the last counted peak
        if (now - lastShakePeakMs < SHAKE_MIN_GAP_MS) return;

        lastShakePeakMs = now;
        shakePeakTimes.addLast(now);

        Log.v(TAG, "Shake peak detected magnitude=" + magnitude
                + " count=" + shakePeakTimes.size() + "/" + SHAKE_COUNT_REQUIRED);

        // Re-check after adding the new peak
        shakingActive = shakePeakTimes.size() >= 2;

        if (shakePeakTimes.size() >= SHAKE_COUNT_REQUIRED) {
            shakePeakTimes.clear();
            shakingActive = false; // gesture complete — re-enable crash/jerk
            Log.w(TAG, "📳 Shake-to-SOS triggered — " + SHAKE_COUNT_REQUIRED
                    + " peaks in " + SHAKE_WINDOW_MS + "ms");
            triggerAutoSos("📳 Rapid Shake Detected — SOS Triggered", "shake");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOS trigger — cooldown guard + cancel window
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for all four detection signals. Enforces:
     *   • SOS_COOLDOWN_MS cooldown between consecutive triggers
     *   • User-configurable cancel window before SOS is actually sent
     *
     * @param reason Human-readable description shown in the countdown notification
     * @param type   Machine-readable type passed to SosDispatcher for SMS label:
     *               "drop" | "crash" | "rapid_stop" | "scream" | "volume_hold"
     */
    private void triggerAutoSos(String reason, String type) {
        long now = System.currentTimeMillis();

        // Cooldown guard — prevents duplicate bursts from the same physical event
        if (now - lastSosTriggerMs < SOS_COOLDOWN_MS) {
            Log.d(TAG, "SOS cooldown active — ignoring: " + reason);
            return;
        }
        // Prevent double-trigger if a cancel window is already counting down
        if (!pendingSos.compareAndSet(false, true)) {
            Log.d(TAG, "SOS already pending — ignoring: " + reason);
            return;
        }

        final long   cancelWindowMs = MonitorSettingsHelper.getSosCancelWindow(this);
        final String cancelLabel    = MonitorSettingsHelper.labelForValue(cancelWindowMs);

        lastSosTriggerMs = now;
        Log.w(TAG, "⚠️  Emergency detected: " + reason
                + " type=" + type + " cancelWindow=" + cancelWindowMs + "ms");

        showCancelCountdownNotification(reason, cancelLabel);

        // After cancelWindowMs, if user hasn't tapped CANCEL → dispatch SOS
        mainHandler.postDelayed(() -> {
            if (pendingSos.get()) {
                pendingSos.set(false);
                Log.w(TAG, "🆘 Cancel window expired — dispatching SOS: " + reason);
                dispatchSos(reason, type);
            }
        }, cancelWindowMs);
    }

    private void cancelPendingSos() {
        if (pendingSos.compareAndSet(true, false)) {
            Log.d(TAG, "✅ SOS cancelled by user — false positive dismissed");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_CANCEL);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cancel-countdown notification
    // ──────────────────────────────────────────────────────────────────────────

    private void showCancelCountdownNotification(String reason, String cancelLabel) {
        createChannel(CH_CANCEL, "SOS Countdown",
                NotificationManager.IMPORTANCE_HIGH,
                "Shown when auto-SOS is counting down", true);

        // Tapping CANCEL fires ACTION_CANCEL_SOS back to this service
        Intent cancelIntent = new Intent(this, EmergencyDetectionService.class);
        cancelIntent.setAction(ACTION_CANCEL_SOS);
        PendingIntent cancelPending = PendingIntent.getService(
                this, 10, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tapping the notification body opens the app
        Intent openAppIntent = new Intent(this, HomeActivity.class);
        openAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openAppPending = PendingIntent.getActivity(
                this, 11, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = reason
                + "\n\nSOS will be sent automatically in " + cancelLabel + ".\n"
                + "Tap  ✋ CANCEL  if you are safe.";

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CH_CANCEL)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("🚨 SOS Sending in " + cancelLabel + "!")
                        .setContentText(reason + " — Tap CANCEL if you're safe.")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.RED)
                        .setColorized(true)
                        .setOngoing(true)      // Cannot be swiped away
                        .setAutoCancel(false)
                        .setVibrate(new long[]{0, 400, 200, 400, 200, 400})
                        .setLights(Color.RED, 300, 300)
                        .setContentIntent(openAppPending)
                        .addAction(0, "✋  CANCEL — I'm Safe", cancelPending);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_CANCEL, builder.build());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOS dispatch — warm cached location first, SosDispatcher fallback
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Dispatches the SOS using the best available location.
     *
     * Priority:
     *   1. cachedLocation — continuously refreshed by HIGH_ACCURACY subscription.
     *      Used when age ≤ LOCATION_MAX_AGE_MS and coordinates are non-zero.
     *      Normal path: instant dispatch with an accurate GPS position.
     *   2. SosDispatcher.fetchLocationAndSendSos() — used when cache is absent
     *      or stale (e.g. service just started, GPS off for > 60 s). It runs its
     *      own two-step strategy: getCurrentLocation(HIGH_ACCURACY) →
     *      getLastLocation() → no-location SMS fallback.
     */
    private void dispatchSos(String reason, String type) {
        // Dismiss the countdown notification
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_CANCEL);

        Location loc = cachedLocation;
        if (loc != null) {
            long ageMs = System.currentTimeMillis() - loc.getTime();
            if (ageMs <= LOCATION_MAX_AGE_MS
                    && (loc.getLatitude() != 0.0 || loc.getLongitude() != 0.0)) {
                Log.w(TAG, "🆘 Dispatching SOS with warm cached location"
                        + " age=" + ageMs + "ms"
                        + " acc=" + loc.getAccuracy() + "m"
                        + " lat=" + loc.getLatitude()
                        + " lon=" + loc.getLongitude());
                SosDispatcher.sendSos(this, reason, type,
                        loc.getLatitude(), loc.getLongitude());
                return;
            }
            Log.w(TAG, "Cached location stale (age=" + ageMs + "ms) — fetching fresh");
        } else {
            Log.w(TAG, "No cached location yet — fetching fresh via SosDispatcher");
        }

        SosDispatcher.fetchLocationAndSendSos(this, reason, type);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification channel helper
    // ──────────────────────────────────────────────────────────────────────────

    private void createChannel(String id, String name, int importance,
                               String description, boolean vibrate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(id) != null) return;

        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(description);
        ch.enableLights(true);
        ch.setLightColor(Color.RED);
        ch.enableVibration(vibrate);
        if (vibrate) ch.setVibrationPattern(new long[]{0, 400, 200, 400});
        nm.createNotificationChannel(ch);
    }
}