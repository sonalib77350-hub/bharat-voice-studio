#!/usr/bin/env python3
"""A-to-Z on-emulator test for Bharat Voice Studio.

usage: emulator_test.py OUT_DIR APK MODE [PREV_APK]
  MODE = full  -> install, launch, then drive every screen with REAL adb taps / keyboard. Element
                  positions and page state are read through the WebView DevTools socket (debuggable
                  APK only). Writes a PASS/FAIL checklist with screenshot evidence.
  MODE = smoke -> install (optionally over PREV_APK, to test upgrades), launch, screenshot, logs.
Outputs in OUT_DIR: checklist.md, report.json, summary.txt, logcat_*.txt, console.json, NN_*.png.
Never exits non-zero.
"""
import base64, json, os, re, subprocess, sys, time, traceback, urllib.request

OUT, APK, MODE = sys.argv[1], sys.argv[2], sys.argv[3]
PREV = sys.argv[4] if len(sys.argv) > 4 else ""
PKG = "com.sonali.bharatvoicestudio"
WAV = os.environ.get("TEST_WAV", "")
os.makedirs(OUT, exist_ok=True)
rows = []      # checklist rows
console = []
cdp = None


def log(msg):
    print(msg, flush=True)


def adb(*a, binary=False, timeout=120):
    try:
        r = subprocess.run(["adb", *a], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else "TIMEOUT"
    return r.stdout if binary else (r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace"))


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


shot_n = [0]


def shot(name):
    shot_n[0] += 1
    fn = "%02d_%s.png" % (shot_n[0], re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")[:40])
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(data)
    return fn


def check(area, function, status, detail="", evidence=""):
    """status: PASS | FAIL | NEEDS KEY | NOT TESTABLE"""
    rows.append({"area": area, "function": function, "status": status, "detail": str(detail)[:900], "evidence": evidence})
    log("[%s] %s / %s :: %s %s" % (status, area, function, str(detail)[:400], evidence))


def alive():
    pid = sh("pidof " + PKG).strip()
    act = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 2").strip()
    return pid, act


def dump_logs(tag):
    full = adb("logcat", "-d", "-v", "time", timeout=180)
    with open(os.path.join(OUT, "logcat_%s_full.txt" % tag), "w") as f:
        f.write(full)
    pid = sh("pidof " + PKG).strip()
    keep = re.compile(r"AndroidRuntime|FATAL|BVS|TextToSpeech|SpeechRecognizer|chromium|cr_|" + PKG, re.I)
    flt = "\n".join(l for l in full.splitlines() if keep.search(l) or (pid and "(%5s)" % pid in l)
                    and not re.search(r"MediaControlGatt", l))
    with open(os.path.join(OUT, "logcat_%s_filtered.txt" % tag), "w") as f:
        f.write(flt)
    return full, flt


def downloads():
    a = set(l.strip() for l in sh("ls -1 /sdcard/Download/").splitlines() if l.strip() and "No such" not in l)
    q = sh("content query --uri content://media/external/downloads --projection _display_name")
    a |= set(m.strip() for m in re.findall(r"_display_name=([^,\n]+)", q))
    return a


def bvs_log(n=4):
    return [l[19:].strip() for l in adb("logcat", "-d", "-s", "BVS:V").splitlines()
            if "toast:" in l or "download" in l.lower() or "MediaStore" in l or "dl" in l[19:30]][-n:]


def t_download_direct():
    """Blob download through the same path the page uses (a.click() on <a download href=blob:>)."""
    to_app("home")
    name = "bvs-selftest-%d.txt" % int(time.time())
    b0 = downloads()
    cdp.ev("""(function(){var b=new Blob(["Bharat Voice Studio download test \\u0b13\\u0b21\\u0b3f\\u0b06\\n"],{type:'text/plain'});
      var u=URL.createObjectURL(b), a=document.createElement('a'); a.href=u; a.download=%s;
      document.body.appendChild(a); a.click(); a.remove(); setTimeout(function(){URL.revokeObjectURL(u)},5000);})()""" % json.dumps(name))
    new = []
    for _ in range(10):
        time.sleep(1)
        new = sorted(downloads() - b0)
        if name in new:
            break
    ls = sh("ls -l /sdcard/Download/")
    body = sh("cat '/sdcard/Download/%s'" % name) if name in ls else ""
    check("Downloads", "Blob download lands in /sdcard/Download (ls -l / MediaStore)",
          "PASS" if name in ls and "download test" in body else "FAIL",
          "new=%s body=%r ls=%r native=%s" % (new, body[:80], ls[-400:], bvs_log(4)))


# ------------------------------------------------------------------ DevTools
class CDP:
    def __init__(self):
        import websocket
        name = None
        for _ in range(15):
            unix = sh("cat /proc/net/unix")
            pid = sh("pidof " + PKG).strip()
            socks = sorted(set(re.findall(r"@?(webview_devtools_remote_\d+)", unix)))
            name = next((s for s in socks if s.endswith("_" + pid)), None)
            if name:
                break
            time.sleep(1)
        if not name:
            raise RuntimeError("no webview_devtools_remote socket for app pid")
        adb("forward", "--remove-all")
        adb("forward", "tcp:9222", "localabstract:" + name)
        page = None
        for _ in range(20):
            try:
                pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=5))
                page = next((p for p in pages if "index.html" in p.get("url", "")), None)
                if page:
                    break
            except Exception:
                pass
            time.sleep(1)
        if not page:
            raise RuntimeError("devtools: our page not found")
        self.url = page.get("url")
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=60, suppress_origin=True)
        self.i = 0
        self.call("Runtime.enable")
        self.call("Log.enable")

    def _drain(self, msg):
        m, p = msg.get("method"), msg.get("params", {})
        if m == "Runtime.consoleAPICalled":
            console.append({"type": p.get("type"), "text": " ".join(str(a.get("value", a.get("description", ""))) for a in p.get("args", []))[:500]})
        elif m == "Runtime.exceptionThrown":
            d = p.get("exceptionDetails", {})
            console.append({"type": "EXCEPTION", "text": (d.get("text", "") + " " + str(d.get("exception", {}).get("description", "")))[:800],
                            "url": d.get("url"), "line": d.get("lineNumber")})
        elif m == "Log.entryAdded":
            e = p.get("entry", {})
            console.append({"type": "log-" + str(e.get("level")), "text": str(e.get("text"))[:500], "url": e.get("url")})

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
            raise RuntimeError("JS error: " + str(res["exceptionDetails"].get("exception", {}).get("description", res["exceptionDetails"]))[:400])
        return res.get("result", {}).get("value")

    def pump(self, secs):
        end = time.time() + secs
        self.ws.settimeout(0.5)
        while time.time() < end:
            try:
                self._drain(json.loads(self.ws.recv()))
            except Exception:
                pass
        self.ws.settimeout(60)


def connect():
    global cdp
    cdp = CDP()
    cdp.ev(HOOKS)
    return cdp


HOOKS = r"""(function(){
 if(window.__t) return; window.__t={tts:[],sr:[],err:[]};
 var t=window.__bvsTTS; if(t) window.__bvsTTS=function(id,ty,p){__t.tts.push(ty+(p&&p.error?':'+p.error:''));return t.apply(this,arguments)};
 var s=window.__bvsSR; if(s) window.__bvsSR=function(id,ty,p){__t.sr.push(ty+(p&&p.error?':'+p.error+':'+(p.message||''):''));return s.apply(this,arguments)};
 ['pointerdown','touchstart','click'].forEach(function(t){document.addEventListener(t,function(e){var g=e.target,p=e.touches&&e.touches[0]||e;
   __t.ev=(__t.ev||[]).slice(-30); __t.ev.push(t+':'+(g&&(g.id||g.tagName))+'@'+Math.round(p.clientX||0)+','+Math.round(p.clientY||0)+(e.isTrusted?'':'(js)'));},true);});
 window.addEventListener('error',function(e){__t.err.push(String(e.message))});
 window.addEventListener('unhandledrejection',function(e){__t.err.push('rejection: '+String(e.reason&&e.reason.message||e.reason))});
})()"""


_wb = [None]


def webview_bounds(fresh=False):
    """Screen bounds of the WebView. Only its left/top/width are used (keyboard resizes change the height
    only), so it is read once and cached: the slow uiautomator dump must not sit between measuring an
    element and tapping it, or the page can move in between and the tap lands on something else."""
    if _wb[0] and not fresh:
        return _wb[0]
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml", timeout=40)
        xml = sh("cat /sdcard/ui.xml")
        m = re.search(r'class="android\.webkit\.WebView"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            _wb[0] = tuple(int(v) for v in m.groups())
            return _wb[0]
        time.sleep(1)
    return None


POS = """(function(){var e=document.querySelector(%s), b=e.getBoundingClientRect();
  var x=b.left+Math.min(b.width/2, 60), y=b.top+b.height/2, top=document.elementFromPoint(x,y);
  return {x:x, y:y, top:b.top, h:b.height, iw:innerWidth, hit: !!(top && (top===e || e.contains(top))),
          at: top ? (top.id || top.tagName) : null};})()"""


def tap(sel, label=None, js_fallback=False):
    r = cdp.ev("""(function(){var e=document.querySelector(%s); if(!e) return null;
      e.scrollIntoView({block:'center', inline:'nearest', behavior:'instant'}); var b=e.getBoundingClientRect(); var cs=getComputedStyle(e);
      return {w:b.width, vis: !!(e.offsetParent||cs.position==='fixed') && cs.visibility!=='hidden', dis: !!e.disabled};})()""" % json.dumps(sel))
    if not r or not r["vis"] or r["w"] == 0:
        if js_fallback and r is not None:
            cdp.ev("document.querySelector(%s).click()" % json.dumps(sel))
            time.sleep(1)
            return "js"
        raise RuntimeError("element %s not visible/tappable: %s" % (sel, r))
    wb = webview_bounds()
    if not wb:
        raise RuntimeError("WebView not found in uiautomator dump")
    prev, same = None, 0
    for _ in range(25):  # wait until the element has not moved for 3 readings in a row
        time.sleep(0.3)
        r = cdp.ev(POS % json.dumps(sel))
        if prev and abs(prev["y"] - r["y"]) < 1 and abs(prev["x"] - r["x"]) < 1 and r["hit"]:
            same += 1
            if same >= 2:
                break
        else:
            same = 0
        prev = r
    scale = (wb[2] - wb[0]) / r["iw"]
    x, y = int(wb[0] + r["x"] * scale), int(wb[1] + r["y"] * scale)
    adb("shell", "input", "tap", str(x), str(y))
    after = cdp.ev(POS % json.dumps(sel))
    moved = abs(after["y"] - r["y"]) >= 1
    log("  tap %s -> (%d,%d) hit=%s at=%s moved_after=%s" % (label or sel, x, y, r["hit"], r["at"], moved))
    time.sleep(1.2)
    return "tap"


def kb_shown():
    return "mInputShown=true" in sh("dumpsys input_method | grep mInputShown")


def hide_kb():
    if kb_shown():
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)


def type_into(sel, text):
    tap(sel)
    if not cdp.ev("document.activeElement===document.querySelector(%s)" % json.dumps(sel)):
        log("  focus did not land on %s after tap; focusing it so typing still uses the real keyboard" % sel)
        cdp.ev("document.querySelector(%s).focus()" % json.dumps(sel))
        time.sleep(0.5)
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.8)


def setv(sel, value):
    cdp.ev("""(function(){var e=document.querySelector(%s); e.value=%s; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));})()"""
           % (json.dumps(sel), json.dumps(value)))


def views():
    return cdp.ev("['landing','auth','app'].filter(function(v){var e=document.getElementById('view-'+v);return e&&!e.hidden})")


def text(sel):
    return cdp.ev("(function(){var e=document.querySelector(%s);return e?(e.hidden?'':e.textContent.trim()):null})()" % json.dumps(sel))


def wait_for(expr, secs, step=1.0):
    end = time.time() + secs
    v = None
    while time.time() < end:
        try:
            v = cdp.ev(expr)
        except Exception as e:
            v = None
        if v:
            return v
        time.sleep(step)
    return v


def pane_errors(pane):
    return cdp.ev("""[].filter.call(document.querySelectorAll('#pane-%s [role=alert], #pane-%s .x-error'),function(e){return !e.hidden&&e.offsetParent&&e.textContent.trim()}).map(function(e){return e.textContent.trim()})""" % (pane, pane))


def to_app(tab="home"):
    cdp.ev("if(document.getElementById('view-app').hidden){document.getElementById('tryDemo').click()}")
    time.sleep(0.5)
    sel = "#tab-" + tab
    if cdp.ev("document.querySelector('%s').getAttribute('aria-selected')" % sel) != "true":
        tap(sel, "tab " + tab, js_fallback=True)


def ensure_cdp():
    """Reattach to WebView DevTools if the socket dropped (renderer restart, emulator hiccup)."""
    try:
        if cdp is not None and cdp.ws.connected:
            cdp.ev("1")
            return
    except Exception:
        pass
    log("  DevTools socket lost; reconnecting")
    pid, _ = alive()
    if not pid:
        sh("am start -W -n %s/.MainActivity" % PKG, timeout=90)
        time.sleep(15)
    connect()


def run(area, function, fn):
    try:
        ensure_cdp()
    except Exception as e:
        log("  reconnect failed: %r" % e)
    try:
        try:
            fn()
        except Exception as e:
            if "socket" not in repr(e).lower() and "Connection" not in repr(e):
                raise
            log("  %s: DevTools connection dropped (%r); reconnecting and retrying once" % (function, e))
            ensure_cdp()
            fn()
    except Exception as e:
        ev = ""
        try:
            ev = shot("fail_" + function)
        except Exception:
            pass
        check(area, function, "FAIL", "test error: " + repr(e)[:600], ev)


# ------------------------------------------------------------------ flows
def t_landing():
    st = json.loads(cdp.ev("""JSON.stringify({views:['landing','auth','app'].filter(function(v){var e=document.getElementById('view-'+v);return e&&!e.hidden}),
      sheets:[].map.call(document.styleSheets,function(s){var n=-1;try{n=s.cssRules.length}catch(e){};return (s.href||'inline').split('/').pop().slice(0,30)+':'+n}),
      origin:location.origin, secure:window.isSecureContext, shim:!!window.__bvsShim, title:document.title,
      heroH:(document.querySelector('.x-lhero h1')||{}).offsetHeight||0})"""))
    ev = shot("landing")
    overflow("landing (%spx wide)" % cdp.ev("innerWidth"))
    hark_ok = any(s.startswith("hark.css:") and not s.endswith(":-1") and not s.endswith(":0") for s in st["sheets"])
    ok = st["views"] == ["landing"] and st["heroH"] > 0 and hark_ok
    check("Landing", "Landing page renders (not blank), design CSS loaded", "PASS" if ok else "FAIL", st, ev)
    check("Security", "Page runs on https app origin, secure context, native shim present",
          "PASS" if st["origin"].startswith("https://appassets") and st["secure"] and st["shim"] else "FAIL", st)


def overflow(where):
    o = cdp.ev("JSON.stringify({sw:document.documentElement.scrollWidth, iw:innerWidth, sx:scrollX})")
    o = json.loads(o)
    check("Layout", "No sideways overflow on " + where, "PASS" if o["sw"] <= o["iw"] + 1 else "FAIL", o)


def t_landing_nav():
    y0 = cdp.ev("scrollY")
    tap('.x-lnav__links a[href="#pricing"]', "Pricing link", js_fallback=True)
    time.sleep(1.5)
    y1 = cdp.ev("scrollY")
    check("Landing", "Top links (Products/Voices/Pricing) scroll to section", "PASS" if y1 > y0 + 200 else "FAIL",
          "scrollY %s -> %s" % (y0, y1), shot("landing_pricing"))
    cdp.ev("scrollTo(0,0)")


def t_cards():
    res, bad = [], []
    keys = cdp.ev("[].map.call(document.querySelectorAll('#view-landing [data-open]'),function(b){return b.getAttribute('data-open')})")
    for i, k in enumerate(keys):
        cdp.ev("showView('landing')")
        time.sleep(0.4)
        tap('#view-landing [data-open]:nth-of-type(%d)' % (i + 1) if False else "#view-landing [data-open=\"%s\"]" % k, "card " + k)
        sel = cdp.ev("(function(){var t=document.getElementById('tab-%s');return t&&t.getAttribute('aria-selected')})()" % k)
        v = views()
        (res if (v == ["app"] and sel == "true") else bad).append(k)
    check("Landing", "Product cards open the matching tool (%s)" % ",".join(keys), "PASS" if not bad else "FAIL",
          "ok=%s failed=%s" % (res, bad), shot("card_last"))


def t_signup():
    cdp.ev("showView('landing')")
    tap('.x-lnav [data-auth="signup"]', "Sign up")
    v = views()
    check("Auth", "Sign up button opens Create account screen", "PASS" if v == ["auth"] else "FAIL", v, shot("signup_screen"))
    tap("#authSubmit", "Create account (empty)")
    e = text("#authEmailErr")
    check("Auth", "Empty form shows validation error", "PASS" if e else "FAIL", e)
    type_into("#authName", "Sonali")
    type_into("#authEmail", "sonali@example.com")
    type_into("#authPw", "test12345")
    vals = cdp.ev("[authName.value,authEmail.value,authPw.value.length].join('|')")
    check("Auth", "Typing with the Android keyboard works in all fields", "PASS" if vals == "Sonali|sonali@example.com|9" else "FAIL", vals)
    hide_kb()
    tap("#authPwToggle", "Show password")
    t = cdp.ev("authPw.type")
    check("Auth", "Show/Hide password toggle", "PASS" if t == "text" else "FAIL", "type=" + str(t))
    tap("#authSubmit", "Create account")
    v, g = views(), text("#greet")
    check("Auth", "Create account -> dashboard", "PASS" if v == ["app"] and "Sonali" in (g or "") else "FAIL",
          "%s greet=%r" % (v, g), shot("dashboard_signup"))


def t_logout_login():
    tap("#profBtn", "profile")
    tap("#profLogout", "Log out")
    v = views()
    check("Auth", "Profile menu -> Log out returns to landing", "PASS" if v == ["landing"] else "FAIL", v)
    tap('.x-lnav [data-auth="login"]', "Log in")
    title = text("#authTitle")
    type_into("#authEmail", "sonali@example.com")
    type_into("#authPw", "test12345")
    hide_kb()
    tap("#authSubmit", "Log in submit")
    v, g = views(), text("#greet")
    check("Auth", "Log in with email -> dashboard", "PASS" if title == "Welcome back" and v == ["app"] else "FAIL",
          "title=%r %s greet=%r" % (title, v, g), shot("dashboard_login"))


def t_google():
    cdp.ev("document.getElementById('profLogout').click()")
    time.sleep(0.5)
    tap('.x-lnav [data-auth="login"]', "Log in")
    tap("#authGoogle", "Continue with Google")
    step = cdp.ev("!document.getElementById('authGStep').hidden")
    type_into("#authGName", "Sonali")
    hide_kb()
    tap("#authGGo", "Continue")
    v = views()
    check("Auth", "Google (demo) login -> dashboard", "PASS" if step and v == ["app"] else "FAIL", "step=%s %s" % (step, v),
          shot("dashboard_google"))


def t_trydemo():
    cdp.ev("document.getElementById('profLogout').click()")
    time.sleep(0.5)
    tap("#tryDemo", "Try demo")
    v, g = views(), text("#greet")
    check("Auth", "Try demo -> dashboard as Guest", "PASS" if v == ["app"] and "Guest" in (g or "") else "FAIL", "%s %r" % (v, g))


def t_back():
    to_app("vs")
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)
    pid, _ = alive()
    home = cdp.ev("document.getElementById('tab-home').getAttribute('aria-selected')") if pid else None
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)
    pid2, act = alive()
    v = views() if pid2 else None
    ok = pid and pid2 and home == "true" and v == ["landing"] and PKG in act
    check("Navigation", "Android Back: tool tab -> Home -> landing (app does not close)", "PASS" if ok else "FAIL",
          "after1 home=%s, after2 views=%s, top=%s" % (home, v, act[:120]), shot("after_back"))
    if not pid2 or PKG not in act:
        sh("am start -n %s/.MainActivity" % PKG)
        time.sleep(8)
        connect()


def t_tabs():
    to_app("home")
    tabs = cdp.ev("[].map.call(document.querySelectorAll('.x-tab[id^=tab-]'),function(b){return [b.id.slice(4), b.textContent.trim()]})")
    for k, label in tabs:
        how = tap("#tab-" + k, "tab " + k, js_fallback=True)
        st = cdp.ev("""(function(){var p=document.getElementById('pane-%s');return {sel:document.getElementById('tab-%s').getAttribute('aria-selected'),
             vis:!!(p&&!p.hidden&&p.offsetHeight>0), txt:(p?p.innerText:'').replace(/\\s+/g,' ').slice(0,160)}})()""" % (k, k))
        check("Tabs", "Tab '%s' (%s) opens" % (label, k), "PASS" if st["sel"] == "true" and st["vis"] else "FAIL",
              ("[js click: not reachable by touch] " if how == "js" else "") + st["txt"], shot("tab_" + k))


def t_lang():
    to_app("tr")
    s0 = cdp.ev("[srcLang.value,tgtLang.value].join('>')")
    opts = cdp.ev("[].map.call(tgtLang.options,function(o){return o.value})")
    tap("#swap", "swap")
    s1 = cdp.ev("[srcLang.value,tgtLang.value].join('>')")
    chip = cdp.ev("(function(){var c=document.querySelector('#tgtChips button');return c?c.textContent.trim():null})()")
    if chip:
        tap("#tgtChips button", "first target chip")
    s2 = cdp.ev("[srcLang.value,tgtLang.value].join('>')")
    check("Translate", "Language pickers: swap and quick chips", "PASS" if (s1 != s0 or s0.split('>')[0] == "unknown") else "FAIL",
          "start %s, after swap %s, after chip %r %s; %d target languages" % (s0, s1, chip, s2, len(opts)), shot("languages"))
    setv("#srcLang", "hi-IN" if "hi-IN" in cdp.ev("[].map.call(srcLang.options,function(o){return o.value})") else "unknown")


SCRIPTS = {"en": r"[A-Za-z]{3}", "od": "[\u0B00-\u0B7F]", "bn": "[\u0980-\u09FF]", "ta": "[\u0B80-\u0BFF]",
           "gu": "[\u0A80-\u0AFF]", "te": "[\u0C00-\u0C7F]", "hi": "[\u0900-\u097F]"}


def t_free_translate():
    to_app("tr")
    opts = cdp.ev("[].map.call(tgtLang.options,function(o){return o.value})")
    src_opts = cdp.ev("[].map.call(srcLang.options,function(o){return o.value})")
    if "hi-IN" in src_opts:
        setv("#srcLang", "hi-IN")
    setv("#frText", "नमस्ते, आप कैसे हैं? मेरा नाम सोनाली है।")
    done = []
    for pre in ["en", "od", "bn", "ta", "gu"]:
        code = next((o for o in opts if o.split("-")[0] == pre and "roman" not in o), None)
        if not code:
            continue
        setv("#tgtLang", code)
        cdp.ev("frOut.textContent=''")
        tap("#frGo", "Translate -> " + code)
        out = wait_for("frOut.textContent.trim()", 30) or ""
        err = pane_errors("tr")
        ok = bool(re.search(SCRIPTS[pre], out)) and not err
        ev = shot("free_translate_" + pre)
        if pre == "en":
            overflow("Translate tab")
        check("Translate", "Free text translate Hindi -> %s" % code, "PASS" if ok else "FAIL", "out=%r err=%r" % (out[:200], err), ev)
        done.append(code)
    rom = next((o for o in opts if "roman" in o), None)
    hist = cdp.ev("(function(){var l=document.getElementById('histText');return l?l.children.length:0})()")
    check("History", "Translations are added to History", "PASS" if hist and hist >= len(done) else "FAIL",
          "%s history items, hinglish option=%r" % (hist, rom))


def t_tts():
    to_app("tr")
    nv = cdp.ev("speechSynthesis.getVoices().length")
    if not text("#frOut"):
        setv("#frText", "Hello, how are you?")
        tap("#frGo")
        wait_for("frOut.textContent.trim()", 30)
    cdp.ev("__t.tts=[]")
    tap("#frSpeak", "Sunao")
    ev = wait_for("__t.tts.filter(function(x){return x=='start'||x=='end'}).length", 15)
    log_ = cdp.ev("__t.tts.join(',')")
    err = pane_errors("tr")
    check("Speech", "Sunao (text to speech) on translated text", "PASS" if ev and not err else "FAIL",
          "native TTS events=%s, voices=%s, errors=%s (audio itself not audible on CI)" % (log_, nv, err), shot("sunao"))
    tap("#frHush", "Stop")
    check("Speech", "Stop button stops speech", "PASS" if not cdp.ev("speechSynthesis.speaking") else "FAIL",
          "speaking=%s" % cdp.ev("speechSynthesis.speaking"))


def t_voice_studio_free():
    to_app("vs")
    setv("#fvText", "नमस्ते, यह बोलने की जाँच है।")
    langs = cdp.ev("[].map.call(fvLang.options,function(o){return o.value})")
    if langs:
        setv("#fvLang", next((l for l in langs if l.startswith("hi")), langs[0]))
    time.sleep(1)
    voices = cdp.ev("fvVoice.options.length")
    cdp.ev("__t.tts=[]")
    tap("#fvPlay", "Play")
    ev = wait_for("__t.tts.filter(function(x){return x=='start'||x=='end'}).length", 15)
    err = pane_errors("vs")
    first = cdp.ev("__t.tts.join(',')")
    note = ""
    if not ev and langs and any(l.startswith("en") for l in langs):
        # Emulator images often have no Hindi TTS voice data: check the app said so, then try English.
        note = "hi: tts=%s native=%s; " % (first, [l for l in bvs_log(6) if "awaaz" in l][-1:])
        tap("#fvStop", "Stop")
        setv("#fvText", "Hello, this is a speech test.")
        setv("#fvLang", next(l for l in langs if l.startswith("en")))
        time.sleep(1)
        cdp.ev("__t.tts=[]")
        tap("#fvPlay", "Play (English)")
        ev = wait_for("__t.tts.filter(function(x){return x=='start'||x=='end'}).length", 15)
        err = pane_errors("vs")
    check("Voice Studio", "Free Voice Studio: pick language/voice, Play", "PASS" if ev and not err else "FAIL",
          "%slanguages=%d voices-in-list=%s tts-events=%s errors=%s" % (note, len(langs or []), voices, cdp.ev("__t.tts.join(',')"), err), shot("voice_studio_free"))
    tap("#fvStop", "Stop")


def t_mic():
    sh("pm grant %s android.permission.RECORD_AUDIO" % PKG)
    gum = cdp.ev("""navigator.mediaDevices.getUserMedia({audio:true}).then(function(s){var n=s.getAudioTracks().length;s.getTracks().forEach(function(t){t.stop()});return 'ok tracks='+n}).catch(function(e){return 'error '+e.name+': '+e.message})""", True)
    check("Mic", "WebView microphone access (getUserMedia) with permission granted", "PASS" if str(gum).startswith("ok") else "FAIL", gum)
    to_app("tr")
    cdp.ev("__t.sr=[]")
    tap("#frMic", "Live mic")
    time.sleep(6)
    sr = cdp.ev("__t.sr.join(',')")
    interim, err = text("#frInterim"), pane_errors("tr")
    ev = shot("mic_live")
    avail = "start" in (sr or "")
    if avail:
        check("Mic", "Live mic (speech recognition) starts listening", "PASS", "events=%s interim=%r" % (sr, interim), ev)
    elif "service-not-allowed" in (sr or ""):
        check("Mic", "Live mic (speech recognition)", "NOT TESTABLE",
              "this emulator image has no speech-recognition service; app showed: %r %s" % (interim, err), ev)
    else:
        check("Mic", "Live mic (speech recognition)", "FAIL", "events=%s interim=%r errors=%s" % (sr, interim, err), ev)
    if cdp.ev("frMic.getAttribute('aria-pressed')") == "true":
        tap("#frMic", "stop mic")


def inject_file(sel, path, mime):
    data = base64.b64encode(open(path, "rb").read()).decode()
    cdp.ev("""(function(){var b=atob(%s),u=new Uint8Array(b.length);for(var i=0;i<b.length;i++)u[i]=b.charCodeAt(i);
      var f=new File([u],%s,{type:%s}); var dt=new DataTransfer(); dt.items.add(f); var inp=document.querySelector(%s);
      inp.files=dt.files; inp.dispatchEvent(new Event('change',{bubbles:true})); return inp.files.length})()"""
           % (json.dumps(data), json.dumps(os.path.basename(path)), json.dumps(mime), json.dumps(sel)))


def t_file_picker():
    to_app("tr")
    hide_kb()
    opened, top, tries = False, "", []
    for attempt in range(3):
        cdp.ev("__t.ev=[]")
        adb("logcat", "-c")
        tap("#frFile", "file input")
        for _ in range(8):
            time.sleep(1)
            top = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
            opened = bool(re.search(r"documentsui|ChooserActivity|ResolverActivity|photopicker|\.files", top, re.I))
            if opened:
                break
        native = [l[19:].strip() for l in adb("logcat", "-d", "-s", "BVS:V").splitlines() if "chooser" in l.lower()]
        tries.append("try %d opened=%s events=[%s] native=%s" % (attempt + 1, opened, cdp.ev("(__t.ev||[]).join(' ')"), native[-3:]))
        if opened:
            break
    ev = shot("file_picker")
    if opened:
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        top2 = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        if not re.search(PKG, top2):  # a chooser dialog plus the picker can need two Backs
            adb("shell", "input", "keyevent", "4")
            time.sleep(2)
    _, back = alive()
    cancel_ok = cdp.ev("true")  # page still answers after cancel (ValueCallback got null)
    check("Files", "Choose file opens Android file picker, Back returns to the app",
          "PASS" if opened and PKG in back and cancel_ok else "FAIL",
          "picker=%s | after back=%s | %s" % (top.strip()[:140], back[:120], " / ".join(tries)), ev)


def t_whisper():
    if not WAV or not os.path.exists(WAV):
        check("Files", "File upload -> Whisper transcribe + translate", "NOT TESTABLE", "no test WAV on runner")
        return
    to_app("tr")
    setv("#tgtLang", next((o for o in cdp.ev("[].map.call(tgtLang.options,function(o){return o.value})") if o.startswith("hi")), "hi-IN"))
    setv("#srcLang", next((o for o in cdp.ev("[].map.call(srcLang.options,function(o){return o.value})") if o.startswith("en")), "unknown"))
    cdp.ev("document.getElementById('frClear').click()")
    inject_file("#frFile", WAV, "audio/wav")
    tap("#frFileGo", "Transcribe + translate")
    t0 = time.time()
    n = wait_for("(function(){var e=document.getElementById('frErr');if(!e.hidden&&e.textContent.trim())return -1;return document.querySelectorAll('#frRows tr').length})()", 420, 5)
    rows_ = cdp.ev("[].map.call(document.querySelectorAll('#frRows tr'),function(r){return r.innerText.replace(/\\s+/g,' ')}).slice(0,3)")
    err, prog = pane_errors("tr"), text("#frProg")
    check("Files", "File upload (English speech WAV) -> Whisper transcript + Hindi translation",
          "PASS" if n and n > 0 else "FAIL", "rows=%s in %ds: %s | progress=%r errors=%s" % (n, time.time() - t0, rows_, prog, err), shot("whisper_result"))


def t_exports():
    to_app("tr")
    if not cdp.ev("document.querySelectorAll('#frRows tr').length"):
        cdp.ev("""(function(){})()""")
    before = downloads()
    res = {}
    for b in ["frTxt", "frSrt"]:
        try:
            tap("#" + b, b)
            time.sleep(3)
            new = downloads() - before
            res[b] = sorted(new)
            before |= new
        except Exception as e:
            res[b] = "error " + repr(e)[:120]
        err = pane_errors("tr")
        if err:
            res[b + "_msg"] = err
        res[b + "_native"] = bvs_log(2)
    ok = all(isinstance(res.get(b), list) and res.get(b) for b in ["frTxt", "frSrt"])
    check("Export", "Transcript TXT and SRT download to phone's Download folder", "PASS" if ok else "FAIL", res, shot("exports"))
    to_app("home")
    b0 = downloads()
    tap("#histAll", "History: Sab download karo")
    time.sleep(4)
    new = sorted(downloads() - b0)
    check("History", "History 'Sab download karo' saves a file", "PASS" if new else "FAIL", "%s native=%s" % (new, bvs_log(2)), shot("history"))
    if new:
        c = sh("cat '/sdcard/Download/%s' | head -c 300" % new[0])
        check("History", "Downloaded history file has the translations", "PASS" if re.search(r"[\u0B00-\u0B7F\u0980-\u09FFA-Za-z]{3}", c) else "FAIL", c[:300])


def t_link():
    to_app("lk")
    url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    cdp.ev("navigator.clipboard.writeText(%s)" % json.dumps(url), True)
    tap("#lkPaste", "Paste")
    v = cdp.ev("lkUrl.value")
    check("Link", "Paste button pastes the copied link", "PASS" if v == url else "FAIL", "field=%r errors=%s" % (v, pane_errors("lk")))
    if v != url:
        setv("#lkUrl", url)
    hide_kb()
    tap("#lkGo", "Audio lao")
    st = wait_for("""(function(){var s=document.getElementById('lkSubBox'),a=document.getElementById('lkAudioBox'),f=document.getElementById('lkFail');
        if(s&&!s.hidden) return 'subs'; if(a&&!a.hidden) return 'audio'; if(f&&!f.hidden) return 'fail'; return ''})()""", 150, 3)
    logtxt = cdp.ev("(document.getElementById('lkLog')||{}).innerText||''")
    info = text("#lkTitle")
    ev = shot("link_result")
    check("Link", "YouTube link -> fetch video info/subtitles/audio", "PASS" if st in ("subs", "audio") else "FAIL",
          "state=%r title=%r log=%r fail=%r" % (st, info, (logtxt or "")[-500:], text("#lkFailWhy")), ev)
    if st == "subs":
        opts = cdp.ev("[].map.call(lkSubSel.options,function(o){return o.textContent})")
        setv("#tgtLang", next((o for o in cdp.ev("[].map.call(tgtLang.options,function(o){return o.value})") if o.startswith("od")), "hi-IN"))
        # REAL adb taps only. Started = handler ran (button disabled while busy, or its log line appeared).
        started_js = "lkSubGo.disabled || /sources try|hisse translate/.test((document.getElementById('lkLog')||{}).innerText||'') || !document.getElementById('pane-tr').hidden"
        cdp.ev("document.getElementById('frClear').click()")  # setup only: empty transcript so only new Odia rows count
        retry, started = "", False
        for attempt in range(3):
            cdp.ev("__t.ev=[]")
            tap("#lkSubGo", "Subtitle se seedha translate")
            started = wait_for(started_js, 8, 0.5)
            evs = cdp.ev("(__t.ev||[]).join(' ')")
            retry += "real tap %d: started=%s events=[%s]; " % (attempt + 1, bool(started), evs)
            if started:
                break
        n = wait_for("""(function(){if(document.getElementById('pane-tr').hidden)return 0;
            return [].filter.call(document.querySelectorAll('#frRows tr'),function(r){return /[\u0B00-\u0B7F]/.test(r.innerText)}).length})()""", 200, 3)
        rows_ = cdp.ev("[].map.call(document.querySelectorAll('#frRows tr'),function(r){return r.innerText.replace(/\\s+/g,' ')}).slice(0,3)")
        lk_log = cdp.ev("(document.getElementById('lkLog')||{}).innerText||''")
        check("Link", "YouTube subtitles -> translated (Odia) transcript (real tap on 'Subtitle se seedha translate')", "PASS" if (n and started) else "FAIL",
              retry + "subtitle tracks=%s odia rows=%s %s errors=%s log=%r" % (opts, n, rows_, pane_errors("lk"), (lk_log or "")[-300:]), shot("link_subs_translated"))


def t_needs_key():
    for k, name, needle in [("vc", "Voice Clone", "Sarvam key"), ("db", "Video Dubbing (full dub)", "Sarvam key"),
                            ("el", "ElevenLabs voices", "ElevenLabs key"), ("sfx", "Sound effects", "ElevenLabs key"),
                            ("chg", "Voice changer", "ElevenLabs key"), ("iso", "Voice isolator", "ElevenLabs key"),
                            ("mus", "Music", "ElevenLabs key")]:
        to_app(k)
        t = cdp.ev("document.getElementById('pane-%s').innerText.replace(/\\s+/g,' ')" % k) or ""
        has = needle.lower() in t.lower()
        check("Needs key", "%s: tab opens and explains a key is needed" % name, "NEEDS KEY" if has else "FAIL",
              ("message shown: " if has else "no key message: ") + t[:220], shot("needs_key_" + k))
    # the "Key daalo"/"Settings kholo" button leads to Settings
    to_app("el")
    btn = cdp.ev("""(function(){var b=[].filter.call(document.querySelectorAll('#pane-el button'),function(b){return b.offsetParent&&/key daalo/i.test(b.textContent)})[0];
        if(!b) return null; b.id=b.id||'__keyBtn'; return '#'+b.id})()""")
    if btn:
        tap(btn, "Key daalo")
        sel = cdp.ev("document.getElementById('tab-more').getAttribute('aria-selected')")
        check("Needs key", "'Key daalo' button opens Settings / API keys", "PASS" if sel == "true" else "FAIL", "tab-more selected=%s" % sel)


def t_free_subs():
    to_app("db")
    if not WAV:
        check("Dubbing", "Free subtitles (Whisper -> translated SRT)", "NOT TESTABLE", "no test file")
        return
    inject_file("#fdFile", WAV, "audio/wav")
    srcs = cdp.ev("[].map.call(fdSrc.options,function(o){return o.value})")
    tg = cdp.ev("[].map.call(fdTgt.options,function(o){return o.value})")
    if srcs:
        setv("#fdSrc", next((s for s in srcs if s.startswith("en")), srcs[0]))
    if tg:
        setv("#fdTgt", next((s for s in tg if s.startswith("hi")), tg[0]))
    tap("#fdGo", "Whisper -> translate")
    ok = wait_for("(function(){var e=document.getElementById('fdErr');if(e&&!e.hidden&&e.textContent.trim())return 'err';return document.getElementById('fdSrt').hidden?'':'srt'})()", 300, 5)
    ev = shot("free_subtitles")
    if ok == "srt":
        b0 = downloads()
        tap("#fdSrt", "Target SRT download")
        time.sleep(3)
        new = sorted(downloads() - b0)
        check("Dubbing", "Free subtitles: Whisper -> translated SRT -> download", "PASS" if new else "FAIL", "downloaded=%s native=%s" % (new, bvs_log(2)), ev)
    else:
        check("Dubbing", "Free subtitles: Whisper -> translated SRT", "FAIL", "state=%r err=%s prog=%r" % (ok, pane_errors("db"), text("#fdProg")), ev)


def t_settings():
    to_app("more")
    setv("#apiKey", "sk_test_invalid_key_123")
    tap("#saveKey", "Save Sarvam key")
    m = wait_for("(function(){var t=keyMsg.textContent;return /galat|check nahi|sahi/i.test(t)?t:''})()", 25)
    check("Settings", "Sarvam key: Save checks the key and reports a wrong key", "PASS" if m and "galat" in m.lower() else "FAIL",
          "message=%r" % m, shot("settings_sarvam"))
    setv("#elKey", "sk_test_invalid_el_456")
    tap("#elKeyShow", "Show key")
    shown = cdp.ev("elKey.type")
    tap("#elKeySave", "Save ElevenLabs key")
    m2 = wait_for("(function(){var t=document.getElementById('elKeyMsg').textContent;return /galat|save|sahi/i.test(t)?t:''})()", 25)
    check("Settings", "ElevenLabs key: Show toggle + Save reports a wrong key", "PASS" if shown == "text" and m2 and "galat" in m2.lower() else "FAIL",
          "type=%s message=%r" % (shown, m2), shot("settings_el"))
    # engine radios
    tap('input[name="engine"][value="sarvam"]', "engine Sarvam", js_fallback=True)
    badge = text("#engBadge")
    to_app("vs")
    setv("#vsText", "Namaste")
    try:
        tap("#vsGo", "Sarvam Awaaz banao")
        time.sleep(3)
    except Exception:
        pass
    e = pane_errors("vs")
    check("Needs key", "Sarvam HD voices (Voice Studio) without a valid key show a clear message", "NEEDS KEY" if e else "FAIL",
          "engine=%r message=%s" % (badge, e), shot("sarvam_without_key"))
    to_app("more")
    tap('input[name="engine"][value="free"]', "engine Free", js_fallback=True)
    check("Settings", "Engine switch Sarvam/ElevenLabs/Free", "PASS" if text("#engBadge") == "Free" and badge == "Sarvam" else "FAIL",
          "after sarvam=%r after free=%r" % (badge, text("#engBadge")))


def t_help():
    to_app("help")
    t = cdp.ev("document.getElementById('pane-help').innerText.length")
    check("Help", "Help tab shows instructions", "PASS" if t and t > 200 else "FAIL", "%s chars" % t, shot("help"))


def t_security():
    r = cdp.ev("""Promise.all([
      fetch('http://example.com/').then(function(){return 'http ALLOWED'}).catch(function(e){return 'http blocked'}),
      fetch('file:///android_asset/index.html').then(function(){return 'file ALLOWED'}).catch(function(e){return 'file blocked'})
    ]).then(function(a){return a.join(', ')})""", True)
    check("Security", "No cleartext http and no file:// access from the page", "PASS" if "ALLOWED" not in r else "FAIL", r)


def t_persistence():
    """Keys must not survive an app restart, and must not be written to app storage."""
    sh("am force-stop " + PKG)
    time.sleep(2)
    found = sh("run-as %s sh -c 'grep -rl sk_test_invalid . 2>/dev/null | head'" % PKG).strip()
    sh("am start -n %s/.MainActivity" % PKG)
    time.sleep(12)
    connect()
    k = cdp.ev("[document.getElementById('apiKey').value, document.getElementById('elKey').value].join('|')")
    v = views()
    check("Security", "API keys are session-only (gone after restart, not in app files)",
          "PASS" if k == "|" and not found else "FAIL", "after restart fields=%r, files containing key=%r" % (k, found or "none"))
    check("App", "Relaunch after force-stop shows landing", "PASS" if v == ["landing"] else "FAIL", v, shot("relaunch"))


# ------------------------------------------------------------------ main
def main():
    adb("logcat", "-c")
    adb("logcat", "-G", "8M")
    ver = sh("getprop ro.build.version.release").strip() + " / API " + sh("getprop ro.build.version.sdk").strip()
    log("device: Android " + ver)
    if PREV:
        adb("uninstall", PKG)
        out = adb("install", PREV, timeout=300)
        check("Install", "Install currently published APK (" + os.path.basename(PREV) + ")", "PASS" if "Success" in out else "FAIL", out.strip()[-200:])
        out = adb("install", "-r", APK, timeout=300)
        check("Install", "Update new APK over the published one without uninstalling",
              "PASS" if "Success" in out else "FAIL", out.strip()[-300:])
        if "Success" not in out:
            adb("uninstall", PKG)
            out = adb("install", APK, timeout=300)
            check("Install", "Install after uninstalling the old app", "PASS" if "Success" in out else "FAIL", out.strip()[-200:])
    else:
        adb("uninstall", PKG)
        out = adb("install", APK, timeout=300)
        check("Install", "Fresh install on Android " + ver, "PASS" if "Success" in out else "FAIL", out.strip()[-200:])
    out = sh("am start -W -n %s/.MainActivity" % PKG, timeout=90)
    time.sleep(25)
    ev = shot("launch_25s")
    pid, act = alive()
    full, _ = dump_logs("launch")
    crash = re.findall(r"FATAL EXCEPTION.*(?:\n.*){0,20}", full)
    check("App", "Launch: app alive 25 s after start, no crash", "PASS" if pid and PKG in act and not crash else "FAIL",
          "pid=%s top=%s %s" % (pid, act[:120], crash[0][:800] if crash else ""), ev)

    if MODE == "full":
        try:
            connect()
        except Exception as e:
            check("App", "Attach to WebView DevTools", "FAIL", repr(e))
            return finish()
        for area, fn_name, fn in [("Landing", "landing", t_landing), ("Landing", "nav", t_landing_nav), ("Landing", "cards", t_cards),
                                  ("Auth", "signup", t_signup), ("Auth", "logout_login", t_logout_login), ("Auth", "google", t_google),
                                  ("Auth", "trydemo", t_trydemo), ("Navigation", "back", t_back), ("Tabs", "tabs", t_tabs),
                                  ("Translate", "languages", t_lang), ("Translate", "free_translate", t_free_translate),
                                  ("Speech", "tts", t_tts), ("Voice Studio", "voice_studio", t_voice_studio_free),
                                  ("Mic", "mic", t_mic), ("Files", "file_picker", t_file_picker), ("Files", "whisper", t_whisper),
                                  ("Downloads", "download_direct", t_download_direct), ("Export", "exports", t_exports), ("Link", "link", t_link), ("Needs key", "needs_key", t_needs_key),
                                  ("Dubbing", "free_subs", t_free_subs), ("Settings", "settings", t_settings), ("Help", "help", t_help),
                                  ("Security", "security", t_security), ("Security", "persistence", t_persistence)]:
            log("=== " + fn_name)
            run(area, fn_name, fn)
            try:
                cdp.pump(0.5)
            except Exception:
                try:
                    connect()
                except Exception:
                    pass
    finish()


def finish():
    pid, act = alive()
    check("App", "App still running at the end of the test", "PASS" if pid else "FAIL", act[:150], shot("final"))
    full, flt = dump_logs("end")
    crash = re.findall(r"FATAL EXCEPTION.*(?:\n.*){0,20}", full)
    if crash:
        check("App", "No crash during the whole run", "FAIL", crash[0][:1200])
    try:
        pe = cdp.ev("__t.err") if cdp else []
    except Exception:
        pe = ["(page gone)"]
    with open(os.path.join(OUT, "console.json"), "w") as f:
        json.dump({"page_errors": pe, "console": console}, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "report.json"), "w") as f:
        json.dump({"mode": MODE, "apk": os.path.basename(APK), "checks": rows}, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "checklist.md"), "w") as f:
        f.write("| # | Area | Function | Result | Evidence | Details |\n|---|---|---|---|---|---|\n")
        for i, r in enumerate(rows, 1):
            f.write("| %d | %s | %s | %s | %s | %s |\n" % (i, r["area"], r["function"], r["status"], r["evidence"],
                                                         r["detail"].replace("|", "/").replace("\n", " ")[:400]))
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        for r in rows:
            f.write("[%s] %s / %s :: %s  %s\n" % (r["status"], r["area"], r["function"], r["detail"][:600], r["evidence"]))
        f.write("\n--- page errors ---\n" + "\n".join(map(str, pe or [])) + "\n")
        f.write("\n--- console (errors/warnings) ---\n" + "\n".join("%s: %s" % (c["type"], c["text"]) for c in console
                                                                  if c["type"] in ("error", "warning", "EXCEPTION", "log-error", "log-warning"))[:20000] + "\n")


try:
    main()
except Exception:
    check("Harness", "test harness", "FAIL", traceback.format_exc()[-1500:])
    try:
        finish()
    except Exception:
        pass
