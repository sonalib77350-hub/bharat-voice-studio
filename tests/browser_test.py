"""Headless phone-sized browser test of the Puna AI UI with a mock native bridge (real Edge TTS via server)."""
import json, os, sys, time, subprocess
from playwright.sync_api import sync_playwright
WWW = sys.argv[1]; OUT = sys.argv[2]; os.makedirs(OUT, exist_ok=True)
PORT = 8765
srv = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), "server.py"), WWW, os.path.join(OUT, "media"), str(PORT)])
time.sleep(1.5)
rows = []
def check(name, ok, detail=""):
    rows.append((name, "PASS" if ok else "FAIL", str(detail)[:300])); print(("PASS" if ok else "FAIL"), name, str(detail)[:300], flush=True)
try:
    with sync_playwright() as p:
        kw = {"executable_path": os.environ["CHROME"]} if os.environ.get("CHROME") else {}
        b = p.chromium.launch(**kw, args=["--no-sandbox", "--no-proxy-server", "--autoplay-policy=no-user-gesture-required"])
        ctx = b.new_context(viewport={"width": 393, "height": 851}, device_scale_factor=2, is_mobile=True, has_touch=True, locale="hi-IN")
        pg = ctx.new_page(); errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        pg.on("console", lambda m: errs.append("console." + m.type + ": " + m.text) if m.type == "error" else None)
        pg.goto("http://127.0.0.1:%d/index.html" % PORT); pg.wait_for_function("window.Puna && window.Puna.ready", timeout=10000)
        pg.screenshot(path=OUT + "/01_assistant.png")
        st = pg.evaluate("window.Puna.S")
        check("default voice is Sonali, speed 1.3", st["voice"] == "sonali" and abs(st["speed"] - 1.3) < 1e-9, st["voice"])
        check("voice chip shows Sonali", pg.inner_text("#voiceChipName") == "Sonali")
        # no-key message
        pg.fill("#aType", "Hello Puna, kaise ho?"); pg.press("#aType", "Enter")
        pg.wait_for_function("document.querySelector('#aReply') && /Groq key/.test(document.querySelector('#aReply').innerText)", timeout=8000)
        check("no Groq key -> friendly 'Settings mein Groq key daalo' reply", True, pg.inner_text("#aReply")[:120])
        pg.wait_for_function("window.__mockCalls.some(c=>c.m==='edgeSynth')", timeout=8000)
        c = [x for x in pg.evaluate("window.__mockCalls") if x["m"] == "edgeSynth"][0]
        check("reply spoken with Sonali's Edge voice + speed 1.3 (+30%)", c["voice"] == "en-US-AvaMultilingualNeural" and c["rate"] == "+30%", c)
        pg.screenshot(path=OUT + "/02_nokey_reply.png")
        # voice switch by speaking (mock SR returns this phrase)
        pg.evaluate("window.Puna.stopAll(); window.__mockCalls.length=0; window.__mockSpeech='Rohan se baat karni hai'")
        pg.click("#micBtn")
        pg.wait_for_function("window.Puna.S.voice==='rohan'", timeout=5000)
        pg.wait_for_function("window.__mockCalls.some(c=>c.m==='edgeSynth')", timeout=8000)
        c = [x for x in pg.evaluate("window.__mockCalls") if x["m"] == "edgeSynth"][0]
        check("spoken 'Rohan se baat karni hai' switches voice -> Rohan (hi-IN-MadhurNeural)", c["voice"] == "hi-IN-MadhurNeural", pg.inner_text("#aReply")[:100])
        pg.evaluate("window.Puna.stopAll(); window.__mockCalls.length=0")
        for phrase, vid in [("Sonali se baat karni hai", "sonali"), ("Christina ki awaaz chahiye", "christina"), ("मुझे प्रियांशी से बात करनी है", "priyanshi"), ("switch to Primosh voice", "primosh"), ("Sagarika se baat karo", "sagarika"), ("Archita ki voice lagao", "archita"), ("Anup se baat karni hai", "anup"), ("Prince se baat karo", "prince"), ("Emma ki awaaz", "emma"), ("Anaya se baat", "anaya"), ("Aarav se baat karni hai", "aarav"), ("Swara ki awaaz lagao", "swara"), ("Sonali se baat karni hai", "sonali")]:
            r = pg.evaluate("t => window.Puna.localCommand(t)", phrase)
            ok = pg.evaluate("window.Puna.S.voice") == vid
            check("voice command '%s' -> %s" % (phrase, vid), ok, r and r.get("reply"))
        check("'Mera naam Sonali hai' does NOT switch voice", pg.evaluate("window.Puna.localCommand('Mera naam Sonali hai')") is None)
        # language command + memory
        r = pg.evaluate("window.Puna.localCommand('Ab se hamesha Hindi Devanagari mein baat karna')")
        check("language command sets replyLang=hi + saves memory", pg.evaluate("window.Puna.S.replyLang") == "hi" and any("Hindi" in m["text"] for m in pg.evaluate("window.Puna.memory.list()")), r)
        pg.evaluate("window.Puna.S.replyLang='auto'; window.Puna.save()")
        # Fast mode: web-search routing, fast model, streamed first sentence -> TTS before the reply finishes
        st = pg.evaluate("window.Puna.S")
        check("Fast mode is ON by default", st.get("fast") is True, st.get("fast"))
        cases = {"abhi kya kar rahi ho": False, "aaj main bahut thak gayi": False, "mujhe ek joke sunao": False, "aaj ki news batao": True,
                 "Bhubaneswar ka mausam kaisa hai": True, "aaj petrol ka price kya hai": True, "kal match kaun jeeta": True, "Python mein list kaise banate hain": False}
        bad = {k: v for k, v in cases.items() if pg.evaluate("t => window.Puna.needsWeb(t)", k) != v}
        check("web search only for current-info questions (8 phrases)", not bad, bad or "all correct")
        pg.evaluate("window.Puna.stopAll(); window.__mockCalls.length=0; window.Puna.S.groqKey='gsk_test'; window.Puna.S.replyLang='hinglish'; window.Puna.save();"
                    "window.Puna.mockCalls=[]; window.Puna.mockLLM={ttft:300, cps:400, text:'Haan Sonali, main theek hoon. Tum batao, aaj ka din kaisa raha? Kuch naya seekha ya bas aaram kiya?'}")
        pg.evaluate("window.Puna.ask('mera naam Sonali hai, tum kaisi ho', 'voice')")
        pg.wait_for_function("window.__mockCalls.some(c=>c.m==='edgeSynth')", timeout=8000)
        first = [x for x in pg.evaluate("window.__mockCalls") if x["m"] == "edgeSynth"][0]
        mc = pg.evaluate("window.Puna.mockCalls[0]")
        check("voice turn uses fast model openai/gpt-oss-20b, low effort, short max tokens", mc["model"] == "openai/gpt-oss-20b" and mc["effort"] == "low" and mc["max"] == 400 and mc["stream"], {k: mc[k] for k in ("model", "effort", "max", "stream")})
        check("fast voice system prompt asks for 1-3 short sentences", "1-3 short spoken sentences" in mc["sys"], mc["sys"][-300:])
        check("first sentence sent to TTS on its own while the reply is still streaming", first["text"].startswith("Haan Sonali") and "Tum batao" not in first["text"], first["text"])
        pg.wait_for_function("window.Puna.lastLatency && window.Puna.lastLatency.play", timeout=15000)
        lat = pg.evaluate("window.Puna.lastLatency")
        check("latency readout recorded (mock LLM ttft 300 ms; real Edge TTS via server)", lat["firstToken"] is not None and lat["play"] is not None, lat)
        time.sleep(0.5)
        n1 = len(pg.evaluate("window.Puna.mockCalls")); time.sleep(3.5); n2 = len(pg.evaluate("window.Puna.mockCalls"))
        check("memory extraction runs in the background only after the reply started speaking", n1 == 1 and n2 == 2, "calls at first audio=%d, 3.5 s later=%d" % (n1, n2))
        pg.evaluate("window.Puna.stopAll(); window.__mockCalls.length=0; window.Puna.mockCalls=[]; window.Puna.mockLLM.searchText='Aaj ki badi khabar: test headline.'")
        pg.evaluate("window.Puna.ask('aaj ki news batao', 'voice')")
        pg.wait_for_function("window.__mockCalls.some(c=>c.m==='edgeSynth')", timeout=8000)
        f = [x for x in pg.evaluate("window.__mockCalls") if x["m"] == "edgeSynth"][0]
        mc = pg.evaluate("window.Puna.mockCalls[0]")
        check("news question -> web search call + instant filler line spoken", mc["search"] and "second" in f["text"].lower(), {"search": mc["search"], "filler": f["text"]})
        pg.evaluate("window.Puna.stopAll(); delete window.Puna.mockLLM; window.Puna.S.groqKey=''; window.Puna.S.replyLang='auto'; window.Puna.save()")
        check("echo filter: own words ignored, new words accepted", pg.evaluate("(window.Puna.Speaker.recent=['Haan Sonali, main theek hoon.'], [window.Puna.isEcho('haan sonali main theek'), window.Puna.isEcho('ruko mujhe kuch aur poochna hai')])") == [True, False])
        # all voices synthesize for real through Edge (server) and decode in Chromium
        res = pg.evaluate("""async () => { const out = {}; for (const v of window.Puna.VOICES) { try { const t = v.scripts.includes('deva') ? 'नमस्ते, मैं ' + v.name + ' हूँ।' : 'Hello, I am ' + v.name + '.'; const pcm = await window.Puna.synthLine(v.id, t, 1.3); out[v.name] = pcm.length / 24000; } catch (e) { out[v.name] = 'ERR ' + e.message; } } return out; }""")
        for k, v in res.items(): check("Edge voice %s synthesizes + decodes" % k, isinstance(v, (int, float)) and v > 0.5, "%s sec" % v)
        o = pg.evaluate("window.Puna.resolveVoice('sonali', 'ନମସ୍କାର, ମୁଁ ସୋନାଲୀ। ଆଜି କଣ କରିବା?')")
        check("Odia text -> Bengali-script neural fallback (Archita)", o["engine"] == "edge" and o["voice"] == "bn-IN-TanishaaNeural", o)
        od = pg.evaluate("async () => (await window.Puna.synthLine('sonali', 'ନମସ୍କାର, ମୁଁ ସୋନାଲୀ। ଆଜି କଣ କରିବା?', 1.0)).length/24000")
        check("Odia line actually produces audio via Bengali-script voice", od > 1.0, "%.1f sec" % od)
        # Broadcast with custom script (no key needed)
        pg.click("#tabs button[data-tab=broadcast]")
        pg.click("#bMode button[data-v=script]")
        pg.fill("#bTopic", "Sonali: Namaste dosto! Aaj hum aloo paratha banana seekhenge.\nRohan: Wah! Pehle kya karna hai?\nSonali: Chaar uble aloo lo aur achhe se mash karo.\nChristina: And don't forget a pinch of love!\nArchita: ଧନ୍ୟବାଦ, ପୁଣି ଦେଖା ହେବ।")
        pg.click("#bVoices .vchip[data-id=sonali]"); pg.click("#bVoices .vchip[data-id=rohan]")
        pg.screenshot(path=OUT + "/03_broadcast_form.png")
        pg.click("#bGo")
        pg.wait_for_function("window.Puna.lastBroadcast || window.Puna.lastBroadcastError", timeout=120000)
        item = pg.evaluate("window.Puna.lastBroadcast"); err = pg.evaluate("window.Puna.lastBroadcastError")
        check("custom-script broadcast builds stitched audio (5 lines, 4 voices)", bool(item) and len(item["timed"]) == 5, err or "%d lines, %.1fs, audio=%s" % (len(item["timed"]), item["duration"], item.get("audio")))
        if item:
            names = [t["name"] for t in item["timed"]]
            check("speakers mapped by name (Sonali, Rohan, Sonali, Christina, Archita)", names == ["Sonali", "Rohan", "Sonali", "Christina", "Archita"], names)
            mono = all(item["timed"][i]["end"] <= item["timed"][i + 1]["start"] for i in range(len(item["timed"]) - 1))
            check("line timings increase monotonically", mono, [(round(t["start"], 2), round(t["end"], 2)) for t in item["timed"]])
            m4a = os.path.join(OUT, "media", item["audio"])
            pr = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", m4a], capture_output=True, text=True).stdout.strip()
            check("m4a file valid (ffprobe)", pr and abs(float(pr) - item["duration"]) < 1.0, pr)
            pg.wait_for_selector("#bResult:not([hidden])")
            pg.screenshot(path=OUT + "/04_broadcast_result.png", full_page=True)
            pg.click("#bSave"); pg.click("#bWa"); time.sleep(0.5)
            calls = pg.evaluate("window.__mockCalls")
            check("Save -> saveToDownloads called with .m4a", any(c["m"] == "save" and c["display"].endswith(".m4a") for c in calls), [c for c in calls if c["m"] == "save"])
            check("WhatsApp share requested (falls back to chooser when missing)", [c["target"] for c in calls if c["m"] == "share"][:2] == ["whatsapp", ""], [c for c in calls if c["m"] == "share"])
        # chat tab
        pg.click("#tabs button[data-tab=chat]"); time.sleep(0.3)
        pg.screenshot(path=OUT + "/05_chat.png")
        check("chat history shows earlier messages", pg.locator(".msg").count() >= 2, pg.locator(".msg").count())
        pg.evaluate("document.querySelector('.msg.assistant .md').innerHTML = window.Puna.md('# Title\\n**bold** and `code`\\n- one\\n- two\\n```js\\nlet a=1\\n```\\n[link](https://example.com)')")
        h = pg.inner_html(".msg.assistant .md")
        check("markdown renders headings, bold, lists, code, links", all(s in h for s in ["<h3>", "<b>bold</b>", "<li>", "<pre><code>", "class=\"ext\""]), h[:200])
        pg.click("#cNew"); check("new chat created", pg.inner_text("#cTitle") == "Nayi chat")
        pg.click("#cListBtn"); time.sleep(0.2); check("chat list shows multiple conversations", pg.locator(".conv").count() >= 2, pg.locator(".conv").count()); pg.screenshot(path=OUT + "/06_chat_list.png"); pg.click("#cDrawerClose")
        # settings + memory
        pg.click("#tabs button[data-tab=settings]")
        pg.fill("#sMemAdd", "Mujhe Odia gaane pasand hain"); pg.click("#sMemAddBtn")
        check("manual memory add shows in list", "Odia gaane" in pg.inner_text("#sMemList"))
        n0 = pg.locator("#sMemList .mem").count(); pg.locator("#sMemList [data-mdel]").first.click()
        check("memory delete works", pg.locator("#sMemList .mem").count() == n0 - 1, "%d -> %d" % (n0, pg.locator("#sMemList .mem").count()))
        pg.screenshot(path=OUT + "/07_settings.png", full_page=True)
        pg.click("#sVoiceBtn"); time.sleep(0.2); pg.screenshot(path=OUT + "/08_voice_picker.png")
        check("voice picker lists all 13 voices", pg.locator("#vsList .vitem").count() == 13, pg.locator("#vsList .vitem").count())
        pg.click("#vsList .vitem[data-id=prince]"); check("picker selects Prince", pg.evaluate("window.Puna.S.voice") == "prince")
        pg.click("#tabs button[data-tab=builder]"); check("builder tab shows Coming soon", "Coming soon" in pg.inner_text("#v-builder")); pg.screenshot(path=OUT + "/09_builder.png")
        pg.click("#tabs button[data-tab=auto]"); time.sleep(0.3)
        check("Agent tab replaces Coming soon (setup + task box)", "Coming soon" not in pg.inner_text("#v-auto") and pg.is_visible("#agGo"), pg.inner_text("#v-auto")[:120])
        check("phone-task intent detection", [pg.evaluate("t=>window.Puna.isPhoneTask(t)", t) for t in ["WhatsApp kholo aur Sonali ko good morning bhejo", "torch on karo", "alarm kaise lagate hain", "aaj ki news batao"]] == [True, True, False, False])
        pg.evaluate("window.__mockCalls.length=0; window.Puna.autoConfirm=false; window.Puna.mockAgent=[{name:'open_app',args:{name:'Settings'}},{name:'tap',args:{index:0}},{name:'tap',args:{text:'Send'}},{name:'finish',args:{summary:'Settings khola.'}}]")
        pg.fill("#agTask", "Settings kholo"); pg.click("#agGo")
        pg.wait_for_function("window.Puna.lastAgent", timeout=15000)
        la = pg.evaluate("window.Puna.lastAgent"); tc = [c for c in pg.evaluate("window.__mockCalls") if c["m"] == "agentTool"]
        check("agent loop (mock planner): open_app -> tap -> risky 'Send' asked & declined (not executed) -> finish", [c["name"] for c in tc] == ["open_app", "tap"] and any("Nahi" in l["text"] for l in la["log"]) and la["result"]["summary"] == "Settings khola.", ([c["name"] for c in tc], [l["text"] for l in la["log"]]))
        pg.screenshot(path=OUT + "/10_agent.png", full_page=True)
        pg.evaluate("delete window.Puna.mockAgent; delete window.Puna.autoConfirm")
        check("no page JS errors", not [e for e in errs if "favicon" not in e], errs[:5])
        b.close()
finally:
    srv.terminate()
with open(OUT + "/report.md", "w") as f:
    f.write("| Check | Result | Detail |\n|---|---|---|\n" + "\n".join("| %s | %s | %s |" % (a, s, d.replace("|", "/").replace("\n", " ")) for a, s, d in rows) + "\n")
print("SUMMARY", sum(1 for r in rows if r[1] == "PASS"), "pass /", len(rows))
