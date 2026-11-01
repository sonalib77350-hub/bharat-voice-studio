package com.sonali.punaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 24/7 presence: a foreground service with a persistent notification (Talk / Stop),
 * optionally running a hands-free "Hey Puna" loop with the on-device SpeechRecognizer.
 */
public class PunaService extends Service {
    static final String TAG = "PUNA";
    static final String CH_ALWAYS = "puna_always", CH_WAKE = "puna_wake";
    static final String ACTION_STOP = "com.sonali.punaai.STOP";
    static final String ACTION_REFRESH = "com.sonali.punaai.REFRESH";
    static final String ACTION_WAKE_TEST = "com.sonali.punaai.WAKE_TEST";
    static final String ACTION_TALK = "com.sonali.punaai.TALK";
    static final int NOTE_ID = 7, NOTE_WAKE = 8;
    /** "Hey Puna" (and how recognizers often spell it); a bare "Puna" only when said alone, so talk about Pune city does not trigger. */
    static final Pattern WAKE = Pattern.compile(
            "(\\b(hey|hi|hello|he|hay|ok|okay)|ए|हे|है|हाय|ହେ)\\s*,?\\s*(puna|poona|pune|poonah|punah|pooja na|पुना|पूना|पुणे|ପୁନା|ପୁଣା)"
                    + "|^\\s*(puna|पुना|पूना|ପୁନା)\\s*[.!?]?\\s*$", Pattern.CASE_INSENSITIVE);

    static volatile boolean running = false;
    static volatile boolean appVisible = false;
    static volatile String lastHeard = "";
    static volatile int wakeCycles = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private boolean wakeOn = false, listening = false;
    private int errors = 0;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("puna", MODE_PRIVATE); }

    static void sync(Context c) {
        SharedPreferences p = prefs(c);
        Intent i = new Intent(c, PunaService.class).setAction(ACTION_REFRESH);
        if (p.getBoolean("alwaysOn", false) || p.getBoolean("wake", false)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } catch (Exception e) { Log.w(TAG, "service start failed", e); }
        } else if (running) {
            c.stopService(new Intent(c, PunaService.class));
        }
    }

    static boolean overlayOk(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel a = new NotificationChannel(CH_ALWAYS, "Puna 24/7", NotificationManager.IMPORTANCE_LOW);
            a.setDescription("Puna hamesha taiyaar — Talk / Stop buttons");
            a.setShowBadge(false);
            nm.createNotificationChannel(a);
            NotificationChannel w = new NotificationChannel(CH_WAKE, "Hey Puna", NotificationManager.IMPORTANCE_HIGH);
            w.setDescription("Jab Puna aapki awaaz sunti hai");
            nm.createNotificationChannel(w);
        }
    }

    private PendingIntent talkIntent() {
        Intent t = new Intent(this, MainActivity.class).setAction(ACTION_TALK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, t, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ALWAYS) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PunaService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String text = wakeOn ? (appVisible ? "App khula hai — Hey Puna ruka hua" : "“Hey Puna” bolo, ya Talk dabao") : "Talk dabao aur baat karo";
        b.setSmallIcon(R.drawable.ic_stat_puna)
                .setContentTitle("Puna AI " + (wakeOn ? "sun rahi hai 👂" : "taiyaar hai"))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "🎙️ Talk", talkIntent()).build())
                .addAction(new Notification.Action.Builder(null, "⏹ Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void goForeground() {
        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (wakeOn) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        } else if (Build.VERSION.SDK_INT >= 30 && wakeOn) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, build(), type);
            else startForeground(NOTE_ID, build());
        } catch (Exception e) {
            // Android 14+: microphone FGS cannot start from the background (e.g. after boot) — run without the mic loop.
            Log.w(TAG, "startForeground with mic failed, falling back: " + e);
            wakeOn = false;
            prefs(this).edit().putBoolean("wakePending", true).apply();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTE_ID, build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        SharedPreferences p = prefs(this);
        if (ACTION_STOP.equals(a)) {
            p.edit().putBoolean("alwaysOn", false).putBoolean("wake", false).apply();
            stopWake();
            stopForeground(true);
            stopSelf();
            running = false;
            return START_NOT_STICKY;
        }
        if (ACTION_WAKE_TEST.equals(a) && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String t = intent.getStringExtra("text");
            onHeard(t == null ? "hey puna" : t);
            return START_STICKY;
        }
        boolean always = p.getBoolean("alwaysOn", false), wantWake = p.getBoolean("wake", false);
        if (!always && !wantWake) { stopSelf(); running = false; return START_NOT_STICKY; }
        wakeOn = wantWake && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        goForeground();
        running = true;
        if (wakeOn) { p.edit().putBoolean("wakePending", false).apply(); scheduleListen(300); } else stopWake();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWake();
        running = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ wake loop

    private void stopWake() {
        main.removeCallbacksAndMessages(null);
        if (rec != null) { try { rec.cancel(); rec.destroy(); } catch (Exception ignored) {} rec = null; }
        listening = false;
    }

    private void scheduleListen(long ms) {
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::listen, ms);
    }

    private void listen() {
        if (!wakeOn) return;
        if (appVisible) { // the app itself uses the mic while open
            if (rec != null) { try { rec.cancel(); } catch (Exception ignored) {} }
            listening = false;
            scheduleListen(2000);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Log.w(TAG, "wake: no recognizer"); scheduleListen(60000); return; }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { listening = true; }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int e) {
                    listening = false;
                    // no-match / timeout are normal (silence): restart quickly; other errors back off.
                    boolean quiet = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    errors = quiet ? 0 : errors + 1;
                    if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                        try { rec.destroy(); } catch (Exception ignored) {}
                        rec = null;
                    }
                    scheduleListen(quiet ? 250 : Math.min(30000, 1500L * errors));
                }
                @Override public void onResults(Bundle r) { listening = false; handle(r); scheduleListen(250); }
                @Override public void onPartialResults(Bundle r) { handle(r); }
                @Override public void onEvent(int t, Bundle b) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs(this).getString("srLang", "hi-IN"));
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            wakeCycles++;
            rec.startListening(i);
        } catch (Exception e) {
            scheduleListen(5000);
        }
    }

    private void handle(Bundle b) {
        if (b == null) return;
        ArrayList<String> t = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (t == null) return;
        for (String s : t) {
            if (s != null && WAKE.matcher(s.toLowerCase(Locale.ROOT)).find()) { onHeard(s); return; }
        }
    }

    private void onHeard(String text) {
        lastHeard = text;
        Log.i(TAG, "wake phrase heard: " + text);
        if (rec != null) { try { rec.cancel(); } catch (Exception ignored) {} }
        listening = false;
        Intent t = new Intent(this, MainActivity.class).setAction(ACTION_TALK).putExtra("heard", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean started = false;
        if (overlayOk(this)) { // "Display over other apps" lets a service open the app from the background
            try { startActivity(t); started = true; } catch (Exception e) { Log.w(TAG, "start activity failed", e); }
        }
        // Always also post a heads-up notification (full-screen intent where allowed) as the fallback path.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_WAKE) : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_stat_puna).setContentTitle("Haan, bolo! 👂").setContentText("Tap karke Puna se baat karo")
                    .setContentIntent(talkIntent()).setAutoCancel(true).setCategory(Notification.CATEGORY_CALL)
                    .setPriority(Notification.PRIORITY_HIGH).setTimeoutAfter(20000);
            if (!started) b.setFullScreenIntent(talkIntent(), true);
            try { nm.notify(NOTE_WAKE, b.build()); } catch (Exception ignored) {}
        }
        scheduleListen(15000); // resume listening after the conversation window
    }
}
