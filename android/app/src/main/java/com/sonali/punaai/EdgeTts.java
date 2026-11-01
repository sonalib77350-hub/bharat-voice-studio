package com.sonali.punaai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Microsoft Edge "Read aloud" neural TTS over WebSocket (free, no key).
 * Protocol follows edge-tts 7.3.1 (https://github.com/rany2/edge-tts): Sec-MS-GEC v2 token,
 * speech.config + ssml messages, binary "Path:audio" frames carrying 24 kHz 48 kbps mono MP3.
 */
public final class EdgeTts {
    static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    static final String CHROMIUM_MAJOR = "143";
    static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;
    static final String WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken="
            + EdgeDrm.TRUSTED_CLIENT_TOKEN;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR + ".0.0.0";

    private static volatile double skewSeconds = 0;
    private static OkHttpClient client;
    private static final SecureRandom RND = new SecureRandom();

    static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    /** For desktop tests (proxy). */
    static synchronized void setClient(OkHttpClient c) { client = c; }

    public static class EdgeException extends Exception {
        public final int code;
        EdgeException(String m, int code) { super(m); this.code = code; }
    }

    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String muid() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X", x & 0xFF));
        return s.toString();
    }

    private static String jsDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date()) + " GMT+0000 (Coordinated Universal Time)";
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c <= 8) || c == 11 || c == 12 || (c >= 14 && c <= 31)) { b.append(' '); continue; }
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** Split long text at sentence/space boundaries so each SSML stays well under the service limit. */
    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        String t = text.trim();
        while (t.length() > max) {
            int cut = -1;
            for (String sep : new String[]{"\n", "। ", "॥", ". ", "? ", "! ", "; ", ", ", " "}) {
                int k = t.lastIndexOf(sep, max);
                if (k > max / 3) { cut = k + sep.length(); break; }
            }
            if (cut <= 0) cut = max;
            out.add(t.substring(0, cut).trim());
            t = t.substring(cut).trim();
        }
        if (!t.isEmpty()) out.add(t);
        return out;
    }

    /**
     * @param rate  e.g. "+30%"   @param pitch e.g. "+0Hz"
     * @return MP3 bytes (audio-24khz-48kbitrate-mono-mp3)
     */
    public static byte[] synth(String text, String voice, String rate, String pitch) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (String part : split(text, 2000)) {
            byte[] b;
            try {
                b = synthOnce(part, voice, rate, pitch);
            } catch (EdgeException e) {
                if (e.code == 403) b = synthOnce(part, voice, rate, pitch); // retried once after clock-skew fix
                else throw e;
            }
            all.write(b);
        }
        return all.toByteArray();
    }

    /** Last synth stats (for the latency readout): first-audio ms, total ms, whether a warm socket was used. */
    static volatile long lastFirstMs, lastTotalMs;
    static volatile boolean lastWarm;

    private static Request buildRequest() {
        String url = WSS + "&ConnectionId=" + uuid()
                + "&Sec-MS-GEC=" + EdgeDrm.secMsGec(System.currentTimeMillis() / 1000.0 + skewSeconds)
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
        return new Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + muid() + ";")
                .build();
    }

    private static String configMsg() {
        return "X-Timestamp:" + jsDate() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    private static String ssmlMsg(String text, String voice, String rate, String pitch) {
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'><prosody pitch='" + pitch + "' rate='" + rate + "' volume='+0%'>"
                + escape(text) + "</prosody></voice></speak>";
        return "X-RequestId:" + uuid() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + jsDate() + "Z\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
    }

    private static void noteSkew(Response r) {
        if (r == null || r.code() != 403) return;
        String date = r.header("Date");
        if (date == null) return;
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            double server = f.parse(date).getTime() / 1000.0;
            skewSeconds += server - (System.currentTimeMillis() / 1000.0 + skewSeconds);
        } catch (Exception ignored) {}
    }

    /** One in-flight synthesis on a connection. */
    static final class Job {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Exception err;
        volatile long firstAt;
    }

    /**
     * A reusable Edge WebSocket. Measured 2026-11-01 from the sandbox: a new connection's first request takes
     * 0.5–5 s to first audio, a request on an already-open connection ~140 ms; the service drops idle sockets
     * after 30–60 s (pings do not keep them alive), so idle sockets older than MAX_IDLE_MS are thrown away.
     */
    static final class Conn extends WebSocketListener {
        WebSocket ws;
        final CountDownLatch opened = new CountDownLatch(1);
        volatile boolean open, dead, busy;
        volatile long lastUsed = System.currentTimeMillis();
        volatile Job job;
        int uses;

        void start() { ws = client().newWebSocket(buildRequest(), this); }

        boolean fresh(long now) { return !dead && now - lastUsed < MAX_IDLE_MS; }

        void kill() {
            dead = true;
            try { if (ws != null) ws.cancel(); } catch (Exception ignored) {}
            Job j = job;
            if (j != null) { if (j.err == null) j.err = new EdgeException("Edge TTS: connection closed", 0); j.done.countDown(); }
            opened.countDown();
        }

        @Override public void onOpen(WebSocket w, Response r) {
            w.send(configMsg());
            open = true;
            lastUsed = System.currentTimeMillis();
            opened.countDown();
        }

        @Override public void onMessage(WebSocket w, String t) {
            int h = t.indexOf("\r\n\r\n");
            String head = h >= 0 ? t.substring(0, h) : t;
            Job j = job;
            if (j != null && head.contains("Path:turn.end")) j.done.countDown();
        }

        @Override public void onMessage(WebSocket w, ByteString bytes) {
            Job j = job;
            if (j == null) return;
            byte[] d = bytes.toByteArray();
            if (d.length < 2) return;
            int hl = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            if (hl + 2 > d.length) return;
            String head = new String(d, 2, hl, StandardCharsets.UTF_8);
            if (!head.contains("Path:audio")) return;
            int start = 2 + hl;
            if (d.length > start && head.contains("Content-Type:audio/mpeg")) {
                if (j.firstAt == 0) j.firstAt = System.currentTimeMillis();
                synchronized (j.audio) { j.audio.write(d, start, d.length - start); }
            }
        }

        @Override public void onFailure(WebSocket w, Throwable t, Response r) {
            int code = r != null ? r.code() : 0;
            noteSkew(r);
            Job j = job;
            if (j != null && j.err == null) j.err = new EdgeException("Edge TTS: " + (code > 0 ? "HTTP " + code + " " : "") + t, code);
            dead = true;
            if (j != null) j.done.countDown();
            opened.countDown();
        }

        @Override public void onClosing(WebSocket w, int code, String reason) {
            dead = true;
            w.close(1000, null);
            Job j = job;
            if (j != null) j.done.countDown();
            opened.countDown();
        }

        @Override public void onClosed(WebSocket w, int code, String reason) {
            dead = true;
            Job j = job;
            if (j != null) j.done.countDown();
            opened.countDown();
        }
    }

    static final long MAX_IDLE_MS = 22000;
    static final int MAX_POOL = 3;
    private static final List<Conn> pool = new ArrayList<>();

    private static synchronized Conn acquire(boolean allowWarm) {
        long now = System.currentTimeMillis();
        for (int i = pool.size() - 1; i >= 0; i--) {
            Conn c = pool.get(i);
            if (!c.busy && !c.fresh(now)) { pool.remove(i); c.kill(); }
        }
        if (allowWarm) {
            for (Conn c : pool) if (!c.busy && !c.dead) { c.busy = true; return c; }
        }
        Conn c = new Conn();
        c.busy = true;
        pool.add(c);
        c.start();
        return c;
    }

    private static synchronized void release(Conn c, boolean ok) {
        c.busy = false;
        c.job = null;
        c.lastUsed = System.currentTimeMillis();
        int idle = 0;
        for (Conn x : pool) if (x != c && !x.busy && !x.dead) idle++;
        if (!ok || c.dead || idle >= MAX_POOL) { pool.remove(c); c.kill(); }
    }

    /** Open up to n idle connections ahead of time (called when the user starts talking). */
    public static synchronized void prewarm(int n) {
        long now = System.currentTimeMillis();
        int ready = 0;
        for (int i = pool.size() - 1; i >= 0; i--) {
            Conn c = pool.get(i);
            if (!c.busy && !c.fresh(now)) { pool.remove(i); c.kill(); continue; }
            // a socket about to expire is not worth counting: the reply would arrive after it is dropped
            if (!c.busy && now - c.lastUsed < MAX_IDLE_MS - 8000) ready++;
        }
        for (int k = ready; k < Math.min(n, MAX_POOL); k++) {
            Conn c = new Conn();
            pool.add(c);
            c.start();
        }
    }

    public static synchronized int poolSize() { return pool.size(); }

    private static byte[] synthOnce(String text, String voice, String rate, String pitch) throws Exception {
        try {
            return synthOn(acquire(true), text, voice, rate, pitch);
        } catch (EdgeException e) {
            if (e.code == 403) throw e;
            // a pooled socket can die while idle: retry once on a brand-new connection
            return synthOn(acquire(false), text, voice, rate, pitch);
        }
    }

    private static byte[] synthOn(Conn c, String text, String voice, String rate, String pitch) throws Exception {
        long t0 = System.currentTimeMillis();
        boolean warm = c.open;
        Job j = new Job();
        boolean ok = false;
        try {
            if (!c.opened.await(15, TimeUnit.SECONDS) || c.dead || !c.open) {
                c.kill();
                throw new EdgeException("Edge TTS: connect failed", 0);
            }
            c.job = j;
            c.uses++;
            if (!c.ws.send(ssmlMsg(text, voice, rate, pitch))) { c.kill(); throw new EdgeException("Edge TTS: send failed", 0); }
            if (!j.done.await(30, TimeUnit.SECONDS)) { c.kill(); throw new EdgeException("Edge TTS timeout", 0); }
            byte[] out;
            synchronized (j.audio) { out = j.audio.toByteArray(); }
            if (out.length == 0) {
                if (j.err != null) throw j.err;
                throw new EdgeException("Edge TTS: no audio (voice '" + voice + "' may not exist)", c.dead ? 0 : 1);
            }
            if (c.dead) { // closed right after turn.end: still a good result
                ok = false;
            } else ok = true;
            lastFirstMs = j.firstAt > 0 ? j.firstAt - t0 : -1;
            lastTotalMs = System.currentTimeMillis() - t0;
            lastWarm = warm;
            return out;
        } finally {
            release(c, ok);
        }
    }
}
