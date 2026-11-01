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

    private static byte[] synthOnce(String text, String voice, String rate, String pitch) throws Exception {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        final Exception[] err = new Exception[1];
        String url = WSS + "&ConnectionId=" + uuid()
                + "&Sec-MS-GEC=" + EdgeDrm.secMsGec(System.currentTimeMillis() / 1000.0 + skewSeconds)
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
        Request req = new Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + muid() + ";")
                .build();
        final String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'><prosody pitch='" + pitch + "' rate='" + rate + "' volume='+0%'>"
                + escape(text) + "</prosody></voice></speak>";
        WebSocket ws = client().newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                w.send("X-Timestamp:" + jsDate() + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");
                w.send("X-RequestId:" + uuid() + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "X-Timestamp:" + jsDate() + "Z\r\n"
                        + "Path:ssml\r\n\r\n" + ssml);
            }

            @Override public void onMessage(WebSocket w, String t) {
                int h = t.indexOf("\r\n\r\n");
                String head = h >= 0 ? t.substring(0, h) : t;
                if (head.contains("Path:turn.end")) {
                    w.close(1000, null);
                    done.countDown();
                }
            }

            @Override public void onMessage(WebSocket w, ByteString bytes) {
                byte[] d = bytes.toByteArray();
                if (d.length < 2) return;
                int hl = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                if (hl + 2 > d.length) return;
                String head = new String(d, 2, hl, StandardCharsets.UTF_8);
                if (!head.contains("Path:audio")) return;
                int start = 2 + hl;
                if (d.length > start && head.contains("Content-Type:audio/mpeg")) audio.write(d, start, d.length - start);
            }

            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                int code = r != null ? r.code() : 0;
                if (r != null && code == 403) {
                    String date = r.header("Date");
                    if (date != null) {
                        try {
                            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                            double server = f.parse(date).getTime() / 1000.0;
                            skewSeconds += server - (System.currentTimeMillis() / 1000.0 + skewSeconds);
                        } catch (Exception ignored) {}
                    }
                }
                err[0] = new EdgeException("Edge TTS: " + (code > 0 ? "HTTP " + code + " " : "") + t, code);
                done.countDown();
            }

            @Override public void onClosing(WebSocket w, int code, String reason) {
                w.close(1000, null);
                done.countDown();
            }

            @Override public void onClosed(WebSocket w, int code, String reason) { done.countDown(); }
        });
        if (!done.await(45, TimeUnit.SECONDS)) {
            ws.cancel();
            throw new EdgeException("Edge TTS timeout", 0);
        }
        if (audio.size() == 0) {
            if (err[0] != null) throw err[0];
            throw new EdgeException("Edge TTS: no audio (voice '" + voice + "' may not exist)", 1);
        }
        return audio.toByteArray();
    }
}
