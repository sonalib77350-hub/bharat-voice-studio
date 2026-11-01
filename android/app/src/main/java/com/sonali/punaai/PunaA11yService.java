package com.sonali.punaai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Puna Agent's eyes and hands on a non-rooted phone: reads the screen's UI tree, taps / types / scrolls,
 * and global actions (back, home, recents, notifications, screenshot). The user turns it on in
 * Settings › Accessibility › Puna AI Agent. A floating ⏹ Stop bubble is shown while the agent runs.
 */
public class PunaA11yService extends AccessibilityService {
    static volatile PunaA11yService instance;
    static volatile Runnable onStopTapped;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView bubble;
    /** Nodes from the last read_screen, so the model can say "tap #12". */
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();

    static boolean enabled(Context c) {
        if (instance != null) return true;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/");
        } catch (Exception e) { return false; }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        hideBubble();
        hideConfirm();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        hideBubble();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    // ------------------------------------------------------------------ Stop bubble (accessibility overlay, no extra permission)

    void showBubble(final String text) {
        main.post(() -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (bubble == null) {
                    bubble = new TextView(this);
                    bubble.setTextColor(Color.WHITE);
                    bubble.setTextSize(13);
                    bubble.setPadding(28, 18, 28, 18);
                    bubble.setBackgroundColor(Color.parseColor("#E0DC2626"));
                    bubble.setMaxWidth(900);
                    bubble.setOnClickListener(v -> { Runnable r = onStopTapped; if (r != null) r.run(); });
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.END;
                    lp.y = 160;
                    wm.addView(bubble, lp);
                }
                bubble.setText("⏹ Stop Puna · " + (text == null ? "" : (text.length() > 40 ? text.substring(0, 40) + "…" : text)));
            } catch (Exception e) { android.util.Log.w("PUNA", "bubble", e); }
        });
    }

    void hideBubble() {
        main.post(() -> {
            if (bubble != null) {
                try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bubble); } catch (Exception ignored) {}
                bubble = null;
            }
        });
    }

    // ------------------------------------------------------------------ confirmation overlay (shown over other apps)
    private android.widget.LinearLayout confirmBox;
    private android.view.View yesBtn, noBtn;

    /** Centre of an overlay control on screen ("yes" | "no" | "bubble"), for the emulator tests. */
    String overlayPos(String which) {
        android.view.View v = "yes".equals(which) ? yesBtn : "no".equals(which) ? noBtn : bubble;
        if (v == null || !v.isAttachedToWindow()) return "";
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return (xy[0] + v.getWidth() / 2) + "," + (xy[1] + v.getHeight() / 2);
    }

    interface Answer { void on(boolean yes); }

    void askConfirm(final String text, final Answer cb) {
        main.post(() -> {
            hideConfirmNow();
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                android.widget.LinearLayout box = new android.widget.LinearLayout(this);
                box.setOrientation(android.widget.LinearLayout.VERTICAL);
                box.setPadding(40, 36, 40, 30);
                box.setBackgroundColor(Color.parseColor("#F20F1430"));
                TextView t = new TextView(this);
                t.setText("🛡️ Puna: " + text);
                t.setTextColor(Color.WHITE);
                t.setTextSize(17);
                box.addView(t);
                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setPadding(0, 26, 0, 0);
                android.widget.Button yes = new android.widget.Button(this), no = new android.widget.Button(this);
                yes.setText("✅ Haan, karo");
                no.setText("✖ Nahi");
                yes.setOnClickListener(v -> { hideConfirmNow(); cb.on(true); });
                no.setOnClickListener(v -> { hideConfirmNow(); cb.on(false); });
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(yes, lp);
                row.addView(no, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                box.addView(row);
                yesBtn = yes; noBtn = no;
                WindowManager.LayoutParams wl = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                wl.gravity = Gravity.BOTTOM;
                wm.addView(box, wl);
                confirmBox = box;
            } catch (Exception e) { android.util.Log.w("PUNA", "confirm overlay", e); }
        });
    }

    void hideConfirm() { main.post(this::hideConfirmNow); }

    private void hideConfirmNow() {
        if (confirmBox != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(confirmBox); } catch (Exception ignored) {}
            confirmBox = null;
        }
    }

    // ------------------------------------------------------------------ screen reading

    private AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) return r;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.isActive() && w.getRoot() != null) return w.getRoot();
            }
        } catch (Exception ignored) {}
        return null;
    }

    String currentPackage() {
        AccessibilityNodeInfo r = root();
        return r != null && r.getPackageName() != null ? r.getPackageName().toString() : "";
    }

    /** Compact list of meaningful nodes: [{i, t(ext), d(esc), id, c(lass), k(clickable), e(ditable), s(crollable), b(ounds)}]. */
    synchronized JSONObject readScreen(int max) throws Exception {
        lastNodes.clear();
        JSONArray arr = new JSONArray();
        AccessibilityNodeInfo r = root();
        JSONObject out = new JSONObject();
        out.put("app", r != null && r.getPackageName() != null ? r.getPackageName().toString() : "");
        if (r != null) walk(r, arr, max, 0);
        out.put("nodes", arr);
        out.put("count", arr.length());
        return out;
    }

    private void walk(AccessibilityNodeInfo n, JSONArray arr, int max, int depth) throws Exception {
        if (n == null || arr.length() >= max || depth > 40) return;
        if (!n.isVisibleToUser()) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        String id = n.getViewIdResourceName();
        boolean click = n.isClickable() || n.isLongClickable(), edit = n.isEditable(), scroll = n.isScrollable();
        boolean meaningful = !TextUtils.isEmpty(t) || !TextUtils.isEmpty(d) || edit || scroll || (click && id != null);
        if (meaningful) {
            JSONObject o = new JSONObject();
            o.put("i", lastNodes.size());
            if (!TextUtils.isEmpty(t)) o.put("t", clip(t.toString(), 80));
            if (!TextUtils.isEmpty(d)) o.put("d", clip(d.toString(), 60));
            if (id != null) o.put("id", id.contains(":id/") ? id.substring(id.indexOf(":id/") + 4) : id);
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            o.put("c", cls.substring(cls.lastIndexOf('.') + 1));
            if (click) o.put("k", 1);
            if (edit) o.put("e", 1);
            if (scroll) o.put("s", 1);
            if (n.isChecked()) o.put("on", 1);
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            o.put("b", b.centerX() + "," + b.centerY());
            arr.put(o);
            lastNodes.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), arr, max, depth + 1);
    }

    private static String clip(String s, int n) { s = s.replace('\n', ' '); return s.length() > n ? s.substring(0, n) + "…" : s; }

    // ------------------------------------------------------------------ finding nodes

    private static String norm(CharSequence c) { return c == null ? "" : c.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(); }

    /** Best node for a text: exact text/desc match first, then "starts with", then "contains". */
    AccessibilityNodeInfo findByText(String q) {
        AccessibilityNodeInfo r = root();
        if (r == null || q == null) return null;
        String want = norm(q);
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(r, all, 0);
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser()) continue;
            String t = norm(n.getText()), d = norm(n.getContentDescription());
            int sc = 0;
            if (t.equals(want) || d.equals(want)) sc = 3;
            else if (t.startsWith(want) || d.startsWith(want)) sc = 2;
            else if ((!t.isEmpty() && t.contains(want)) || (!d.isEmpty() && d.contains(want))) sc = 1;
            if (sc > bestScore) { bestScore = sc; best = n; }
        }
        return best;
    }

    AccessibilityNodeInfo findById(String id) {
        AccessibilityNodeInfo r = root();
        if (r == null || id == null) return null;
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(r, all, 0);
        for (AccessibilityNodeInfo n : all) {
            String v = n.getViewIdResourceName();
            if (v != null && (v.equals(id) || v.endsWith(":id/" + id)) && n.isVisibleToUser()) return n;
        }
        return null;
    }

    synchronized AccessibilityNodeInfo byIndex(int i) { return i >= 0 && i < lastNodes.size() ? lastNodes.get(i) : null; }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 1500) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    static String label(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (TextUtils.isEmpty(t)) t = n.getContentDescription();
        if (TextUtils.isEmpty(t)) {
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null && !TextUtils.isEmpty(c.getText())) { t = c.getText(); break; }
            }
        }
        return t == null ? "" : t.toString();
    }

    // ------------------------------------------------------------------ actions

    /** Click the node or its nearest clickable ancestor; fall back to a gesture tap on its centre. */
    boolean click(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo p = n;
        for (int k = 0; p != null && k < 8; k++, p = p.getParent()) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return tapXY(b.centerX(), b.centerY());
    }

    boolean tapXY(int x, int y) {
        return gesture(x, y, x, y, 60);
    }

    boolean swipe(int x1, int y1, int x2, int y2, int ms) { return gesture(x1, y1, x2, y2, ms); }

    private boolean gesture(int x1, int y1, int x2, int y2, int ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, ms))).build();
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; l.countDown(); }
            @Override public void onCancelled(GestureDescription d) { l.countDown(); }
        }, main);
        if (!sent) return false;
        try { l.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    /** Type into the given node, the focused field, or the first editable field on screen. */
    boolean type(AccessibilityNodeInfo target, String text, boolean append) {
        AccessibilityNodeInfo n = target;
        AccessibilityNodeInfo r = root();
        if (n == null && r != null) n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if ((n == null || !n.isEditable()) && r != null) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            collect(r, all, 0);
            for (AccessibilityNodeInfo x : all) if (x.isEditable() && x.isVisibleToUser()) { n = x; break; }
        }
        if (n == null) return false;
        if (!n.isFocused()) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        String v = text;
        if (append && n.getText() != null) v = n.getText().toString() + text;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean scroll(String dir) {
        AccessibilityNodeInfo r = root();
        if (r == null) return false;
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(r, all, 0);
        boolean fwd = !"up".equals(dir) && !"left".equals(dir);
        for (AccessibilityNodeInfo n : all) {
            if (n.isScrollable() && n.isVisibleToUser()
                    && n.performAction(fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
        }
        // no scrollable node answered: swipe on the screen
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        if ("left".equals(dir) || "right".equals(dir)) {
            return fwd ? swipe(w * 4 / 5, h / 2, w / 5, h / 2, 300) : swipe(w / 5, h / 2, w * 4 / 5, h / 2, 300);
        }
        return fwd ? swipe(w / 2, h * 3 / 4, w / 2, h / 4, 350) : swipe(w / 2, h / 4, w / 2, h * 3 / 4, 350);
    }

    boolean global(String what) {
        int a;
        switch (what) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_dialog": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < 28) return false;
                a = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return false;
                a = GLOBAL_ACTION_LOCK_SCREEN; break;
            default: return false;
        }
        return performGlobalAction(a);
    }
}
