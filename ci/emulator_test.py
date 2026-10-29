#!/usr/bin/env python3
"""On-emulator test for Bharat Voice Studio.

usage: emulator_test.py OUT_DIR APK MODE [PREV_APK]
  MODE = full  -> install, launch, then drive the real UI with adb taps (coordinates from the
                  WebView's DevTools socket; needs a debuggable APK) and check every step.
  MODE = smoke -> install (optionally over PREV_APK to test upgrades), launch, screenshot, logs.
Writes report.json, summary.txt, logcat*.txt, console.json and NN_*.png into OUT_DIR.
Never exits non-zero: the report says what passed and failed.
"""
import json, os, re, subprocess, sys, time, traceback, urllib.request

OUT, APK, MODE = sys.argv[1], sys.argv[2], sys.argv[3]
PREV = sys.argv[4] if len(sys.argv) > 4 else ""
PKG = "com.sonali.bharatvoicestudio"
os.makedirs(OUT, exist_ok=True)
steps = []
console = []


def log(msg):
    print(msg, flush=True)


def step(name, ok, detail=""):
    steps.append({"step": name, "ok": bool(ok), "detail": detail})
    log(("PASS " if ok else "FAIL ") + name + (" :: " + str(detail)[:600] if detail else ""))


def adb(*a, binary=False, timeout=120):
    r = subprocess.run(["adb", *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else (r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace"))


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


shot_n = [0]


def shot(name):
    shot_n[0] += 1
    fn = os.path.join(OUT, "%02d_%s.png" % (shot_n[0], name))
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(fn, "wb") as f:
        f.write(data)
    log("screenshot " + fn + " (%d bytes)" % len(data))
    return fn


def alive():
    pid = sh("pidof " + PKG).strip()
    act = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' | head -n 3").strip()
    return pid, act


def dump_logs(tag):
    full = adb("logcat", "-d", "-v", "time", timeout=180)
    with open(os.path.join(OUT, "logcat_%s_full.txt" % tag), "w") as f:
        f.write(full)
    keep = re.compile(r"AndroidRuntime|FATAL|chromium|Console|WebView|cr_|BVS|TextToSpeech|TTS|"
                      + PKG + r"|PackageManager|ActivityManager.*" + PKG, re.I)
    flt = "\n".join(l for l in full.splitlines() if keep.search(l))
    with open(os.path.join(OUT, "logcat_%s_filtered.txt" % tag), "w") as f:
        f.write(flt)
    return full, flt


# ------------------------------------------------------------------ DevTools
class CDP:
    def __init__(self):
        import websocket  # websocket-client
        unix = sh("cat /proc/net/unix")
        socks = sorted(set(re.findall(r"@?(webview_devtools_remote_\d+)", unix)))
        pid = sh("pidof " + PKG).strip()
        name = next((s for s in socks if s.endswith("_" + pid)), socks[0] if socks else None)
        if not name:
            raise RuntimeError("no webview_devtools_remote socket (is the APK debuggable?) unix=" + str(socks))
        adb("forward", "tcp:9222", "localabstract:" + name)
        pages = []
        for _ in range(10):
            try:
                pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=5))
                if pages:
                    break
            except Exception:
                pass
            time.sleep(1)
        page = next((p for p in pages if "index.html" in p.get("url", "")), pages[0] if pages else None)
        if not page:
            raise RuntimeError("devtools has no pages")
        self.url = page.get("url")
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=40, suppress_origin=True)
        self.i = 0
        self.call("Runtime.enable")
        self.call("Log.enable")

    def _drain(self, msg):
        m = msg.get("method")
        p = msg.get("params", {})
        if m == "Runtime.consoleAPICalled":
            console.append({"type": p.get("type"), "text": " ".join(str(a.get("value", a.get("description", ""))) for a in p.get("args", []))})
        elif m == "Runtime.exceptionThrown":
            d = p.get("exceptionDetails", {})
            console.append({"type": "EXCEPTION", "text": d.get("text", "") + " " + str(d.get("exception", {}).get("description", "")),
                            "url": d.get("url"), "line": d.get("lineNumber")})
        elif m == "Log.entryAdded":
            e = p.get("entry", {})
            console.append({"type": "log-" + str(e.get("level")), "text": e.get("text"), "url": e.get("url")})

    def call(self, method, **params):
        self.i += 1
        my = self.i
        self.ws.send(json.dumps({"id": my, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == my:
                return msg
            self._drain(msg)

    def ev(self, expr, await_promise=False):
        r = self.call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=await_promise)
        res = r.get("result", {})
        if "exceptionDetails" in res:
            raise RuntimeError("JS: " + json.dumps(res["exceptionDetails"])[:500])
        return res.get("result", {}).get("value")

    def pump(self, secs):
        end = time.time() + secs
        self.ws.settimeout(0.5)
        while time.time() < end:
            try:
                self._drain(json.loads(self.ws.recv()))
            except Exception:
                pass
        self.ws.settimeout(40)


def webview_bounds():
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    m = re.search(r'class="android\.webkit\.WebView"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return None
    return tuple(int(v) for v in m.groups())


def tap(cdp, sel, label=None):
    """Real touch: find the element via DevTools, convert to screen px, adb input tap."""
    r = cdp.ev("""(function(){var e=document.querySelector(%s); if(!e) return null;
      e.scrollIntoView({block:'center'}); var b=e.getBoundingClientRect();
      var vis = !!(e.offsetParent || getComputedStyle(e).position==='fixed');
      return {x:b.left+b.width/2, y:b.top+b.height/2, w:b.width, h:b.height, vis:vis, iw:innerWidth, ih:innerHeight};})()""" % json.dumps(sel))
    if not r or not r["vis"] or r["w"] == 0:
        raise RuntimeError("element %s not visible: %s" % (sel, r))
    time.sleep(0.6)
    r = cdp.ev("""(function(){var b=document.querySelector(%s).getBoundingClientRect();
      return {x:b.left+b.width/2, y:b.top+b.height/2, iw:innerWidth, ih:innerHeight};})()""" % json.dumps(sel))
    wb = webview_bounds()
    if not wb:
        raise RuntimeError("WebView not found in uiautomator dump")
    scale = (wb[2] - wb[0]) / r["iw"]
    x, y = int(wb[0] + r["x"] * scale), int(wb[1] + r["y"] * scale)
    adb("shell", "input", "tap", str(x), str(y))
    log("tap %s -> (%d,%d) webview=%s scale=%.3f" % (label or sel, x, y, wb, scale))
    time.sleep(1.2)


def type_text(text):
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.6)


def visible_views(cdp):
    return cdp.ev("['landing','auth','app'].filter(function(v){var e=document.getElementById('view-'+v);return e&&!e.hidden&&e.offsetHeight>0})")


# ------------------------------------------------------------------ run
def main():
    adb("logcat", "-c")
    log("device: " + sh("getprop ro.build.version.release").strip() + " / API " + sh("getprop ro.build.version.sdk").strip())
    if PREV:
        out = adb("install", PREV, timeout=300)
        step("install previous APK " + os.path.basename(PREV), "Success" in out, out.strip()[-300:])
        out = adb("install", "-r", APK, timeout=300)
        step("upgrade install over previous APK (no uninstall)", "Success" in out, out.strip()[-300:])
        if "Success" not in out:
            adb("uninstall", PKG)
            out = adb("install", APK, timeout=300)
            step("fresh install after uninstall", "Success" in out, out.strip()[-300:])
    else:
        adb("uninstall", PKG)
        out = adb("install", APK, timeout=300)
        step("install " + os.path.basename(APK), "Success" in out, out.strip()[-300:])

    out = sh("am start -W -n %s/.MainActivity" % PKG, timeout=90)
    step("launch MainActivity", "Error" not in out, out.strip()[-300:])
    time.sleep(25)
    shot("launch_after_25s")
    pid, act = alive()
    step("app process alive 25s after launch", bool(pid) and PKG in act, "pid=%s | %s" % (pid, act))
    full, flt = dump_logs("launch")
    crash = re.findall(r"FATAL EXCEPTION.*?(?:\n.*?){0,25}", full)
    step("no FATAL EXCEPTION in logcat", not crash, crash[0][:1500] if crash else "")

    if MODE == "full":
        try:
            cdp = CDP()
            step("DevTools attached", True, cdp.url)
            st = cdp.ev("""JSON.stringify({views:['landing','auth','app'].filter(function(v){var e=document.getElementById('view-'+v);return e&&!e.hidden}),
              sheets:[].map.call(document.styleSheets,function(s){var n=-1;try{n=s.cssRules.length}catch(e){};return (s.href||'inline').split('/').pop()+':'+n}),
              textPrimaryVar:getComputedStyle(document.documentElement).getPropertyValue('--text-primary'),
              bodyBg:getComputedStyle(document.body).backgroundColor, bodyFont:getComputedStyle(document.body).fontFamily,
              shim:!!window.__bvsShim, bridge:!!window.AndroidBridge, ttsVoices:(window.speechSynthesis&&speechSynthesis.getVoices().length),
              ready:document.readyState, title:document.title, w:innerWidth, h:innerHeight, scrollH:document.documentElement.scrollHeight})""")
            st = json.loads(st)
            step("page state", True, st)
            step("landing view is the visible view", st["views"] == ["landing"], st["views"])
            step("design stylesheet hark.css loaded (CSS variables defined)", bool(st["textPrimaryVar"].strip()),
                 "--text-primary=%r sheets=%s" % (st["textPrimaryVar"], st["sheets"]))
            step("native speech shim injected", st["shim"] and st["bridge"], "")

            # --- sign up with email (real taps + real keyboard)
            try:
                tap(cdp, '.x-lnav [data-auth="signup"]', "Sign up (nav)")
                v = visible_views(cdp)
                step("tap Sign up opens the auth screen", v == ["auth"], v)
                shot("auth_signup")
                tap(cdp, "#authName"); type_text("Sonali")
                tap(cdp, "#authEmail"); type_text("sonali@example.com")
                tap(cdp, "#authPw"); type_text("test12345")
                vals = cdp.ev("[authName.value,authEmail.value,authPw.value.length].join('|')")
                step("typing into sign-up fields works", vals == "Sonali|sonali@example.com|9", vals)
                adb("shell", "input", "keyevent", "4")  # BACK closes the keyboard
                time.sleep(1.5)
                v = visible_views(cdp)
                if v != ["auth"]:
                    step("BACK (closing keyboard) kept the auth screen", False, v)
                tap(cdp, "#authSubmit", "Create account")
                v = visible_views(cdp)
                greet = cdp.ev("(document.getElementById('greet')||{}).textContent")
                step("Create account enters the dashboard", v == ["app"] and "Sonali" in (greet or ""), "%s greet=%r" % (v, greet))
                shot("dashboard_after_signup")
            except Exception as e:
                step("email sign-up flow", False, repr(e))
                shot("signup_failure")

            # --- Google demo login
            try:
                cdp.ev("document.getElementById('profLogout').click()")
                time.sleep(1)
                tap(cdp, '.x-lnav [data-auth="login"]', "Log in (nav)")
                tap(cdp, "#authGoogle", "Continue with Google")
                g = cdp.ev("!document.getElementById('authGStep').hidden")
                step("Google button shows demo-login name step", g, "")
                tap(cdp, "#authGName"); type_text("Sonali")
                tap(cdp, "#authGGo", "Continue")
                v = visible_views(cdp)
                step("Google demo login enters the dashboard", v == ["app"], v)
            except Exception as e:
                step("Google demo login flow", False, repr(e))
                shot("google_login_failure")

            # --- Try demo from landing
            try:
                cdp.ev("document.getElementById('profLogout').click()")
                time.sleep(1)
                tap(cdp, "#tryDemo", "Try demo")
                v = visible_views(cdp)
                step("Try demo enters the dashboard", v == ["app"], v)
            except Exception as e:
                step("Try demo flow", False, repr(e))

            # --- Free translate
            try:
                tap(cdp, "#tab-tr", "Speech to Text tab")
                sel = cdp.ev("JSON.stringify({sel:document.getElementById('tab-tr').getAttribute('aria-selected'), free:document.querySelector('.x-app').classList.contains('is-free'), src:srcLang.value, tgt:tgtLang.value, frVisible:!!document.getElementById('frGo').offsetParent})")
                step("Speech to Text tab opens with Free engine and Free translate visible", '"frVisible":true' in sel and '"sel":"true"' in sel, sel)
                cdp.ev("""(function(){var t=document.getElementById('tgtLang'); var o=[].filter.call(t.options,function(o){return /^en/i.test(o.value)})[0];
                    if(o){t.value=o.value;t.dispatchEvent(new Event('change',{bubbles:true}));}
                    var a=document.getElementById('frText'); a.value='नमस्ते, आप कैसे हैं? मेरा नाम सोनाली है।'; a.dispatchEvent(new Event('input',{bubbles:true}));})()""")
                tap(cdp, "#frGo", "Free translate button")
                out, err = "", ""
                for _ in range(40):
                    out = cdp.ev("document.getElementById('frOut').textContent") or ""
                    err = cdp.ev("(function(){var e=document.getElementById('frErr');return e.hidden?'':e.textContent})()") or ""
                    if out.strip() or err.strip():
                        break
                    time.sleep(1)
                step("Free translate returns text", bool(out.strip()) and not err.strip(), "out=%r err=%r" % (out, err))
                shot("free_translate_result")
                tap(cdp, "#frSpeak", "Sunao (speak)")
                cdp.pump(6)
                err = cdp.ev("(function(){var e=document.getElementById('frErr');return e.hidden?'':e.textContent})()") or ""
                vs = cdp.ev("speechSynthesis.getVoices().length")
                step("Sunao (TTS) raised no page error", not err.strip(), "frErr=%r voices=%s" % (err, vs))
                shot("after_speak")
            except Exception as e:
                step("free translate flow", False, repr(e))
                shot("translate_failure")

            cdp.pump(2)
        except Exception as e:
            step("DevTools-driven UI test", False, traceback.format_exc()[-1500:])

    time.sleep(2)
    pid, act = alive()
    step("app still alive at end", bool(pid), "pid=%s | %s" % (pid, act))
    shot("final")
    full, flt = dump_logs("end")
    jserr = [l for l in flt.splitlines() if re.search(r"Uncaught|TypeError|ReferenceError|SyntaxError|net::ERR|Not allowed to load|FATAL", l)]
    with open(os.path.join(OUT, "console.json"), "w") as f:
        json.dump(console, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "dumpsys_activity_top.txt"), "w") as f:
        f.write(sh("dumpsys activity top | head -n 60"))
    rep = {"mode": MODE, "apk": os.path.basename(APK), "steps": steps, "js_error_lines": jserr[:80]}
    with open(os.path.join(OUT, "report.json"), "w") as f:
        json.dump(rep, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        for s in steps:
            f.write(("PASS " if s["ok"] else "FAIL ") + s["step"] + " :: " + str(s["detail"])[:800] + "\n")
        f.write("\n--- JS/console error lines from logcat ---\n" + "\n".join(jserr[:80]) + "\n")
        f.write("\n--- console events via DevTools ---\n" + "\n".join("%s: %s" % (c["type"], c["text"]) for c in console[:120]) + "\n")


try:
    main()
except Exception:
    step("test harness", False, traceback.format_exc()[-1500:])
    with open(os.path.join(OUT, "summary.txt"), "a") as f:
        f.write(traceback.format_exc())
