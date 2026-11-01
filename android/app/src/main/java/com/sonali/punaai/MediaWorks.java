package com.sonali.punaai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Audio (WAV -> AAC .m4a) and Broadcast video (animated text + AAC -> H.264 .mp4) using MediaCodec/MediaMuxer. */
final class MediaWorks {
    private MediaWorks() {}

    interface Progress { void on(int pct, String stage); }

    static final class Pcm { short[] data; int rate; int channels; }

    static Pcm readWav(File f) throws IOException {
        byte[] all;
        try (InputStream in = new FileInputStream(f)) {
            all = new byte[(int) f.length()];
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
        }
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IOException("not a WAV file");
        int pos = 12, rate = 24000, ch = 1, bits = 16, dataOff = -1, dataLen = 0;
        while (pos + 8 <= all.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (id == 0x20746d66) { // "fmt "
                ch = b.getShort(pos + 10); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22);
            } else if (id == 0x61746164) { // "data"
                dataOff = pos + 8; dataLen = Math.min(len, all.length - dataOff); break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataOff < 0 || bits != 16) throw new IOException("WAV must be 16-bit PCM");
        Pcm p = new Pcm();
        p.rate = rate; p.channels = ch;
        p.data = new short[dataLen / 2];
        b.position(dataOff);
        b.asShortBuffer().get(p.data, 0, p.data.length);
        return p;
    }

    static final class Sample { byte[] data; long pts; int flags; }

    /** Encodes PCM to AAC samples. Returns output format via fmtOut[0]. */
    static List<Sample> encodeAac(Pcm pcm, MediaFormat[] fmtOut, Progress pr, int pctFrom, int pctTo) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, pcm.channels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        c.start();
        List<Sample> out = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idx = 0; // in shorts
        boolean inDone = false, outDone = false;
        int frameShorts = 1024 * pcm.channels;
        int lastPct = -1;
        try {
            while (!outDone) {
                if (!inDone) {
                    int ii = c.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = c.getInputBuffer(ii);
                        ib.clear();
                        int n = Math.min(Math.min(frameShorts * 4, ib.capacity() / 2), pcm.data.length - idx);
                        long pts = (long) idx / pcm.channels * 1000000L / pcm.rate;
                        if (n <= 0) {
                            c.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm.data, idx, n);
                            c.queueInputBuffer(ii, 0, n * 2, pts, 0);
                            idx += n;
                            int pct = pctFrom + (int) ((long) (pctTo - pctFrom) * idx / Math.max(1, pcm.data.length));
                            if (pr != null && pct != lastPct) { lastPct = pct; pr.on(pct, "audio"); }
                        }
                    }
                }
                int oi = c.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    fmtOut[0] = c.getOutputFormat();
                } else if (oi >= 0) {
                    ByteBuffer ob = c.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        Sample s = new Sample();
                        s.data = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(s.data);
                        s.pts = info.presentationTimeUs;
                        s.flags = info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        out.add(s);
                    }
                    c.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            try { c.stop(); } catch (Exception ignored) {}
            c.release();
        }
        if (fmtOut[0] == null) throw new IOException("AAC encoder gave no format");
        return out;
    }

    static void wavToM4a(File wav, File out, Progress pr) throws IOException {
        Pcm pcm = readWav(wav);
        MediaFormat[] fmt = new MediaFormat[1];
        List<Sample> samples = encodeAac(pcm, fmt, pr, 0, 95);
        MediaMuxer mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int t = mx.addTrack(fmt[0]);
            mx.start();
            writeSamples(mx, t, samples);
            mx.stop();
        } finally {
            mx.release();
        }
        if (pr != null) pr.on(100, "done");
    }

    private static void writeSamples(MediaMuxer mx, int track, List<Sample> samples) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        for (Sample s : samples) {
            bi.set(0, s.data.length, s.pts, s.flags);
            mx.writeSampleData(track, ByteBuffer.wrap(s.data), bi);
        }
    }

    // ------------------------------------------------------------------ video

    static final class Line { String name, text; int color; double start, end; }

    static void makeVideo(JSONObject spec, File wav, File out, Progress pr) throws Exception {
        final int W = spec.optInt("w", 720), H = spec.optInt("h", 1280), FPS = spec.optInt("fps", 20);
        final String title = spec.optString("title", "Puna AI Broadcast");
        final String brand = spec.optString("brand", "Made with Puna AI");
        JSONArray ja = spec.getJSONArray("lines");
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < ja.length(); i++) {
            JSONObject o = ja.getJSONObject(i);
            Line l = new Line();
            l.name = o.optString("name", "");
            l.text = o.optString("text", "");
            l.color = parseColor(o.optString("color", "#8b5cf6"));
            l.start = o.optDouble("start", 0);
            l.end = o.optDouble("end", 0);
            lines.add(l);
        }

        Pcm pcm = readWav(wav);
        double duration = Math.max(1.0, (double) pcm.data.length / pcm.channels / pcm.rate);
        MediaFormat[] afmt = new MediaFormat[1];
        List<Sample> audio = encodeAac(pcm, afmt, pr, 0, 8);
        pcm = null;

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, W * H * 3);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vTrack = -1;
        boolean muxStarted = false;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Renderer rd = new Renderer(W, H, title, brand, lines, duration);
        int[] px = new int[W * H];
        int total = (int) Math.ceil(duration * FPS);
        int frame = 0;
        boolean inDone = false, outDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int lastPct = -1;
        try {
            while (!outDone) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        long pts = (long) frame * 1000000L / FPS;
                        if (frame >= total) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            rd.draw(cv, frame / (double) FPS);
                            bmp.getPixels(px, 0, W, 0, 0, W, H);
                            Image img = enc.getInputImage(ii);
                            int size;
                            if (img != null) {
                                fillImage(img, px, W, H);
                                size = W * H * 3 / 2;
                            } else {
                                ByteBuffer ib = enc.getInputBuffer(ii);
                                ib.clear();
                                fillI420(ib, px, W, H);
                                size = W * H * 3 / 2;
                            }
                            enc.queueInputBuffer(ii, 0, size, pts, 0);
                            frame++;
                            int pct = 8 + (int) (91L * frame / Math.max(1, total));
                            if (pr != null && pct != lastPct) { lastPct = pct; pr.on(pct, "video"); }
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = mx.addTrack(enc.getOutputFormat());
                    int aTrack = mx.addTrack(afmt[0]);
                    mx.start();
                    muxStarted = true;
                    writeSamples(mx, aTrack, audio);
                    audio = null;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxStarted) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mx.writeSampleData(vTrack, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (muxStarted) mx.stop();
        } finally {
            try { enc.stop(); } catch (Exception ignored) {}
            enc.release();
            try { mx.release(); } catch (Exception ignored) {}
            bmp.recycle();
        }
        if (!muxStarted) throw new IOException("video encoder produced nothing");
        if (pr != null) pr.on(100, "done");
    }

    private static int parseColor(String s) {
        try { return Color.parseColor(s); } catch (Exception e) { return 0xFF8B5CF6; }
    }

    private static void fillImage(Image img, int[] px, int W, int H) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        byte[] row = new byte[W];
        for (int y = 0; y < H; y++) {
            int ro = y * W, yo = y * yRow;
            for (int x = 0; x < W; x++) {
                int c = px[ro + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                row[x] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
            if (yPix == 1) {
                yb.position(yo);
                yb.put(row, 0, W);
            } else {
                for (int x = 0; x < W; x++) yb.put(yo + x * yPix, row[x]);
            }
        }
        for (int y = 0; y < H / 2; y++) {
            for (int x = 0; x < W / 2; x++) {
                int c = px[(y * 2) * W + x * 2];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                ub.put(y * uRow + x * uPix, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                vb.put(y * vRow + x * vPix, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
            }
        }
    }

    private static void fillI420(ByteBuffer ib, int[] px, int W, int H) {
        byte[] y = new byte[W * H], u = new byte[W * H / 4], v = new byte[W * H / 4];
        for (int i = 0; i < W * H; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            y[i] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }
        for (int j = 0; j < H / 2; j++) for (int i = 0; i < W / 2; i++) {
            int c = px[(j * 2) * W + i * 2];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            u[j * (W / 2) + i] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            v[j * (W / 2) + i] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
        ib.put(y).put(u).put(v);
    }

    /** Draws one frame: drifting gradient, bokeh, title, speaker card with word-by-word reveal, progress. */
    static final class Renderer {
        final int W, H;
        final String title, brand;
        final List<Line> lines;
        final double dur;
        final Paint bg = new Paint(), p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final boolean portrait;
        int cachedLine = -1;
        float cachedSize = 0;

        Renderer(int W, int H, String title, String brand, List<Line> lines, double dur) {
            this.W = W; this.H = H; this.title = title; this.brand = brand; this.lines = lines; this.dur = dur;
            portrait = H > W;
            tp.setColor(Color.WHITE);
            tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            small.setColor(Color.WHITE);
            small.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        }

        int current(double t) {
            for (int i = 0; i < lines.size(); i++) if (t < lines.get(i).end) return i;
            return lines.size() - 1;
        }

        static int mix(int a, int b, float f) {
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
            return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bgc - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
        }

        void draw(Canvas c, double t) {
            int li = lines.isEmpty() ? -1 : current(t);
            Line L = li >= 0 ? lines.get(li) : null;
            int accent = L != null ? L.color : 0xFF8B5CF6;
            float wob = (float) (0.5 + 0.5 * Math.sin(t * 0.6));
            int c1 = mix(0xFF0B1026, mix(accent, 0xFF000000, 0.55f), 0.55f + 0.2f * wob);
            int c2 = mix(0xFF1E1B4B, 0xFF0F172A, wob);
            bg.setShader(new LinearGradient(0, 0, W * (0.3f + 0.4f * wob), H, c1, c2, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, bg);
            // bokeh
            for (int i = 0; i < 7; i++) {
                double ph = i * 1.7 + t * (0.15 + i * 0.03);
                float x = (float) (W * (0.5 + 0.42 * Math.sin(ph * 1.3 + i)));
                float y = (float) (H * (0.5 + 0.42 * Math.cos(ph + i * 0.7)));
                float r = Math.min(W, H) * (0.08f + 0.05f * (i % 3));
                p.setShader(null);
                p.setColor((i % 2 == 0 ? accent : 0xFF22D3EE) & 0x00FFFFFF | 0x22000000);
                c.drawCircle(x, y, r, p);
            }
            float u = Math.min(W, H) / 720f;
            // title
            small.setTextSize(26 * u);
            small.setColor(0xCCFFFFFF);
            c.drawText("● LIVE  ·  PUNA AI BROADCAST", 40 * u, (portrait ? 90 : 60) * u, small);
            TextPaint tt = new TextPaint(tp);
            tt.setTextSize((portrait ? 40 : 34) * u);
            StaticLayout tl = layout(title, tt, W - (int) (80 * u), Layout.Alignment.ALIGN_NORMAL);
            c.save();
            c.translate(40 * u, (portrait ? 110 : 75) * u);
            if (tl.getLineCount() > 2) tl = layout(ellipsize(title, 70), tt, W - (int) (80 * u), Layout.Alignment.ALIGN_NORMAL);
            tl.draw(c);
            c.restore();

            if (L != null) {
                float cardTop = portrait ? H * 0.30f : H * 0.30f, cardBot = portrait ? H * 0.80f : H * 0.84f;
                float left = 36 * u, right = W - 36 * u;
                float appear = (float) Math.min(1, Math.max(0, (t - L.start) / 0.35));
                c.save();
                c.translate(0, (1 - appear) * 30 * u);
                p.setShader(null);
                p.setColor(0x33FFFFFF);
                c.drawRoundRect(new RectF(left, cardTop, right, cardBot), 36 * u, 36 * u, p);
                p.setColor(accent);
                c.drawRoundRect(new RectF(left, cardTop, left + 10 * u, cardBot), 5 * u, 5 * u, p);
                // avatar + name
                float ax = left + 70 * u, ay = cardTop + 75 * u, ar = 42 * u;
                double pulse = 1 + 0.06 * Math.sin(t * 9);
                p.setColor(accent & 0x00FFFFFF | 0x55000000);
                c.drawCircle(ax, ay, (float) (ar * 1.25 * pulse), p);
                p.setColor(accent);
                c.drawCircle(ax, ay, ar, p);
                small.setColor(Color.WHITE);
                small.setTextSize(40 * u);
                String ini = L.name.isEmpty() ? "?" : L.name.substring(0, 1).toUpperCase();
                c.drawText(ini, ax - small.measureText(ini) / 2, ay + 14 * u, small);
                small.setTextSize(36 * u);
                c.drawText(L.name, ax + ar + 28 * u, ay + 12 * u, small);
                // text with reveal
                double span = Math.max(0.3, L.end - L.start - 0.2);
                float frac = (float) Math.min(1, Math.max(0, (t - L.start) / span));
                int words = L.text.split("\\s+").length;
                int shown = (int) Math.ceil(words * Math.min(1, frac * 1.08));
                int cut = cutAfterWords(L.text, shown);
                SpannableString ss = new SpannableString(L.text);
                if (cut < L.text.length()) ss.setSpan(new ForegroundColorSpan(0x55FFFFFF), cut, L.text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int tw = (int) (right - left - 70 * u);
                float avail = cardBot - (ay + ar + 40 * u) - 30 * u;
                if (cachedLine != li) {
                    float size = (portrait ? 52 : 44) * u;
                    TextPaint mp = new TextPaint(tp);
                    while (size > 18 * u) {
                        mp.setTextSize(size);
                        if (layout(L.text, mp, tw, Layout.Alignment.ALIGN_NORMAL).getHeight() <= avail) break;
                        size -= 2 * u;
                    }
                    cachedLine = li;
                    cachedSize = size;
                }
                TextPaint mp = new TextPaint(tp);
                mp.setTextSize(cachedSize);
                StaticLayout sl = layout(ss, mp, tw, Layout.Alignment.ALIGN_NORMAL);
                c.translate(left + 40 * u, ay + ar + 40 * u);
                sl.draw(c);
                c.restore();
            }
            // equalizer bars + progress
            float base = H - (portrait ? 150 : 70) * u;
            p.setShader(null);
            for (int i = 0; i < 24; i++) {
                float hgt = (float) ((10 + 34 * Math.abs(Math.sin(t * 7 + i * 0.9) * Math.cos(t * 3.1 + i))) * u);
                p.setColor(i % 2 == 0 ? accent : 0xFF22D3EE);
                float x = W / 2f + (i - 12) * 14 * u;
                c.drawRoundRect(new RectF(x, base - hgt, x + 8 * u, base), 4 * u, 4 * u, p);
            }
            float pb = H - (portrait ? 100 : 40) * u;
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(40 * u, pb, W - 40 * u, pb + 8 * u), 4 * u, 4 * u, p);
            p.setColor(accent);
            c.drawRoundRect(new RectF(40 * u, pb, 40 * u + (W - 80 * u) * (float) Math.min(1, t / dur), pb + 8 * u), 4 * u, 4 * u, p);
            small.setTextSize(22 * u);
            small.setColor(0x99FFFFFF);
            c.drawText(brand, W - 40 * u - small.measureText(brand), pb + 40 * u, small);
        }

        static String ellipsize(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

        static int cutAfterWords(String s, int n) {
            if (n <= 0) return 0;
            int count = 0;
            boolean inWord = false;
            for (int i = 0; i < s.length(); i++) {
                boolean ws = Character.isWhitespace(s.charAt(i));
                if (!ws && !inWord) { inWord = true; }
                if (ws && inWord) { inWord = false; count++; if (count >= n) return i; }
            }
            return s.length();
        }

        @SuppressWarnings("deprecation")
        static StaticLayout layout(CharSequence s, TextPaint tp, int width, Layout.Alignment al) {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                return StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(10, width)).setAlignment(al)
                        .setLineSpacing(0, 1.15f).build();
            }
            return new StaticLayout(s, tp, Math.max(10, width), al, 1.15f, 0, false);
        }
    }
}
