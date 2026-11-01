package com.sonali.punaai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puna AI: a WebView UI (assets/www) with native voice (SpeechRecognizer, Edge neural TTS, Android TTS),
 * media (AAC/MP4 via MediaCodec), Downloads saving and sharing.
 */
public class MainActivity extends Activity {
    static final String TAG = "PUNA";
    static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + ASSET_HOST;
    private static final String START_URL = ORIGIN + "/index.html";
    private static final int REQ_MIC_WEB = 201, REQ_MIC_SR = 202, REQ_STORAGE = 203;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private final ExecutorService media = Executors.newSingleThreadExecutor();
    private boolean debuggable;
    private volatile boolean trustedPage;
    private WebView web;

    private SpeechRecognizer recognizer;
    private int srActiveId;
    private Object[] pendingSr;
    private PermissionRequest pendingWebPermission;

    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<Object[]> ttsQueue = new ArrayList<>();
    private final Map<String, File> writes = new HashMap<>();

    static File mediaDir(Context c) {
        File d = new File(c.getCacheDir(), "media");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static String safeName(String n) {
        if (n == null) return "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.contains("..")) n = n.replace("..", "_");
        return n;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#070A14"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#070A14"));
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "PunaNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) this::handleBack);
        }
        initTts();
        web.loadUrl(START_URL);
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("(window.__punaBack&&window.__punaBack())?'1':'0'", r -> {
            if (r != null && r.contains("1")) return;
            finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { handleBack(); }

    @Override
    protected void onPause() {
        super.onPause();
        js("window.__punaLife&&window.__punaLife('pause')");
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) try { recognizer.destroy(); } catch (Exception ignored) {}
        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        net.shutdownNow();
        media.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    private byte[] readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    void cb(String id, JSONObject o) {
        js("window.__punaCb&&window.__punaCb(" + JSONObject.quote(id) + "," + o + ")");
    }

    void cbErr(String id, Throwable e) {
        Log.w(TAG, "task " + id + " failed", e);
        try {
            cb(id, new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e)));
        } catch (Exception ignored) {}
    }

    void toast(final String msg) {
        Log.i(TAG, "toast: " + msg);
        main.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOwn(String url) { return url != null && (url.equals(ORIGIN) || url.startsWith(ORIGIN + "/")); }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Is link ko kholne ke liye koi app nahi mila");
        }
    }

    static String mimeOf(String name) {
        int d = name.lastIndexOf('.');
        String ext = d >= 0 ? name.substring(d + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("m4a")) return "audio/mp4";
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("mp4")) return "video/mp4";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    // ------------------------------------------------------------------ WebView

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isOwn(url) || url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) return false;
            openExternal(url);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!"https".equals(u.getScheme()) || !ASSET_HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "" : u.getPath();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) path = "index.html";
            if (path.contains("..")) return notFound();
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            h.put("Access-Control-Allow-Origin", ORIGIN);
            if (path.startsWith("media/")) return serveMedia(path.substring(6), request, h);
            byte[] data = readAsset("www/" + path);
            if (data == null) return notFound();
            String mime = mimeOf(path);
            if (path.endsWith(".js")) mime = "text/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
            else if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(data));
            r.setResponseHeaders(h);
            return r;
        }

        private WebResourceResponse serveMedia(String name, WebResourceRequest req, Map<String, String> h) {
            name = safeName(Uri.decode(name));
            File f = new File(mediaDir(MainActivity.this), name);
            if (!f.exists()) return notFound();
            String mime = mimeOf(name);
            long len = f.length();
            h.put("Accept-Ranges", "bytes");
            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            }
            try {
                if (range != null && range.startsWith("bytes=")) {
                    String[] p = range.substring(6).split("-", -1);
                    long start = p[0].isEmpty() ? 0 : Long.parseLong(p[0].trim());
                    long end = (p.length > 1 && !p[1].trim().isEmpty()) ? Long.parseLong(p[1].trim()) : len - 1;
                    end = Math.min(end, len - 1);
                    if (start > end) start = 0;
                    int n = (int) (end - start + 1);
                    byte[] buf = new byte[n];
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(start);
                        raf.readFully(buf);
                    }
                    h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
                    h.put("Content-Length", String.valueOf(n));
                    return new WebResourceResponse(mime, null, 206, "Partial Content", h, new ByteArrayInputStream(buf));
                }
                h.put("Content-Length", String.valueOf(len));
                return new WebResourceResponse(mime, null, 200, "OK", h, new FileInputStream(f));
            } catch (Exception e) {
                return notFound();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            trustedPage = isOwn(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            trustedPage = isOwn(url);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            main.post(() -> {
                Uri o = request.getOrigin();
                boolean audio = false;
                for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                if (o == null || !ASSET_HOST.equals(o.getHost()) || !audio) { request.deny(); return; }
                if (hasMic()) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else {
                    pendingWebPermission = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_WEB);
                }
            });
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (debuggable) Log.d(TAG, m.messageLevel() + " " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_WEB && pendingWebPermission != null) {
            if (granted) pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        } else if (requestCode == REQ_MIC_SR && pendingSr != null) {
            Object[] a = pendingSr;
            pendingSr = null;
            int id = (Integer) a[0];
            if (granted) startRecognizer(id, (String) a[1]);
            else { srEvent(id, "error", err("not-allowed", "Mic permission nahi mili")); srEvent(id, "end", null); }
        }
    }

    // ------------------------------------------------------------------ speech recognition

    static String err(String e, String m) {
        try { return new JSONObject().put("error", e).put("message", m).toString(); } catch (Exception x) { return "{}"; }
    }

    void srEvent(int id, String type, String json) {
        js("window.__punaSR&&window.__punaSR(" + id + ",'" + type + "'," + (json == null ? "null" : json) + ")");
    }

    private void srStartMain(int id, String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            srEvent(id, "error", err("service-not-allowed", "Is phone par speech recognition nahi hai (Google app install/update karo)"));
            srEvent(id, "end", null);
            return;
        }
        if (!hasMic()) {
            pendingSr = new Object[]{id, lang};
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_SR);
            return;
        }
        startRecognizer(id, lang);
    }

    private void startRecognizer(final int id, String lang) {
        if (srActiveId != 0 && srActiveId != id) { srEvent(srActiveId, "end", null); }
        if (recognizer != null) { try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) {} recognizer = null; }
        srActiveId = id;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            boolean ended;
            void finish(String e, String m) {
                if (ended) return;
                ended = true;
                if (e != null) srEvent(id, "error", err(e, m));
                srEvent(id, "end", null);
                if (srActiveId == id) srActiveId = 0;
            }
            @Override public void onReadyForSpeech(Bundle b) { srEvent(id, "start", null); }
            @Override public void onBeginningOfSpeech() { srEvent(id, "speechstart", null); }
            @Override public void onRmsChanged(float db) {
                js("window.__punaRms&&window.__punaRms(" + db + ")");
            }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { srEvent(id, "speechend", null); }
            @Override public void onError(int error) {
                String e;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                    case SpeechRecognizer.ERROR_AUDIO: e = "audio-capture"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "not-allowed"; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER: e = "network"; break;
                    case 12: case 13: e = "language-not-supported"; break;
                    default: e = "aborted";
                }
                finish(e, "android error " + error);
            }
            private void emit(Bundle b, boolean fin) {
                if (b == null) return;
                ArrayList<String> t = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (t == null || t.isEmpty()) return;
                try {
                    srEvent(id, "result", new JSONObject().put("final", fin).put("text", t.get(0)).toString());
                } catch (Exception ignored) {}
            }
            @Override public void onResults(Bundle r) {
                ArrayList<String> t = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (t == null || t.isEmpty() || t.get(0).trim().isEmpty()) srEvent(id, "nomatch", null);
                else emit(r, true);
                finish(null, null);
            }
            @Override public void onPartialResults(Bundle p) { emit(p, false); }
            @Override public void onEvent(int t, Bundle p) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (lang != null && !lang.isEmpty()) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        }
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            srEvent(id, "error", err("aborted", String.valueOf(e.getMessage())));
            srEvent(id, "end", null);
            srActiveId = 0;
        }
    }

    // ------------------------------------------------------------------ Android TTS (fallback)

    private void initTts() {
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                for (Object[] q : ttsQueue) ttsEvent((String) q[0], "error", err("unavailable", "Android TTS nahi chala"));
                ttsQueue.clear();
                return;
            }
            ttsReady = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) { String[] p = u.split("\\|"); if ("0".equals(p[1])) ttsEvent(p[0], "start", null); }
                @Override public void onDone(String u) { String[] p = u.split("\\|"); if (p[1].equals(p[2])) ttsEvent(p[0], "end", null); }
                @SuppressWarnings("deprecation") @Override public void onError(String u) { ttsEvent(u.split("\\|")[0], "error", err("failed", "")); }
                @Override public void onStop(String u, boolean interrupted) { ttsEvent(u.split("\\|")[0], "end", null); }
            });
            for (Object[] q : ttsQueue) speakNow(q);
            ttsQueue.clear();
        }));
    }

    void ttsEvent(String id, String type, String json) {
        js("window.__punaTTS&&window.__punaTTS(" + JSONObject.quote(id) + ",'" + type + "'," + (json == null ? "null" : json) + ")");
    }

    private void speakNow(Object[] q) {
        String id = (String) q[0], text = (String) q[1], lang = (String) q[2];
        float rate = (Float) q[3];
        if (tts == null) return;
        if (lang != null && !lang.isEmpty()) {
            int r = tts.setLanguage(Locale.forLanguageTag(lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsEvent(id, "error", err("language-unavailable", lang + " ki Android awaaz install nahi hai"));
                return;
            }
        }
        tts.setSpeechRate(Math.max(0.3f, Math.min(rate, 3f)));
        int max = Math.max(200, TextToSpeech.getMaxSpeechInputLength() - 10);
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < text.length(); k += max) parts.add(text.substring(k, Math.min(text.length(), k + max)));
        if (parts.isEmpty()) parts.add(" ");
        int last = parts.size() - 1;
        for (int k = 0; k <= last; k++) tts.speak(parts.get(k), TextToSpeech.QUEUE_ADD, new Bundle(), id + "|" + k + "|" + last);
    }

    boolean ttsLangOk(String lang) {
        if (tts == null || !ttsReady) return false;
        try {
            int r = tts.isLanguageAvailable(Locale.forLanguageTag(lang));
            if (r < TextToSpeech.LANG_AVAILABLE) return false;
            Set<Voice> vs = tts.getVoices();
            if (vs == null) return true;
            String base = lang.split("-")[0];
            for (Voice v : vs) {
                if (v.getLocale().getLanguage().equals(base)
                        && (v.getFeatures() == null || !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ files

    private Uri saveToDownloads(File src, String display, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, display);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PunaAI");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream out = cr.openOutputStream(uri, "w"); InputStream in = new FileInputStream(src)) {
                copy(in, out);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        File dir;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PunaAI");
        } else {
            main.post(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
            dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, display);
        try (OutputStream out = new FileOutputStream(dest); InputStream in = new FileInputStream(src)) { copy(in, out); }
        return Uri.fromFile(dest);
    }

    static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ JS bridge

    private class Bridge {
        @JavascriptInterface public String info() {
            try {
                String wv = "";
                if (Build.VERSION.SDK_INT >= 26) {
                    android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                    if (p != null) wv = p.versionName;
                }
                String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                return new JSONObject().put("version", ver).put("sdk", Build.VERSION.SDK_INT).put("webview", wv)
                        .put("debug", debuggable).put("model", Build.MODEL).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** WebView's top-left on screen in device px (used by the emulator tests to map taps). */
        @JavascriptInterface public String viewPos() {
            int[] xy = new int[2];
            if (web != null) web.getLocationOnScreen(xy);
            return xy[0] + "," + xy[1];
        }

        @JavascriptInterface public void srStart(int id, String lang) { if (trustedPage) main.post(() -> srStartMain(id, lang)); }
        @JavascriptInterface public void srStop(int id) {
            if (trustedPage) main.post(() -> { if (recognizer != null && srActiveId == id) recognizer.stopListening(); });
        }
        @JavascriptInterface public void srAbort(int id) {
            if (!trustedPage) return;
            main.post(() -> {
                if (recognizer != null && srActiveId == id) {
                    try { recognizer.cancel(); } catch (Exception ignored) {}
                    srActiveId = 0;
                    srEvent(id, "end", null);
                }
            });
        }
        @JavascriptInterface public boolean srAvailable() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }

        @JavascriptInterface public void ttsSpeak(String id, String text, String lang, double rate) {
            if (!trustedPage) return;
            Object[] q = new Object[]{id, text == null ? "" : text, lang, (float) rate};
            main.post(() -> { if (ttsReady) speakNow(q); else ttsQueue.add(q); });
        }
        @JavascriptInterface public void ttsCancel() {
            if (trustedPage) main.post(() -> { ttsQueue.clear(); if (tts != null) tts.stop(); });
        }
        @JavascriptInterface public boolean ttsHasLang(String lang) { return trustedPage && ttsLangOk(lang); }

        /** Edge neural TTS -> cache/media/<name>.mp3; result through __punaCb(id, {ok,url,name,bytes,ms}). */
        @JavascriptInterface public void edgeSynth(final String id, final String text, final String voice, final String rate, final String pitch) {
            if (!trustedPage) return;
            net.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    byte[] mp3 = EdgeTts.synth(text, voice, rate == null || rate.isEmpty() ? "+0%" : rate, pitch == null || pitch.isEmpty() ? "+0Hz" : pitch);
                    String name = "tts-" + safeName(id) + ".mp3";
                    try (FileOutputStream o = new FileOutputStream(new File(mediaDir(MainActivity.this), name))) { o.write(mp3); }
                    cb(id, new JSONObject().put("ok", true).put("name", name).put("url", "/media/" + name)
                            .put("bytes", mp3.length).put("ms", System.currentTimeMillis() - t0));
                } catch (Throwable e) { cbErr(id, e); }
            });
        }

        @JavascriptInterface public String writeBegin(String name) {
            if (!trustedPage) return "";
            name = safeName(name);
            File f = new File(mediaDir(MainActivity.this), name);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            synchronized (writes) { writes.put(name, f); }
            return name;
        }
        @JavascriptInterface public boolean writeChunk(String name, String b64) {
            if (!trustedPage) return false;
            File f;
            synchronized (writes) { f = writes.get(name); }
            if (f == null) return false;
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(Base64.decode(b64, Base64.DEFAULT)); return true; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public String writeEnd(String name) {
            if (!trustedPage) return "";
            File f;
            synchronized (writes) { f = writes.remove(name); }
            return f != null && f.exists() ? "/media/" + f.getName() : "";
        }

        @JavascriptInterface public void makeM4a(final String id, final String wavName, final String outName) {
            if (!trustedPage) return;
            media.execute(() -> {
                try {
                    File out = new File(mediaDir(MainActivity.this), safeName(outName));
                    MediaWorks.wavToM4a(new File(mediaDir(MainActivity.this), safeName(wavName)), out,
                            (pct, st) -> js("window.__punaProg&&window.__punaProg(" + JSONObject.quote(id) + "," + pct + ",'" + st + "')"));
                    cb(id, new JSONObject().put("ok", true).put("name", out.getName()).put("url", "/media/" + out.getName()).put("bytes", out.length()));
                } catch (Throwable e) { cbErr(id, e); }
            });
        }

        @JavascriptInterface public void makeVideo(final String id, final String specJson, final String wavName, final String outName) {
            if (!trustedPage) return;
            main.post(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            media.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    File out = new File(mediaDir(MainActivity.this), safeName(outName));
                    MediaWorks.makeVideo(new JSONObject(specJson), new File(mediaDir(MainActivity.this), safeName(wavName)), out,
                            (pct, st) -> js("window.__punaProg&&window.__punaProg(" + JSONObject.quote(id) + "," + pct + ",'" + st + "')"));
                    cb(id, new JSONObject().put("ok", true).put("name", out.getName()).put("url", "/media/" + out.getName())
                            .put("bytes", out.length()).put("ms", System.currentTimeMillis() - t0));
                } catch (Throwable e) { cbErr(id, e); }
                main.post(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            });
        }

        @JavascriptInterface public void saveToDownloads(final String id, final String name, final String display) {
            if (!trustedPage) return;
            net.execute(() -> {
                try {
                    File src = new File(mediaDir(MainActivity.this), safeName(name));
                    String disp = safeName(display == null || display.isEmpty() ? name : display);
                    Uri u = MainActivity.this.saveToDownloads(src, disp, mimeOf(disp));
                    toast("Download mein save: PunaAI/" + disp);
                    cb(id, new JSONObject().put("ok", true).put("uri", u.toString()).put("name", disp));
                } catch (Throwable e) { cbErr(id, e); }
            });
        }

        /** target: "whatsapp" | "telegram" | "" (chooser) */
        @JavascriptInterface public String share(final String name, final String text, final String target) {
            if (!trustedPage) return "untrusted";
            String n = safeName(name);
            File f = new File(mediaDir(MainActivity.this), n);
            if (!f.exists()) return "missing";
            String pkg = "";
            if ("whatsapp".equals(target)) pkg = installed("com.whatsapp") ? "com.whatsapp" : (installed("com.whatsapp.w4b") ? "com.whatsapp.w4b" : "");
            else if ("telegram".equals(target)) pkg = installed("org.telegram.messenger") ? "org.telegram.messenger" : (installed("org.thunderdog.challegram") ? "org.thunderdog.challegram" : "");
            final String fp = pkg;
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mimeOf(n));
                i.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(MainActivity.this, n));
                if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    if (!fp.isEmpty()) { i.setPackage(fp); startActivity(i); }
                    else startActivity(Intent.createChooser(i, "Share karo"));
                } catch (Exception e) { toast("Share nahi hua: " + e.getMessage()); }
            });
            if (!target.isEmpty() && pkg.isEmpty()) return "app-missing";
            return "ok";
        }

        @JavascriptInterface public void shareText(final String text) {
            if (!trustedPage) return;
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "Share karo")); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public String listMedia() {
            JSONArray a = new JSONArray();
            File[] fs = mediaDir(MainActivity.this).listFiles();
            if (fs != null) for (File f : fs) {
                try { a.put(new JSONObject().put("name", f.getName()).put("bytes", f.length()).put("mtime", f.lastModified())); } catch (Exception ignored) {}
            }
            return a.toString();
        }

        @JavascriptInterface public void deleteMedia(String name) {
            if (!trustedPage) return;
            //noinspection ResultOfMethodCallIgnored
            new File(mediaDir(MainActivity.this), safeName(name)).delete();
        }

        @JavascriptInterface public void openUrl(String url) {
            if (trustedPage && url != null && (url.startsWith("https://") || url.startsWith("http://"))) main.post(() -> openExternal(url));
        }

        @JavascriptInterface public void toast(String msg) { if (trustedPage) MainActivity.this.toast(msg); }

        @JavascriptInterface public void keepAwake(boolean on) {
            if (!trustedPage) return;
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void copy(String text) {
            if (!trustedPage) return;
            main.post(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Puna AI", text));
            });
        }
    }
}
