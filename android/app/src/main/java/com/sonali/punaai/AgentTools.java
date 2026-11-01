package com.sonali.punaai;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone tools for the Puna Agent. Every call runs on a background thread and returns JSON {ok, ...}.
 * Safety confirmations (send / call / delete / pay) are asked by the UI before these run.
 */
final class AgentTools {
    private final Context ctx;
    AgentTools(Context c) { ctx = c.getApplicationContext(); }

    static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }
    static JSONObject fail(String m) throws Exception { return new JSONObject().put("ok", false).put("error", m); }

    private Context launcher() {
        PunaA11yService s = PunaA11yService.instance;
        return s != null ? s : ctx;   // a bound accessibility service may start activities from the background
    }

    private boolean start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { launcher().startActivity(i); return true; }
        catch (ActivityNotFoundException e) { return false; }
        catch (SecurityException e) { return false; }
    }

    private boolean has(String perm) { return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    static boolean uiTool(String n) {
        switch (n) {
            case "open_app": case "tap": case "type_text": case "scroll": case "press": case "open_settings": case "swipe":
            case "whatsapp_message": case "send_sms": case "youtube": case "navigate": case "open_url": case "web_search": case "root_shell":
                return true;
            default: return false;
        }
    }

    JSONObject run(String name, JSONObject a) {
        try {
            JSONObject r = exec(name, a == null ? new JSONObject() : a);
            if (r.optBoolean("ok") && uiTool(name) && !"read_screen".equals(name)) {
                try { Thread.sleep("open_app".equals(name) ? 1500 : 800); } catch (InterruptedException ignored) {}
                JSONObject sc = screen(60);
                if (sc != null) r.put("screen", sc);
            }
            return r;
        } catch (Throwable e) {
            try { return fail(e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception x) { return new JSONObject(); }
        }
    }

    JSONObject screen(int max) {
        try {
            PunaA11yService s = PunaA11yService.instance;
            if (s != null) return s.readScreen(max);
            if (RootShell.enabled(ctx)) return RootShell.dumpUi(max);
        } catch (Exception ignored) {}
        return null;
    }

    private PunaA11yService a11y() throws Exception {
        PunaA11yService s = PunaA11yService.instance;
        if (s == null) throw new IllegalStateException("accessibility_off: Settings › Accessibility › Puna AI Agent ko ON karo");
        return s;
    }

    private JSONObject exec(String n, JSONObject a) throws Exception {
        switch (n) {
            case "read_screen": {
                JSONObject s = screen(a.optInt("max", 80));
                if (s == null) return fail("accessibility_off: Settings › Accessibility › Puna AI Agent ko ON karo");
                return ok().put("screen", s);
            }
            case "open_app": return openApp(a.optString("name"));
            case "list_apps": return listApps(a.optString("filter"));
            case "tap": return tap(a);
            case "type_text": return typeText(a);
            case "scroll": {
                if (PunaA11yService.instance == null && RootShell.enabled(ctx)) {
                    String d = a.optString("direction", "down");
                    return RootShell.exec(d.equals("up") ? "input swipe 540 600 540 1600 300" : "input swipe 540 1600 540 600 300");
                }
                return a11y().scroll(a.optString("direction", "down")) ? ok() : fail("scroll nahi hua");
            }
            case "swipe": {
                int x1 = a.getInt("x1"), y1 = a.getInt("y1"), x2 = a.getInt("x2"), y2 = a.getInt("y2"), ms = a.optInt("ms", 300);
                if (PunaA11yService.instance == null && RootShell.enabled(ctx)) return RootShell.exec("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
                return a11y().swipe(x1, y1, x2, y2, ms) ? ok() : fail("swipe failed");
            }
            case "press": {
                String k = a.optString("key", "back");
                if (PunaA11yService.instance == null && RootShell.enabled(ctx)) {
                    String code = k.equals("home") ? "3" : k.equals("recents") ? "187" : k.equals("enter") ? "66" : "4";
                    return RootShell.exec("input keyevent " + code);
                }
                return a11y().global(k) ? ok() : fail("press " + k + " nahi hua");
            }
            case "screenshot": {
                PunaA11yService s = PunaA11yService.instance;
                if (s != null && Build.VERSION.SDK_INT >= 28) return s.global("screenshot") ? ok().put("saved", "Pictures/Screenshots") : fail("screenshot failed");
                if (RootShell.enabled(ctx)) {
                    String f = "/sdcard/Pictures/Puna-" + System.currentTimeMillis() + ".png";
                    JSONObject r = RootShell.exec("mkdir -p /sdcard/Pictures && screencap -p " + f);
                    return r.optBoolean("ok") ? r.put("saved", f) : r;
                }
                return fail("Screenshot ke liye Accessibility (Android 9+) ya root chahiye");
            }
            case "wait": Thread.sleep(Math.min(8000, Math.max(100, a.optInt("ms", 1000)))); return ok();
            case "find_contact": return findContact(a.optString("name"));
            case "call": return call(a);
            case "send_sms": return sms(a);
            case "whatsapp_message": return whatsapp(a);
            case "set_alarm": return alarm(a);
            case "set_timer": return timer(a);
            case "flashlight": return torch(a.optBoolean("on", true));
            case "volume": return volume(a);
            case "open_settings": return settings(a.optString("page", "main"));
            case "youtube": return youtube(a.optString("query"));
            case "navigate": return navigate(a.optString("destination"), a.optString("mode", "driving"));
            case "open_url": {
                String u = a.optString("url");
                if (!u.startsWith("http")) u = "https://" + u;
                return start(new Intent(Intent.ACTION_VIEW, Uri.parse(u))) ? ok() : fail("browser nahi mila");
            }
            case "web_search":
                return start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query"))))) ? ok() : fail("browser nahi mila");
            case "root_shell": {
                if (!RootShell.enabled(ctx)) return fail("root_off: root nahi hai ya Agent settings mein 'Root use karo' band hai");
                return RootShell.exec(a.optString("command"));
            }
            default: return fail("unknown tool " + n);
        }
    }

    // ------------------------------------------------------------------ apps

    private List<ResolveInfo> launchables() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return ctx.getPackageManager().queryIntentActivities(i, 0);
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    private static final String[][] ALIASES = {
            {"settings", "com.android.settings"}, {"setting", "com.android.settings"}, {"whatsapp", "com.whatsapp"}, {"youtube", "com.google.android.youtube"},
            {"chrome", "com.android.chrome"}, {"browser", "com.android.chrome"}, {"camera", "camera"}, {"gallery", "photos"}, {"photos", "com.google.android.apps.photos"},
            {"phone", "dialer"}, {"dialer", "dialer"}, {"messages", "messaging"}, {"sms", "messaging"}, {"contacts", "contacts"}, {"clock", "deskclock"},
            {"alarm", "deskclock"}, {"calculator", "calculator"}, {"maps", "com.google.android.apps.maps"}, {"playstore", "com.android.vending"},
            {"gmail", "com.google.android.gm"}, {"telegram", "org.telegram.messenger"}, {"instagram", "com.instagram.android"}, {"files", "files"}
    };

    JSONObject openApp(String name) throws Exception {
        String want = norm(name);
        if (want.isEmpty()) return fail("app ka naam do");
        PackageManager pm = ctx.getPackageManager();
        ResolveInfo best = null;
        int bestScore = 0;
        String alias = null;
        for (String[] al : ALIASES) if (want.equals(al[0])) alias = al[1];
        for (ResolveInfo r : launchables()) {
            String label = norm(String.valueOf(r.loadLabel(pm))), pkg = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int sc = 0;
            if (label.equals(want)) sc = 5;
            else if (alias != null && pkg.equals(alias)) sc = 5;
            else if (alias != null && pkg.contains(alias)) sc = 4;
            else if (label.startsWith(want)) sc = 3;
            else if (label.contains(want) || (want.length() > 3 && want.contains(label) && label.length() > 3)) sc = 2;
            else if (pkg.contains(want)) sc = 1;
            if (sc > bestScore) { bestScore = sc; best = r; }
        }
        if (best == null) return fail("'" + name + "' naam ka app nahi mila");
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!start(i)) return fail("app nahi khula");
        return ok().put("opened", String.valueOf(best.loadLabel(pm))).put("package", best.activityInfo.packageName);
    }

    JSONObject listApps(String filter) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        JSONArray a = new JSONArray();
        String f = norm(filter);
        for (ResolveInfo r : launchables()) {
            String l = String.valueOf(r.loadLabel(pm));
            if (f.isEmpty() || norm(l).contains(f)) a.put(l);
        }
        return ok().put("apps", a);
    }

    // ------------------------------------------------------------------ UI

    private AccessibilityNodeInfo target(PunaA11yService s, JSONObject a, String textKey) {
        if (a.has("index")) return s.byIndex(a.optInt("index", -1));
        if (a.has("id") && !a.optString("id").isEmpty()) return s.findById(a.optString("id"));
        String t = a.optString(textKey, "");
        return t.isEmpty() ? null : s.findByText(t);
    }

    JSONObject tap(JSONObject a) throws Exception {
        if (a.has("x") && a.has("y")) {
            int x = a.getInt("x"), y = a.getInt("y");
            if (PunaA11yService.instance == null && RootShell.enabled(ctx)) return RootShell.exec("input tap " + x + " " + y);
            return a11y().tapXY(x, y) ? ok() : fail("tap failed");
        }
        if (PunaA11yService.instance == null && RootShell.enabled(ctx)) {
            JSONObject sc = RootShell.dumpUi(300);
            JSONArray ns = sc.optJSONArray("nodes");
            String want = a.optString("text", "").toLowerCase(Locale.ROOT);
            for (int i = 0; ns != null && i < ns.length(); i++) {
                JSONObject o = ns.getJSONObject(i);
                if (a.has("index") ? o.optInt("i") == a.optInt("index") : (o.optString("t") + " " + o.optString("d")).toLowerCase(Locale.ROOT).contains(want)) {
                    String[] xy = o.optString("b").split(",");
                    return RootShell.exec("input tap " + xy[0] + " " + xy[1]).put("tapped", o.optString("t", o.optString("d")));
                }
            }
            return fail("screen par '" + a.optString("text") + "' nahi mila");
        }
        PunaA11yService s = a11y();
        AccessibilityNodeInfo n = target(s, a, "text");
        if (n == null) return fail("screen par '" + a.optString("text", a.optString("id", String.valueOf(a.opt("index")))) + "' nahi mila");
        String lbl = PunaA11yService.label(n);
        return s.click(n) ? ok().put("tapped", lbl) : fail("tap nahi hua: " + lbl);
    }

    JSONObject typeText(JSONObject a) throws Exception {
        String text = a.optString("text");
        if (PunaA11yService.instance == null && RootShell.enabled(ctx)) {
            JSONObject r = RootShell.exec("input text " + RootShell.quoteInput(text));
            if (a.optBoolean("submit")) RootShell.exec("input keyevent 66");
            return r;
        }
        PunaA11yService s = a11y();
        AccessibilityNodeInfo n = target(s, a, "into");
        if (!s.type(n, text, a.optBoolean("append"))) return fail("koi text box nahi mila");
        if (a.optBoolean("submit") && Build.VERSION.SDK_INT >= 30) {
            AccessibilityNodeInfo f = n;
            if (f == null) {
                AccessibilityNodeInfo r = s.getRootInActiveWindow();
                if (r != null) f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (f != null) f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return ok().put("typed", text);
    }

    // ------------------------------------------------------------------ contacts, calls, messages

    JSONObject findContact(String name) throws Exception {
        if (!has(Manifest.permission.READ_CONTACTS)) return fail("needs_permission:contacts");
        JSONArray out = new JSONArray();
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + name + "%"}, null)) {
            while (c != null && c.moveToNext() && out.length() < 8) out.put(new JSONObject().put("name", c.getString(0)).put("number", c.getString(1)));
        }
        return ok().put("contacts", out);
    }

    private String resolveNumber(JSONObject a) throws Exception {
        String num = a.optString("number", "").trim();
        if (!num.isEmpty()) return num;
        String who = a.optString("contact", "").trim();
        if (who.isEmpty()) return null;
        JSONObject r = findContact(who);
        if (!r.optBoolean("ok")) throw new IllegalStateException(r.optString("error"));
        JSONArray cs = r.getJSONArray("contacts");
        if (cs.length() == 0) throw new IllegalStateException("contact '" + who + "' nahi mila");
        return cs.getJSONObject(0).getString("number");
    }

    static String waDigits(String num) {
        String d = num.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) d = d.replaceFirst("^0+", "");
        if (d.length() == 10) d = "91" + d;   // India default
        return d;
    }

    JSONObject call(JSONObject a) throws Exception {
        String num = resolveNumber(a);
        if (num == null) return fail("number ya contact ka naam do");
        boolean direct = has(Manifest.permission.CALL_PHONE);
        Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)));
        return start(i) ? ok().put("number", num).put("mode", direct ? "calling" : "dialer opened (tap call)") : fail("dialer nahi khula");
    }

    JSONObject sms(JSONObject a) throws Exception {
        String num = resolveNumber(a);
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (num == null ? "" : Uri.encode(num))));
        i.putExtra("sms_body", a.optString("text"));
        return start(i) ? ok().put("number", num).put("note", "message box mein text bhar diya; Send dabana baaki") : fail("SMS app nahi mila");
    }

    JSONObject whatsapp(JSONObject a) throws Exception {
        String text = a.optString("text");
        String num = null;
        try { num = resolveNumber(a); } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("needs_permission")) throw e;
        }
        String pkg = installed("com.whatsapp") ? "com.whatsapp" : installed("com.whatsapp.w4b") ? "com.whatsapp.w4b" : null;
        if (pkg == null) return fail("WhatsApp install nahi hai");
        if (num == null) {
            // no number: open WhatsApp; the agent continues with search -> tap the chat -> type
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null || !start(i)) return fail("WhatsApp nahi khula");
            return ok().put("note", "Contact ka number nahi mila — WhatsApp khol diya; search icon tap karke '" + a.optString("contact") + "' dhoondo, chat kholo, phir type_text aur Send.");
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + waDigits(num) + "&text=" + Uri.encode(text)));
        i.setPackage(pkg);
        return start(i) ? ok().put("note", "Chat khul gayi, message box mein text bhar diya. Bhejne ke liye 'Send' tap karna hoga (confirm ke baad).") : fail("WhatsApp chat nahi khuli");
    }

    private boolean installed(String p) {
        try { ctx.getPackageManager().getPackageInfo(p, 0); return true; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ phone controls

    JSONObject alarm(JSONObject a) throws Exception {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour", 7))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
        return start(i) ? ok().put("alarm", String.format(Locale.US, "%02d:%02d", a.optInt("hour", 7), a.optInt("minute", 0))) : fail("Clock app nahi mila");
    }

    JSONObject timer(JSONObject a) throws Exception {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, a.optInt("seconds", 60)))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
        return start(i) ? ok().put("seconds", a.optInt("seconds", 60)) : fail("Clock app nahi mila");
    }

    JSONObject torch(boolean on) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return fail("camera service nahi");
        for (String id : cm.getCameraIdList()) {
            Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(f)) { cm.setTorchMode(id, on); return ok().put("flashlight", on ? "on" : "off"); }
        }
        return fail("is phone mein flashlight nahi mili");
    }

    JSONObject volume(JSONObject a) throws Exception {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        String s = a.optString("stream", "media");
        int st = s.equals("ring") ? AudioManager.STREAM_RING : s.equals("alarm") ? AudioManager.STREAM_ALARM : s.equals("notification") ? AudioManager.STREAM_NOTIFICATION : AudioManager.STREAM_MUSIC;
        String act = a.optString("action", a.has("level") ? "set" : "up");
        int max = am.getStreamMaxVolume(st);
        try {
            switch (act) {
                case "up": am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
                case "down": am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
                case "mute": am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); break;
                case "unmute": am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); break;
                default: am.setStreamVolume(st, Math.round(max * Math.max(0, Math.min(100, a.optInt("level", 50))) / 100f), AudioManager.FLAG_SHOW_UI);
            }
        } catch (SecurityException e) { return fail("Do Not Disturb ki wajah se volume nahi badla"); }
        return ok().put("level_percent", Math.round(100f * am.getStreamVolume(st) / Math.max(1, max)));
    }

    JSONObject settings(String page) throws Exception {
        String act;
        switch (page) {
            case "wifi": act = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": act = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "internet": act = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS; break;
            case "display": act = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": act = Settings.ACTION_SOUND_SETTINGS; break;
            case "location": act = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "battery": act = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            case "apps": act = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "accessibility": act = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "date": act = Settings.ACTION_DATE_SETTINGS; break;
            case "airplane": act = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            case "hotspot": act = Settings.ACTION_WIRELESS_SETTINGS; break;
            default: act = Settings.ACTION_SETTINGS;
        }
        if (start(new Intent(act))) return ok().put("opened", page);
        return start(new Intent(Settings.ACTION_SETTINGS)) ? ok().put("opened", "main") : fail("settings nahi khuli");
    }

    JSONObject youtube(String q) throws Exception {
        if (installed("com.google.android.youtube")) {
            Intent i = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q);
            if (start(i)) return ok().put("searched", q);
        }
        return start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))) ? ok().put("searched", q) : fail("YouTube nahi khula");
    }

    JSONObject navigate(String dest, String mode) throws Exception {
        String m = mode.startsWith("walk") ? "w" : mode.startsWith("bi") ? "b" : mode.startsWith("two") || mode.startsWith("bike") ? "l" : "d";
        if (installed("com.google.android.apps.maps")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=" + m)).setPackage("com.google.android.apps.maps");
            if (start(i)) return ok().put("navigating_to", dest);
        }
        if (start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))) return ok().put("map", dest);
        return start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest)))) ? ok().put("map", dest) : fail("Maps nahi khula");
    }

    // ------------------------------------------------------------------ root

    static final class RootShell {
        static final String[] PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/debug_ramdisk/su"};
        static volatile Boolean granted;

        static boolean available() {
            for (String p : PATHS) if (new File(p).exists()) return true;
            return new File("/data/adb/magisk").exists() || new File("/data/adb/ksu").exists();
        }

        static boolean enabled(Context c) {
            return Boolean.TRUE.equals(granted) && PunaService.prefs(c).getBoolean("rootOn", false);
        }

        /** Asks su for uid 0 (Magisk/KernelSU shows its own grant prompt). */
        static JSONObject request() throws Exception {
            JSONObject r = exec0("id", 25);
            granted = r.optBoolean("ok") && r.optString("out").contains("uid=0");
            return r.put("granted", granted);
        }

        static JSONObject exec(String cmd) throws Exception { return exec0(cmd, 25); }

        private static JSONObject exec0(String cmd, int timeoutS) throws Exception {
            Process p;
            try { p = new ProcessBuilder("su").redirectErrorStream(true).start(); }
            catch (Exception e) { return fail("su nahi chala: " + e.getMessage()); }
            try (OutputStream o = p.getOutputStream()) {
                o.write((cmd + "\nexit\n").getBytes("UTF-8"));
                o.flush();
            }
            StringBuilder sb = new StringBuilder();
            Thread rd = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = br.readLine()) != null && sb.length() < 200000) sb.append(l).append('\n');
                } catch (Exception ignored) {}
            });
            rd.start();
            boolean done = p.waitFor(timeoutS, TimeUnit.SECONDS);
            if (!done) { p.destroy(); return fail("su timeout (grant prompt ka jawab nahi diya?)"); }
            rd.join(2000);
            String out = sb.toString();
            return new JSONObject().put("ok", p.exitValue() == 0).put("exit", p.exitValue()).put("out", out.length() > 4000 ? out.substring(0, 4000) + "…" : out);
        }

        static String quoteInput(String t) {
            // `input text` needs %s for spaces and escaping of shell metacharacters
            StringBuilder b = new StringBuilder("'");
            for (char c : t.toCharArray()) {
                if (c == ' ') b.append("%s");
                else if (c == '\'') b.append("'\\''");
                else b.append(c);
            }
            return b.append("'").toString();
        }

        static JSONObject dumpUi(int max) throws Exception {
            JSONObject r = exec0("uiautomator dump /sdcard/.puna_ui.xml >/dev/null 2>&1; cat /sdcard/.puna_ui.xml", 20);
            String x = r.optString("out");
            JSONArray arr = new JSONArray();
            Matcher m = Pattern.compile("<node [^>]*?text=\"([^\"]*)\"[^>]*?resource-id=\"([^\"]*)\"[^>]*?class=\"([^\"]*)\"[^>]*?package=\"([^\"]*)\"[^>]*?content-desc=\"([^\"]*)\"[^>]*?clickable=\"(true|false)\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(x);
            String app = "";
            while (m.find() && arr.length() < max) {
                String t = m.group(1), id = m.group(2), d = m.group(5);
                app = m.group(4);
                boolean click = "true".equals(m.group(6));
                if (t.isEmpty() && d.isEmpty() && !(click && !id.isEmpty())) continue;
                JSONObject o = new JSONObject().put("i", arr.length());
                if (!t.isEmpty()) o.put("t", t);
                if (!d.isEmpty()) o.put("d", d);
                if (!id.isEmpty()) o.put("id", id.contains(":id/") ? id.substring(id.indexOf(":id/") + 4) : id);
                String cls = m.group(3);
                o.put("c", cls.substring(cls.lastIndexOf('.') + 1));
                if (click) o.put("k", 1);
                int x1 = Integer.parseInt(m.group(7)), y1 = Integer.parseInt(m.group(8)), x2 = Integer.parseInt(m.group(9)), y2 = Integer.parseInt(m.group(10));
                o.put("b", ((x1 + x2) / 2) + "," + ((y1 + y2) / 2));
                arr.put(o);
            }
            return new JSONObject().put("app", app).put("nodes", arr).put("count", arr.length()).put("via", "root");
        }
    }
}
