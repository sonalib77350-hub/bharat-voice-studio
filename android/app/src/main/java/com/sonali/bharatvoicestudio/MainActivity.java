package com.sonali.bharatvoicestudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
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
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "BVS";
    // The bundled web app is served from a virtual https origin (same idea as androidx WebViewAssetLoader):
    // it is a secure context like the GitHub Pages site, so CORS, mic and clipboard behave the same, and
    // file:// access can stay switched off.
    static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + ASSET_HOST;
    private static final String START_URL = ORIGIN + "/index.html";
    private static final int REQ_FILE = 101;
    private static final int REQ_MIC_WEB = 201;
    private static final int REQ_MIC_SR = 202;
    private static final int REQ_STORAGE = 203;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean debuggable = false;
    /** True only while the main frame shows our own bundled page; the JS bridge ignores calls otherwise. */
    private volatile boolean trustedPage = false;
    private WebView web;
    private String shimJs = "";

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;

    // Speech recognition
    private SpeechRecognizer recognizer;
    private int srActiveId = 0;
    private Object[] pendingSrStart;

    // Text to speech
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final List<Object[]> ttsQueue = new ArrayList<>();

    // Downloads in progress (id -> temp file / meta)
    private final Map<String, File> dlFiles = new HashMap<>();
    private final Map<String, String[]> dlMeta = new HashMap<>();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shimJs = readAsset("bridge.js");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // targetSdk 35+ draws edge-to-edge: pad the content by the system bars and keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(in.left, in.top, in.right, in.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true); // debug builds only (used by the emulator tests)
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Locked down: no file:// or content:// access, no http inside https, no geolocation, no form-data saving.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                web.evaluateJavascript("window.__bvsSaveUrl&&window.__bvsSaveUrl(" + JSONObject.quote(url) + ","
                        + JSONObject.quote(name) + ")", null);
            } else {
                openExternal(url);
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) this::handleBack);
        }

        initTts();
        warnOldWebView();

        // Always load fresh. (Restoring WebView state after the activity was recreated left a blank,
        // black screen: the restored history pointed at the intercepted page without its content.)
        web.loadUrl(START_URL);
    }

    /** Very old preinstalled WebViews (e.g. v83 on Android 11 images) cannot run the Whisper tools. */
    private void warnOldWebView() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p == null || p.versionName == null) return;
            int major = Integer.parseInt(p.versionName.split("\\.")[0]);
            Log.i(TAG, "WebView " + p.packageName + " " + p.versionName);
            if (major < 100) {
                toast("Android System WebView purana hai (v" + major + "). Play Store se 'Android System WebView' update karo, warna Whisper file/subtitle tools nahi chalenge.");
            }
        } catch (Exception ignored) {}
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
        // Let the page go back first (auth -> landing, a tool tab -> Home, dashboard -> landing).
        web.evaluateJavascript("(window.__bvsBack&&window.__bvsBack())?'1':'0'", r -> {
            if (r != null && r.contains("1")) return;
            if (web != null && web.canGoBack()) web.goBack();
            else finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} recognizer = null; }
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} tts = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "asset " + name, e);
            return "";
        }
    }

    private void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void toast(final String msg) {
        Log.i(TAG, "toast: " + msg);
        main.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOwn(String url) {
        return url != null && (url.equals(ORIGIN) || url.startsWith(ORIGIN + "/"));
    }

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

    // ------------------------------------------------------------------ WebView clients

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        private boolean handleUrl(String url) {
            if (isOwn(url) || url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) {
                return false;
            }
            openExternal(url); // http(s), mailto:, intent:, target=_blank links -> system browser/app
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Serve the bundled index.html with the native shim injected before any page script runs.
            Uri u = request.getUrl();
            if (!"https".equals(u.getScheme()) || !ASSET_HOST.equals(u.getHost())) {
                return super.shouldInterceptRequest(view, request);
            }
            String path = u.getPath() == null ? "" : u.getPath();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) path = "index.html";
            if (path.contains("..")) return notFound();
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            h.put("Access-Control-Allow-Origin", ORIGIN);
            if (path.equals("index.html")) {
                String html = readAsset("index.html");
                if (html.isEmpty()) return notFound();
                String tag = "<script>" + shimJs + "</script>";
                Matcher m = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
                html = m.find() ? html.substring(0, m.end()) + tag + html.substring(m.end()) : tag + html;
                WebResourceResponse r = new WebResourceResponse("text/html", "UTF-8",
                        new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                r.setResponseHeaders(h);
                return r;
            }
            try {
                InputStream in = getAssets().open(path);
                String ext = path.lastIndexOf('.') >= 0 ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                String mime = ext.equals("js") ? "text/javascript" : ext.equals("css") ? "text/css"
                        : ext.equals("json") ? "application/json" : ext.equals("wasm") ? "application/wasm"
                        : ext.equals("svg") ? "image/svg+xml" : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                WebResourceResponse r = new WebResourceResponse(mime == null ? "application/octet-stream" : mime,
                        "UTF-8", in);
                r.setResponseHeaders(h);
                return r;
            } catch (Exception e) {
                Log.w(TAG, "missing asset " + path);
                return notFound();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(),
                    new ByteArrayInputStream(new byte[0]));
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
            // Fallback in case interception did not happen (guarded inside the shim).
            if (!shimJs.isEmpty()) view.evaluateJavascript(shimJs, null);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            main.post(() -> {
                Uri o = request.getOrigin();
                if (o == null || !ASSET_HOST.equals(o.getHost())) { request.deny(); return; }
                boolean wantsAudio = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
                }
                if (!wantsAudio) { request.deny(); return; }
                if (hasMic()) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingWebPermission != null) pendingWebPermission.deny();
                    pendingWebPermission = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_WEB);
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            Log.i(TAG, "onShowFileChooser accept=" + java.util.Arrays.toString(params.getAcceptTypes()) + " mode=" + params.getMode());
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Set<String> mimes = new LinkedHashSet<>();
            boolean any = false;
            String[] accept = params.getAcceptTypes();
            if (accept == null) accept = new String[0];
            for (String a : accept) {
                if (a == null) continue;
                for (String t : a.split(",")) {
                    t = t.trim().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) continue;
                    if (t.startsWith(".")) {
                        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1));
                        if (m != null) mimes.add(m);
                    } else if (t.equals("*/*")) {
                        any = true;
                    } else {
                        mimes.add(t);
                    }
                }
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (any || mimes.isEmpty()) {
                i.setType("*/*");
            } else if (mimes.size() == 1) {
                i.setType(mimes.iterator().next());
            } else {
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            }
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(Intent.createChooser(i, "File chuno"), REQ_FILE);
                Log.i(TAG, "file chooser started type=" + i.getType() + " mimes=" + mimes);
            } catch (Exception e) {
                // Never leave the page waiting: hand WebView an empty result so the next tap works again.
                Log.w(TAG, "file chooser failed: " + e);
                ValueCallback<Uri[]> cb = fileCallback;
                fileCallback = null;
                if (cb != null) cb.onReceiveValue(null);
                toast("File picker nahi mila");
            }
            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            // Page console goes to logcat only in debug builds (release logs nothing the page prints).
            if (debuggable) Log.d(TAG, m.messageLevel() + " " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Log.i(TAG, "file chooser result=" + resultCode);
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_WEB && pendingWebPermission != null) {
            if (granted) pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        } else if (requestCode == REQ_MIC_SR && pendingSrStart != null) {
            Object[] a = pendingSrStart;
            pendingSrStart = null;
            int id = (Integer) a[0];
            if (granted) {
                startRecognizer(id, (String) a[1], (Boolean) a[2], (Integer) a[3]);
            } else {
                srEvent(id, "error", errJson("not-allowed", "Microphone permission denied"));
                srEvent(id, "end", null);
            }
        }
    }

    // ------------------------------------------------------------------ speech recognition

    private void srEvent(int id, String type, String json) {
        js("window.__bvsSR&&window.__bvsSR(" + id + ",'" + type + "'," + (json == null ? "null" : json) + ")");
    }

    private static String errJson(String error, String message) {
        try {
            return new JSONObject().put("error", error).put("message", message).toString();
        } catch (Exception e) {
            return "{\"error\":\"" + error + "\"}";
        }
    }

    private void srStartOnMain(int id, String lang, boolean interim, int maxAlt) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            srEvent(id, "error", errJson("service-not-allowed",
                    "Is phone par speech recognition service nahi hai (Google app install/update karo)"));
            srEvent(id, "end", null);
            return;
        }
        if (!hasMic()) {
            if (pendingSrStart != null) {
                int old = (Integer) pendingSrStart[0];
                srEvent(old, "error", errJson("aborted", ""));
                srEvent(old, "end", null);
            }
            pendingSrStart = new Object[]{id, lang, interim, maxAlt};
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_SR);
            return;
        }
        startRecognizer(id, lang, interim, maxAlt);
    }

    private void startRecognizer(final int id, String lang, boolean interim, int maxAlt) {
        if (srActiveId != 0 && srActiveId != id) {
            int old = srActiveId;
            srEvent(old, "error", errJson("aborted", ""));
            srEvent(old, "end", null);
        }
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        srActiveId = id;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            boolean ended = false;

            private void finish(String error, String message) {
                if (ended) return;
                ended = true;
                if (error != null) srEvent(id, "error", errJson(error, message));
                srEvent(id, "end", null);
                if (srActiveId == id) srActiveId = 0;
            }

            @Override public void onReadyForSpeech(Bundle params) { srEvent(id, "start", null); }
            @Override public void onBeginningOfSpeech() { srEvent(id, "speechstart", null); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { srEvent(id, "speechend", null); }

            @Override
            public void onError(int error) {
                String e;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                    case SpeechRecognizer.ERROR_AUDIO: e = "audio-capture"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "not-allowed"; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER: e = "network"; break;
                    case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                    case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: e = "language-not-supported"; break;
                    case SpeechRecognizer.ERROR_CLIENT: e = "aborted"; break;
                    default: e = "aborted";
                }
                finish(e, "android error " + error);
            }

            private void emit(Bundle b, boolean isFinal) {
                if (b == null) return;
                ArrayList<String> texts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                if (texts == null || texts.isEmpty()) return;
                if (!isFinal && texts.get(0).trim().isEmpty()) return;
                try {
                    JSONArray alts = new JSONArray();
                    for (int k = 0; k < texts.size(); k++) {
                        double c = (conf != null && k < conf.length && conf[k] >= 0) ? conf[k] : (isFinal ? 0.9 : 0.5);
                        alts.put(new JSONObject().put("t", texts.get(k)).put("c", c));
                    }
                    srEvent(id, "result", new JSONObject().put("final", isFinal).put("alts", alts).toString());
                } catch (Exception ignored) {}
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> texts = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty() || texts.get(0).trim().isEmpty()) {
                    srEvent(id, "nomatch", null);
                } else {
                    emit(results, true);
                }
                finish(null, null);
            }

            @Override public void onPartialResults(Bundle partialResults) { emit(partialResults, false); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (lang != null && !lang.isEmpty()) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        }
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); // filtered in JS when interimResults=false
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Math.max(1, maxAlt));
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            srEvent(id, "error", errJson("aborted", String.valueOf(e.getMessage())));
            srEvent(id, "end", null);
            srActiveId = 0;
        }
    }

    // ------------------------------------------------------------------ text to speech

    private void initTts() {
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                Log.w(TAG, "TTS init failed: " + status);
                for (Object[] q : ttsQueue) ttsEvent((String) q[0], "error", errJson("synthesis-unavailable", ""));
                ttsQueue.clear();
                return;
            }
            ttsReady = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    String[] p = utteranceId.split("\\|");
                    if (p.length < 3 || "0".equals(p[1])) ttsEvent(p[0], "start", null);
                }
                @Override public void onDone(String utteranceId) {
                    String[] p = utteranceId.split("\\|");
                    if (p.length < 3 || p[1].equals(p[2])) ttsEvent(p[0], "end", null);
                }
                @SuppressWarnings("deprecation")
                @Override public void onError(String utteranceId) {
                    ttsEvent(utteranceId.split("\\|")[0], "error", errJson("synthesis-failed", ""));
                }
                @Override public void onError(String utteranceId, int errorCode) {
                    ttsEvent(utteranceId.split("\\|")[0], "error", errJson("synthesis-failed", "code " + errorCode));
                    ttsHint(null);
                }
                @Override public void onStop(String utteranceId, boolean interrupted) {
                    ttsEvent(utteranceId.split("\\|")[0], "error", errJson("interrupted", ""));
                }
            });
            js("window.__bvsTTS&&window.__bvsTTS('*','voices'," + voicesJson() + ")");
            for (Object[] q : ttsQueue) speakNow(q);
            ttsQueue.clear();
        }));
    }

    private long lastTtsHint = 0;

    /** One clear message (at most every 8 s) when the phone cannot speak a language. */
    private void ttsHint(String language) {
        long now = System.currentTimeMillis();
        if (now - lastTtsHint < 8000) return;
        lastTtsHint = now;
        toast((language == null ? "Is bhasha ki" : language) + " awaaz phone mein install nahi hai / chal nahi payi → "
                + "Settings › Text-to-speech › Google Speech Services › Install voice data se download karo (Wi-Fi par).");
    }

    private void ttsEvent(String id, String type, String json) {
        js("window.__bvsTTS&&window.__bvsTTS(" + JSONObject.quote(id) + ",'" + type + "',"
                + (json == null ? "null" : json) + ")");
    }

    private String voicesJson() {
        JSONArray arr = new JSONArray();
        if (tts == null || !ttsReady) return "[]";
        try {
            Set<Voice> vs = tts.getVoices();
            Voice def = tts.getDefaultVoice();
            if (vs != null) {
                List<Voice> list = new ArrayList<>(vs);
                list.sort((a, b) -> a.getLocale().toLanguageTag().compareTo(b.getLocale().toLanguageTag()));
                for (Voice v : list) {
                    if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    arr.put(new JSONObject()
                            .put("name", v.getName())
                            .put("lang", v.getLocale().toLanguageTag())
                            .put("local", !v.isNetworkConnectionRequired())
                            .put("def", def != null && def.getName().equals(v.getName())));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "voices", e);
        }
        return arr.toString();
    }

    private void speakNow(Object[] q) {
        String id = (String) q[0], text = (String) q[1], lang = (String) q[2], voice = (String) q[3];
        float rate = (Float) q[4], pitch = (Float) q[5], vol = (Float) q[6];
        if (tts == null) return;
        boolean voiceSet = false;
        if (voice != null && !voice.isEmpty()) {
            try {
                for (Voice v : tts.getVoices()) {
                    if (v.getName().equals(voice)) { tts.setVoice(v); voiceSet = true; break; }
                }
            } catch (Exception ignored) {}
        }
        if (!voiceSet && lang != null && !lang.isEmpty()) {
            Locale loc = Locale.forLanguageTag(lang.replace('_', '-'));
            int r = tts.setLanguage(loc);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language not available: " + lang + " (" + r + ")");
                ttsHint(loc.getDisplayLanguage(Locale.ENGLISH));
                ttsEvent(id, "error", errJson("language-unavailable", "TTS voice for " + lang + " is not installed"));
                return;
            }
        }
        tts.setSpeechRate(Math.max(0.1f, Math.min(rate, 10f)));
        tts.setPitch(Math.max(0.1f, Math.min(pitch, 2f)));
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.max(0f, Math.min(vol, 1f)));
        int max = Math.max(200, TextToSpeech.getMaxSpeechInputLength() - 10);
        List<String> parts = new ArrayList<>();
        if (text.isEmpty()) parts.add(" ");
        for (int k = 0; k < text.length(); k += max) parts.add(text.substring(k, Math.min(text.length(), k + max)));
        int last = parts.size() - 1;
        for (int k = 0; k <= last; k++) {
            int r = tts.speak(parts.get(k), TextToSpeech.QUEUE_ADD, params, id + "|" + k + "|" + last);
            if (r != TextToSpeech.SUCCESS) {
                ttsEvent(id, "error", errJson("synthesis-failed", ""));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    private void finishDownload(String id) {
        File tmp = dlFiles.remove(id);
        String[] meta = dlMeta.remove(id);
        if (tmp == null || meta == null) return;
        String name = meta[0], blobType = meta[1];
        String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(name));
        if (ext == null || ext.isEmpty()) {
            int dot = name.lastIndexOf('.');
            ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        if (mime == null) mime = blobType.isEmpty() ? "application/octet-stream" : blobType.split(";")[0];
        if (name.isEmpty() || !name.contains(".")) {
            String e2 = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = (name.isEmpty() ? "bharat-voice-studio-" + System.currentTimeMillis() : name) + (e2 != null ? "." + e2 : "");
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        try {
            String where = null;
            if (tmp.length() == 0) Log.w(TAG, "download " + name + " is empty (0 bytes)");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                Uri uri = null;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
                    try (OutputStream out = cr.openOutputStream(uri, "w"); InputStream in = new FileInputStream(tmp)) {
                        if (out == null) throw new IllegalStateException("openOutputStream returned null");
                        copy(in, out);
                        out.flush();
                    }
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    // MediaStore may have renamed it (e.g. "name (1).txt"): report the real name.
                    try (android.database.Cursor c = cr.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) name = c.getString(0);
                    }
                    where = "Download";
                    Log.i(TAG, "MediaStore download uri " + uri);
                } catch (Exception e) {
                    Log.e(TAG, "MediaStore save failed, falling back to app folder", e);
                    if (uri != null) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
                }
            }
            if (where == null) {
                File dir;
                if (Build.VERSION.SDK_INT < 29
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                } else {
                    if (Build.VERSION.SDK_INT < 29)
                        main.post(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                    dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
                if (dir == null) dir = getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, name);
                try (OutputStream out = new FileOutputStream(dest); InputStream in = new FileInputStream(tmp)) {
                    copy(in, out);
                }
                where = dir.getAbsolutePath();
            }
            Log.i(TAG, "saved download " + name + " (" + tmp.length() + " bytes) -> " + where);
            toast("Save ho gaya: " + name + " (" + where + ")");
        } catch (Exception e) {
            Log.e(TAG, "save", e);
            toast("Save nahi hua: " + e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    // ------------------------------------------------------------------ JS bridge

    private class Bridge {
        @JavascriptInterface
        public void srStart(final int id, final String lang, final boolean interim, final int maxAlt) {
            if (!trustedPage) return;
            main.post(() -> srStartOnMain(id, lang, interim, maxAlt));
        }

        @JavascriptInterface
        public void srStop(final int id) {
            if (!trustedPage) return;
            main.post(() -> {
                if (recognizer != null && srActiveId == id) recognizer.stopListening();
                else if (pendingSrStart != null && (Integer) pendingSrStart[0] == id) {
                    pendingSrStart = null;
                    srEvent(id, "end", null);
                }
            });
        }

        @JavascriptInterface
        public void srAbort(final int id) {
            if (!trustedPage) return;
            main.post(() -> {
                if (recognizer != null && srActiveId == id) {
                    try { recognizer.cancel(); } catch (Exception ignored) {}
                    srActiveId = 0;
                    srEvent(id, "error", errJson("aborted", ""));
                    srEvent(id, "end", null);
                }
            });
        }

        @JavascriptInterface
        public void ttsSpeak(String id, String text, String lang, String voice, double rate, double pitch, double vol) {
            if (!trustedPage) return;
            final Object[] q = new Object[]{id, text == null ? "" : text, lang, voice, (float) rate, (float) pitch, (float) vol};
            main.post(() -> {
                if (ttsReady) speakNow(q);
                else ttsQueue.add(q);
            });
        }

        @JavascriptInterface
        public void ttsCancel() {
            if (!trustedPage) return;
            main.post(() -> {
                ttsQueue.clear();
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface
        public String ttsVoices() {
            if (!trustedPage) return "";
            return voicesJson();
        }

        @JavascriptInterface
        public void dlBegin(String id, String name, String type) {
            if (!trustedPage) { Log.w(TAG, "dlBegin ignored: page not trusted"); return; }
            Log.i(TAG, "download begin " + name + " type=" + type);
            try {
                File f = File.createTempFile("bvs-dl-", ".part", getCacheDir());
                synchronized (dlFiles) {
                    dlFiles.put(id, f);
                    dlMeta.put(id, new String[]{name == null ? "" : name.trim(), type == null ? "" : type});
                }
            } catch (Exception e) {
                toast("Download shuru nahi hua: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void dlChunk(String id, String b64) {
            if (!trustedPage) return;
            File f;
            synchronized (dlFiles) { f = dlFiles.get(id); }
            if (f == null) { Log.w(TAG, "dlChunk for unknown download " + id); return; }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                Log.e(TAG, "chunk", e);
            }
        }

        @JavascriptInterface
        public void dlEnd(String id) {
            if (!trustedPage) { Log.w(TAG, "dlEnd ignored: page not trusted"); return; }
            synchronized (dlFiles) { finishDownload(id); }
        }

        @JavascriptInterface
        public void dlFail(String id, String why) {
            if (!trustedPage) return;
            synchronized (dlFiles) {
                File f = dlFiles.remove(id);
                dlMeta.remove(id);
                if (f != null) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
            toast("Download nahi hua: " + why);
        }

        @JavascriptInterface
        public void toast(String msg) {
            if (!trustedPage) return;
            MainActivity.this.toast(msg);
        }

        @JavascriptInterface
        public String clipRead() {
            if (!trustedPage) return "";
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return "";
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void clipWrite(final String text) {
            if (!trustedPage) return;
            main.post(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Bharat Voice Studio", text == null ? "" : text));
                } catch (Exception ignored) {}
            });
        }
    }
}
