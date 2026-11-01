/* Puna AI — Phase 1 (voice assistant, memory, web answers, Broadcast, chat). Sonali ke liye. */
(function () {
"use strict";
var $ = function (id) { return document.getElementById(id); };
var N = window.PunaNative || null;           // native bridge (Android); a mock is injected in browser tests
window.Puna = window.Puna || {};

/* ------------------------------------------------------------------ voices */
// Persona -> Microsoft Edge neural voice. scripts = writing systems the voice reads correctly
// (verified 2026-11-01 by synthesizing Hindi/English/Bengali/Odia text with every voice).
var VOICES = [
  { id: "sonali",    name: "Sonali",    g: "F", edge: "en-US-AvaMultilingualNeural",   pitch: "+2Hz", scripts: ["latn", "deva", "beng"], color: "#F472B6", hi: "सोनाली", desc: "Sabse meethi, warm & caring · Hindi/English/Bangla", love: true },
  { id: "emma",      name: "Emma",      g: "F", edge: "en-US-EmmaMultilingualNeural",  scripts: ["latn", "deva", "beng"], color: "#A78BFA", hi: "एम्मा", desc: "Cheerful, clear · Multilingual" },
  { id: "swara",     name: "Swara",     g: "F", edge: "hi-IN-SwaraNeural",             scripts: ["latn", "deva"], color: "#FB923C", hi: "स्वरा", desc: "Hindi female" },
  { id: "anaya",     name: "Anaya",     g: "F", edge: "en-IN-NeerjaExpressiveNeural",  scripts: ["latn"], color: "#34D399", hi: "अनाया", desc: "Indian English, expressive" },
  { id: "christina", name: "Christina", g: "F", edge: "en-GB-SoniaNeural",             scripts: ["latn"], color: "#60A5FA", hi: "क्रिस्टीना", desc: "British English female" },
  { id: "priyanshi", name: "Priyanshi", g: "F", edge: "mr-IN-AarohiNeural",            scripts: ["latn", "deva"], color: "#F59E0B", hi: "प्रियांशी", desc: "Marathi female · Hindi bhi" },
  { id: "archita",   name: "Archita",   g: "F", edge: "bn-IN-TanishaaNeural",          scripts: ["latn", "beng", "orya"], color: "#E879F9", hi: "अर्चिता", desc: "Bengali female · Odia (approx)" },
  { id: "sagarika",  name: "Sagarika",  g: "F", edge: "ne-NP-HemkalaNeural",           scripts: ["latn", "deva"], color: "#2DD4BF", hi: "सागरिका", desc: "Nepali female · Hindi bhi" },
  { id: "aarav",     name: "Aarav",     g: "M", edge: "en-IN-PrabhatNeural",           scripts: ["latn"], color: "#38BDF8", hi: "आरव", desc: "Indian English male" },
  { id: "rohan",     name: "Rohan",     g: "M", edge: "hi-IN-MadhurNeural",            scripts: ["latn", "deva"], color: "#4ADE80", hi: "रोहन", desc: "Hindi male" },
  { id: "anup",      name: "Anup",      g: "M", edge: "bn-IN-BashkarNeural",           scripts: ["latn", "beng", "orya"], color: "#FBBF24", hi: "अनूप", desc: "Bengali male · Odia (approx)" },
  { id: "prince",    name: "Prince",    g: "M", edge: "en-US-AndrewMultilingualNeural",scripts: ["latn", "deva", "beng"], color: "#818CF8", hi: "प्रिंस", desc: "Warm, confident · Multilingual" },
  { id: "primosh",   name: "Primosh",   g: "M", edge: "mr-IN-ManoharNeural",           scripts: ["latn", "deva"], color: "#F87171", hi: "प्रिमोश", desc: "Marathi male · Hindi bhi" }
];
var VBYID = {}; VOICES.forEach(function (v) { VBYID[v.id] = v; });
// Native-language Edge voices for scripts the personas do not read.
var SCRIPT_VOICES = {
  gujr: { F: "gu-IN-DhwaniNeural", M: "gu-IN-NiranjanNeural" }, taml: { F: "ta-IN-PallaviNeural", M: "ta-IN-ValluvarNeural" },
  telu: { F: "te-IN-ShrutiNeural", M: "te-IN-MohanNeural" },    knda: { F: "kn-IN-SapnaNeural", M: "kn-IN-GaganNeural" },
  mlym: { F: "ml-IN-SobhanaNeural", M: "ml-IN-MidhunNeural" },  arab: { F: "ur-IN-GulNeural", M: "ur-IN-SalmanNeural" },
  deva: { F: "hi-IN-SwaraNeural", M: "hi-IN-MadhurNeural" },    beng: { F: "bn-IN-TanishaaNeural", M: "bn-IN-BashkarNeural" },
  latn: { F: "en-US-AvaMultilingualNeural", M: "en-US-AndrewMultilingualNeural" }
};
var LANGS = [
  { id: "auto", label: "Auto (jaise main bolun)", sr: "hi-IN", rule: "Reply in the same language AND script the user used. If the user writes Hindi in Roman letters (Hinglish), reply in Hinglish (Roman). Odia stays Odia script." },
  { id: "hinglish", label: "Hinglish (Roman)", sr: "hi-IN", rule: "Always reply in Hinglish: Hindi-English mix written ONLY in Roman/Latin letters." },
  { id: "hi", label: "हिन्दी (Devanagari)", sr: "hi-IN", rule: "Always reply ONLY in Hindi written in Devanagari script (हिन्दी)." },
  { id: "en", label: "English", sr: "en-IN", rule: "Always reply in simple English." },
  { id: "or", label: "ଓଡ଼ିଆ Odia", sr: "or-IN", rule: "Always reply ONLY in Odia (ଓଡ଼ିଆ) written in Odia script." },
  { id: "bn", label: "বাংলা Bengali", sr: "bn-IN", rule: "Always reply ONLY in Bengali (বাংলা script)." },
  { id: "mr", label: "मराठी Marathi", sr: "mr-IN", rule: "Always reply ONLY in Marathi (Devanagari)." },
  { id: "gu", label: "ગુજરાતી Gujarati", sr: "gu-IN", rule: "Always reply ONLY in Gujarati script." },
  { id: "ta", label: "தமிழ் Tamil", sr: "ta-IN", rule: "Always reply ONLY in Tamil script." },
  { id: "te", label: "తెలుగు Telugu", sr: "te-IN", rule: "Always reply ONLY in Telugu script." },
  { id: "kn", label: "ಕನ್ನಡ Kannada", sr: "kn-IN", rule: "Always reply ONLY in Kannada script." },
  { id: "ml", label: "മലയാളം Malayalam", sr: "ml-IN", rule: "Always reply ONLY in Malayalam script." },
  { id: "pa", label: "ਪੰਜਾਬੀ Punjabi", sr: "pa-IN", rule: "Always reply ONLY in Punjabi (Gurmukhi script)." },
  { id: "ur", label: "اردو Urdu", sr: "ur-IN", rule: "Always reply ONLY in Urdu (Urdu script)." }
];
var LBYID = {}; LANGS.forEach(function (l) { LBYID[l.id] = l; });
var SR_LANGS = [["hi-IN", "Hindi / Hinglish"], ["en-IN", "English (India)"], ["or-IN", "Odia"], ["bn-IN", "Bengali"], ["mr-IN", "Marathi"], ["gu-IN", "Gujarati"], ["ta-IN", "Tamil"], ["te-IN", "Telugu"], ["kn-IN", "Kannada"], ["ml-IN", "Malayalam"], ["pa-IN", "Punjabi"], ["ur-IN", "Urdu"], ["en-US", "English (US)"]];

/* ------------------------------------------------------------------ storage */
function load(k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { toast("Storage full: " + e.message); } }
var DEF = { groqKey: "", geminiKey: "", brain: "groq", level: "high", model: "", callMe: "", gender: "", replyLang: "auto", srLang: "hi-IN",
  voice: "sonali", speed: 1.3, continuous: true, autoSpeak: true, web: "auto", memoryOn: true, odia: "auto",
  fast: true, bargeIn: false, showLat: false, agentVoice: true };
var S = Object.assign({}, DEF, load("puna.settings", {}));
function saveS() { save("puna.settings", S); refreshBadges(); }
var MEM = load("puna.memory", []);
function saveMem() { save("puna.memory", MEM); renderMem(); }
var CONVS = load("puna.convs", []);
var CUR = load("puna.cur", null);
function saveConvs() { if (CONVS.length > 60) CONVS = CONVS.slice(0, 60); save("puna.convs", CONVS); save("puna.cur", CUR); }
var BHIST = load("puna.broadcasts", []);

/* ------------------------------------------------------------------ utils */
var toastT;
function toast(m, ms) { var t = $("toast"); t.textContent = m; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(function () { t.hidden = true; }, ms || 3200); }
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function fmtTime(t) { var d = new Date(t); return d.toLocaleString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" }); }
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
function persona(id) { return VBYID[id] || VBYID.sonali; }

var SCRIPT_RANGES = [["deva", 0x0900, 0x097F], ["beng", 0x0980, 0x09FF], ["guru", 0x0A00, 0x0A7F], ["gujr", 0x0A80, 0x0AFF], ["orya", 0x0B00, 0x0B7F],
  ["taml", 0x0B80, 0x0BFF], ["telu", 0x0C00, 0x0C7F], ["knda", 0x0C80, 0x0CFF], ["mlym", 0x0D00, 0x0D7F], ["arab", 0x0600, 0x06FF]];
function scriptOf(text) {
  var c = { latn: 0 };
  for (var i = 0; i < text.length; i++) {
    var cp = text.charCodeAt(i), hit = false;
    for (var j = 0; j < SCRIPT_RANGES.length; j++) { var r = SCRIPT_RANGES[j]; if (cp >= r[1] && cp <= r[2]) { c[r[0]] = (c[r[0]] || 0) + 1; hit = true; break; } }
    if (!hit && /[A-Za-z]/.test(text[i])) c.latn++;
  }
  var best = "latn", n = 0;
  Object.keys(c).forEach(function (k) { if (k !== "latn" && c[k] > n) { best = k; n = c[k]; } });
  return n > 0 ? best : "latn";
}
var SCRIPT_SR = { deva: "hi-IN", beng: "bn-IN", guru: "pa-IN", gujr: "gu-IN", orya: "or-IN", taml: "ta-IN", telu: "te-IN", knda: "kn-IN", mlym: "ml-IN", arab: "ur-IN", latn: "en-IN" };
// Odia -> Bengali script (near 1:1 Brahmic layout) so a Bengali neural voice can read Odia approximately.
function odiaToBengali(s) {
  var out = "";
  for (var i = 0; i < s.length; i++) {
    var cp = s.charCodeAt(i);
    if (cp < 0x0B00 || cp > 0x0B7F) { out += s[i]; continue; }
    if (cp === 0x0B5F) { out += "\u09DF"; continue; }      // ୟ -> য়
    if (cp === 0x0B71) { out += "\u0993\u09DF"; continue; } // ୱ -> ওয়
    if (cp === 0x0B33) { out += "\u09B2"; continue; }      // ଳ -> ল
    if (cp === 0x0B35) { out += "\u09AC"; continue; }      // ଵ -> ব
    if (cp === 0x0B56 || cp === 0x0B57) { out += "\u09D7"; continue; }
    out += String.fromCharCode(cp - 0x0B00 + 0x0980);
  }
  return out;
}
window.Puna.odiaToBengali = odiaToBengali;
// Clean text for speech: no markdown, links, citation markers, emoji.
function speakable(t) {
  return String(t || "")
    .replace(/```[\s\S]*?```/g, " (code) ")
    .replace(/【[^】]*】/g, "").replace(/\[\d+(?:[,†][^\]]*)?\]/g, "")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "").replace(/\[([^\]]+)\]\((https?:[^)]+)\)/g, "$1")
    .replace(/https?:\/\/\S+/g, "").replace(/[*_#>`|~]+/g, " ")
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{200D}]/gu, "")
    .replace(/\s+/g, " ").trim();
}

/* ------------------------------------------------------------------ native bridge */
var pend = {}, progH = {};
window.__punaCb = function (id, res) { var p = pend[id]; if (!p) return; delete pend[id]; if (res && res.ok) p.res(res); else p.rej(new Error((res && res.error) || "failed")); };
window.__punaProg = function (id, pct, stage) { if (progH[id]) progH[id](pct, stage); };
function nativeTask(fn, onProg, timeoutMs) {
  var id = "t" + uid();
  return new Promise(function (res, rej) {
    pend[id] = { res: res, rej: rej };
    if (onProg) progH[id] = onProg;
    if (timeoutMs) setTimeout(function () { if (pend[id]) { delete pend[id]; rej(new Error("timeout")); } }, timeoutMs);
    try { fn(id); } catch (e) { delete pend[id]; rej(e); }
  }).finally(function () { delete progH[id]; });
}
function rateStr(speed) { var p = Math.round((speed - 1) * 100); return (p >= 0 ? "+" : "") + p + "%"; }
function edgeSynth(text, voiceName, speed, pitch) {
  if (!N || !N.edgeSynth) return Promise.reject(new Error("Native voice nahi mila (browser mode)"));
  return nativeTask(function (id) { N.edgeSynth(id, text, voiceName, rateStr(speed), pitch || "+0Hz"); }, null, 60000);
}
async function writeBlob(blob, name) {
  if (!N || !N.writeBegin) throw new Error("native write unavailable");
  var nm = N.writeBegin(name), buf = new Uint8Array(await blob.arrayBuffer()), CH = 384 * 1024;
  for (var o = 0; o < buf.length; o += CH) {
    var part = buf.subarray(o, Math.min(buf.length, o + CH)), bin = "";
    for (var i = 0; i < part.length; i += 8192) bin += String.fromCharCode.apply(null, part.subarray(i, i + 8192));
    if (!N.writeChunk(nm, btoa(bin))) throw new Error("file write failed");
  }
  return N.writeEnd(nm);
}
var hasOdiaTTS = null;
function odiaPhoneVoice() { if (hasOdiaTTS === null) { try { hasOdiaTTS = !!(N && N.ttsHasLang && N.ttsHasLang("or-IN")); } catch (e) { hasOdiaTTS = false; } } return hasOdiaTTS; }

/* Pick the engine/voice for one piece of text spoken by persona p. */
function resolveVoice(p, text, forFile) {
  var sc = scriptOf(text);
  if (sc === "orya") {
    var eng = S.odia;
    if (eng === "auto") eng = (!forFile && odiaPhoneVoice()) ? "android" : "bengali";
    if (eng === "android" && forFile) eng = "bengali";
    if (eng === "android") return { engine: "android", lang: "or-IN", label: "Phone Odia" };
    if (eng === "mms") return { engine: "mms", lang: "or", label: "MMS Odia" };
    var bp = p.scripts.indexOf("orya") >= 0 ? p : (p.g === "M" ? VBYID.anup : VBYID.archita);
    return { engine: "edge", voice: bp.edge, pitch: bp.pitch, text: odiaToBengali(text), label: bp === p ? "" : bp.name + " (Odia)" };
  }
  if (p.scripts.indexOf(sc) >= 0) return { engine: "edge", voice: p.edge, pitch: p.pitch, label: "" };
  var sv = SCRIPT_VOICES[sc];
  if (sv) return { engine: "edge", voice: sv[p.g] || sv.F, label: "native " + sc };
  return { engine: "android", lang: SCRIPT_SR[sc] || "hi-IN", label: "Phone voice" };
}
window.Puna.resolveVoice = function (id, text) { return resolveVoice(persona(id), text); };

/* ------------------------------------------------------------------ Meta MMS (on-device Odia etc.) */
var MMS_ORT = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/";
var MMS_MODELS = { hi: ["Xenova/mms-tts-hin", "onnx/model_quantized.onnx"], en: ["Xenova/mms-tts-eng", "onnx/model_quantized.onnx"] };
["or", "te", "mr", "gu", "ml", "pa", "as"].forEach(function (c) { MMS_MODELS[c] = ["naklitechie/mms-tts-" + c + "-ONNX", "model.onnx"]; });
var mmsOrt = null, mmsSess = {}, mmsVocab = {};
async function mmsFetch(url, label) {
  var cache = null;
  try { if (window.caches) cache = await caches.open("puna-mms-v1"); } catch (e) { cache = null; }
  var hit = cache && await cache.match(url);
  if (hit) return hit.arrayBuffer();
  var res = await fetch(url);
  if (!res.ok) throw new Error("Model download nahi hua (" + res.status + ")");
  var total = +res.headers.get("content-length") || 0, got = 0, parts = [], last = 0, rd = res.body.getReader();
  for (;;) { var r = await rd.read(); if (r.done) break; parts.push(r.value); got += r.value.length;
    if (label && Date.now() - last > 1500) { last = Date.now(); toast(label + ": " + (got / 1048576).toFixed(1) + (total ? " / " + (total / 1048576).toFixed(0) : "") + " MB"); } }
  var buf = new Uint8Array(got), o = 0; parts.forEach(function (p) { buf.set(p, o); o += p.length; });
  if (cache) { try { await cache.put(url, new Response(buf.slice().buffer)); } catch (e) {} }
  return buf.buffer;
}
async function mmsSpeak(text, lang) {
  var m = MMS_MODELS[lang]; if (!m) throw new Error("MMS voice nahi hai: " + lang);
  if (!mmsOrt) { mmsOrt = await import(MMS_ORT + "ort.bundle.min.mjs"); mmsOrt.env.wasm.wasmPaths = MMS_ORT; mmsOrt.env.wasm.numThreads = 1; }
  var base = "https://huggingface.co/" + m[0] + "/resolve/main/";
  if (!mmsVocab[lang]) mmsVocab[lang] = JSON.parse(new TextDecoder().decode(await mmsFetch(base + "vocab.json")));
  if (!mmsSess[lang]) mmsSess[lang] = await mmsOrt.InferenceSession.create(new Uint8Array(await mmsFetch(base + m[1], "Odia awaaz download")), { executionProviders: ["wasm"] });
  var V = mmsVocab[lang], out = [], total = 0, gap = new Float32Array(3200);
  var parts = text.match(/[^.!?।॥\n]+[.!?।॥]?/g) || [text];
  for (var i = 0; i < parts.length; i++) {
    var ids = [0]; Array.from(parts[i].toLowerCase()).forEach(function (ch) { if (V[ch] != null) ids.push(V[ch], 0); });
    if (ids.length < 3) continue;
    var n = ids.length, a = new BigInt64Array(n), mk = new BigInt64Array(n);
    for (var j = 0; j < n; j++) { a[j] = BigInt(ids[j]); mk[j] = BigInt(1); }
    var r = await mmsSess[lang].run({ input_ids: new mmsOrt.Tensor("int64", a, [1, n]), attention_mask: new mmsOrt.Tensor("int64", mk, [1, n]) });
    var w = (r.waveform || r[mmsSess[lang].outputNames[0]]).data;
    if (out.length) { out.push(gap); total += gap.length; } out.push(w); total += w.length;
  }
  if (!total) throw new Error("Bolne layak akshar nahi mile");
  var all = new Float32Array(total), o = 0; out.forEach(function (x) { all.set(x, o); o += x.length; });
  return wavBlob([all], 16000);
}
function wavBlob(chunks, rate) {
  var n = 0; chunks.forEach(function (c) { n += c.length; });
  var b = new ArrayBuffer(44 + n * 2), v = new DataView(b), o = 0;
  function s(x) { for (var i = 0; i < x.length; i++) v.setUint8(o++, x.charCodeAt(i)); }
  s("RIFF"); v.setUint32(o, 36 + n * 2, true); o += 4; s("WAVEfmt "); v.setUint32(o, 16, true); o += 4; v.setUint16(o, 1, true); o += 2; v.setUint16(o, 1, true); o += 2;
  v.setUint32(o, rate, true); o += 4; v.setUint32(o, rate * 2, true); o += 4; v.setUint16(o, 2, true); o += 2; v.setUint16(o, 16, true); o += 2; s("data"); v.setUint32(o, n * 2, true); o += 4;
  chunks.forEach(function (c) { for (var i = 0; i < c.length; i++) { var x = Math.max(-1, Math.min(1, c[i])); v.setInt16(o, x < 0 ? x * 0x8000 : x * 0x7FFF, true); o += 2; } });
  return new Blob([b], { type: "audio/wav" });
}

/* ------------------------------------------------------------------ Speaker (sentence queue, prefetch, fallbacks) */
var ttsWait = {};
window.__punaTTS = function (id, type, p) { var w = ttsWait[id]; if (!w) return; if (type === "end" || type === "error") { delete ttsWait[id]; w(type === "error" ? (p && p.message) || "error" : null); } };
var player = $("player");
/* ------------------------------------------------------------------ latency readout (Fast mode) */
var LAT = null, DEBUG = false;
function now() { return (window.performance && performance.now) ? performance.now() : Date.now(); }
function markPlay() { if (LAT && !LAT.play) { LAT.play = now(); renderLat(); } }
player.addEventListener("playing", markPlay);
function renderLat() {
  var L = LAT; if (!L) return;
  var r = function (a, b) { return (a && b) ? Math.round(a - b) : null; };
  var o = { stt: r(L.t0, L.speechEnd), firstToken: r(L.tok, L.t0), firstSentence: r(L.sent, L.t0), tts: r(L.tts, L.sent), ttsNative: L.ttsNative, ttsFirstAudio: L.first,
    warm: L.warm, play: r(L.play, L.t0), total: r(L.play, L.speechEnd || L.t0), model: L.model, search: !!L.search, filler: !!L.filler };
  window.Puna.lastLatency = o; (window.Puna.latHist = window.Puna.latHist || []).push(o);
  var el = $("aLat"); if (!el) return;
  if (!(S.showLat || DEBUG)) { el.hidden = true; return; }
  el.hidden = false;
  el.textContent = "⏱ " + [o.stt != null ? "mic " + o.stt : "", o.firstToken != null ? "LLM " + o.firstToken : "", o.firstSentence != null ? "vakya " + o.firstSentence : "",
    o.tts != null ? "awaaz " + o.tts + (o.warm ? " warm" : " cold") : "", o.play != null ? "bajna " + o.play : "", o.total != null ? "= " + o.total + " ms" : ""].filter(Boolean).join(" · ") + (o.filler ? " (filler)" : "") + (o.model ? " · " + o.model : "");
}
var Speaker = {
  q: [], gen: 0, running: false, buf: "", pid: null, warned: false, lastText: "", onState: null, fed: 0, recent: [],
  _item: function (text, pid) {
    var p = persona(pid), v = resolveVoice(p, text, false), it = { text: text, p: p, v: v, prep: null };
    return it;
  },
  _prepare: function (it) {
    if (it.prep) return it.prep;
    var sp = S.speed;
    if (it.v.engine === "edge") {
      it.prep = edgeSynth(it.v.text || it.text, it.v.voice, sp, it.v.pitch).then(function (r) { return { url: r.url, ms: r.ms, warm: r.warm, first: r.first }; }, function (e) {
        if (!Speaker.warned) { Speaker.warned = true; toast("Neural awaaz nahi chali (" + e.message + ") — phone ki awaaz se bol rahi hoon"); }
        return { android: SCRIPT_SR[scriptOf(it.text)] || "hi-IN" };
      });
    } else if (it.v.engine === "mms") {
      it.prep = mmsSpeak(it.text, it.v.lang).then(function (b) { return { url: URL.createObjectURL(b), rate: sp, blob: true }; },
        function (e) { toast("MMS Odia: " + e.message); return { android: "or-IN" }; });
    } else it.prep = Promise.resolve({ android: it.v.lang });
    return it.prep;
  },
  say: function (text, pid) { this._push(speakable(text), pid); this._run(); },
  _push: function (text, pid) {
    if (!text) return;
    var parts = splitSentences(text);
    for (var i = 0; i < parts.length; i++) this.q.push(this._item(parts[i], pid || S.voice));
  },
  feed: function (delta, pid) {
    this.buf += delta; this.pid = pid;
    var m, re = /^([\s\S]*?[.!?।॥\n])(\s+|$)/;
    for (;;) {
      m = this.buf.match(/^[\s\S]{12,}?[.!?।॥:\n](?=\s)/);
      // Fast mode: don't wait for a long first sentence to end — voice its first clause right away
      if (!m && S.fast && this.fed === 0 && this.buf.length >= 48) m = this.buf.match(/^[\s\S]{20,}?[,;—–](?=\s)/);
      if (!m) break;
      var s = m[0]; this.buf = this.buf.slice(s.length);
      var t = speakable(s); if (t) { this.fed++; if (LAT && !LAT.sent) LAT.sent = now(); this.q.push(this._item(t, pid)); this._run(); }
    }
  },
  flush: function (pid) { var t = speakable(this.buf); this.buf = ""; if (t) { this.fed++; if (LAT && !LAT.sent) LAT.sent = now(); this.q.push(this._item(t, pid || this.pid)); } this._run(); },
  stop: function () {
    this.gen++; this.q = []; this.buf = ""; this.running = false; this.fed = 0;
    try { player.pause(); player.removeAttribute("src"); player.load(); } catch (e) {}
    try { if (N && N.ttsCancel) N.ttsCancel(); else if (window.speechSynthesis) speechSynthesis.cancel(); } catch (e) {}
    Object.keys(ttsWait).forEach(function (k) { var w = ttsWait[k]; delete ttsWait[k]; w("stopped"); });
    this._state(false);
  },
  busy: function () { return this.running || this.q.length > 0; },
  _state: function (on) { if (this.onState) this.onState(on); },
  _run: async function () {
    if (this.running) return;
    this.running = true; this._state(true);
    var g = this.gen;
    while (this.q.length && g === this.gen) {
      var it = this.q.shift();
      this._prepare(it);
      if (this.q[0]) this._prepare(this.q[0]);
      if (this.q[1]) this._prepare(this.q[1]);
      var r = await it.prep;
      if (g !== this.gen) break;
      if (LAT && LAT.sent && !LAT.tts) { LAT.tts = now(); LAT.ttsNative = r.ms; LAT.warm = r.warm; LAT.first = r.first; }
      this.lastText = it.text; this.recent.push(it.text); if (this.recent.length > 3) this.recent.shift();
      try {
        if (r.url) await playUrl(r.url, r.rate || 1);
        else await speakAndroid(it.text, r.android || "hi-IN");
      } catch (e) { console.warn("play", e); }
      if (r.blob) URL.revokeObjectURL(r.url);
    }
    if (g === this.gen) { this.running = false; this._state(false); }
  }
};
function splitSentences(text) {
  var parts = text.match(/[^.!?।॥\n]+[.!?।॥]*["'”’)]*\s*/g) || [text], out = [], cur = "";
  parts.forEach(function (p) { cur += p; if (cur.trim().length >= 40) { out.push(cur.trim()); cur = ""; } });
  if (cur.trim()) { if (out.length && cur.trim().length < 15) out[out.length - 1] += " " + cur.trim(); else out.push(cur.trim()); }
  return out;
}
function playUrl(url, rate) {
  return new Promise(function (res) {
    var done = false; function fin() { if (done) return; done = true; player.onended = player.onerror = null; res(); }
    player.onended = fin; player.onerror = fin;
    player.src = url; player.playbackRate = rate || 1;
    var p = player.play(); if (p && p.catch) p.catch(function (e) { console.warn("audio play", e); fin(); });
    setTimeout(fin, 120000);
  });
}
function speakAndroid(text, lang) {
  markPlay();
  return new Promise(function (res) {
    if (N && N.ttsSpeak) {
      var id = "s" + uid(); ttsWait[id] = function () { res(); };
      N.ttsSpeak(id, text, lang, S.speed); setTimeout(function () { if (ttsWait[id]) { delete ttsWait[id]; res(); } }, 90000);
    } else if (window.speechSynthesis) {
      var u = new SpeechSynthesisUtterance(text); u.lang = lang; u.rate = S.speed; u.onend = u.onerror = function () { res(); }; speechSynthesis.speak(u);
    } else res();
  });
}
window.Puna.Speaker = Speaker;

/* ------------------------------------------------------------------ speech recognition */
var SRec = { id: 0, h: null, web: null };
window.__punaSR = function (id, type, p) {
  if (id !== SRec.id || !SRec.h) return;
  var h = SRec.h;
  if (type === "start") h.onStart && h.onStart();
  else if (type === "result") { if (p.final) h.onFinal && h.onFinal(p.text); else h.onPartial && h.onPartial(p.text); }
  else if (type === "speechend") h.onSpeechEnd && h.onSpeechEnd();
  else if (type === "error") h.onError && h.onError(p && p.error, p && p.message);
  else if (type === "nomatch") h.onError && h.onError("no-speech");
  else if (type === "end") { var e = h.onEnd; SRec.h = null; e && e(); }
};
window.__punaRms = function (db) { document.documentElement.style.setProperty("--lvl", Math.max(0, Math.min(1, (db + 2) / 12)).toFixed(2)); };
function listen(lang, h, opt) {
  stopListening(true);
  window.Puna.listens = (window.Puna.listens || 0) + 1;
  SRec.id++; SRec.h = h;
  if (opt && opt.silence && N && N.srStartEx) { N.srStartEx(SRec.id, lang, opt.silence); return; }
  if (N && N.srStart) { N.srStart(SRec.id, lang); return; }
  var W = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!W) { setTimeout(function () { window.__punaSR(SRec.id, "error", { error: "unsupported", message: "Is browser mein mic nahi" }); window.__punaSR(SRec.id, "end"); }, 0); return; }
  var r = new W(), my = SRec.id; SRec.web = r; r.lang = lang; r.interimResults = true;
  r.onstart = function () { window.__punaSR(my, "start"); };
  r.onresult = function (e) { var x = e.results[e.results.length - 1]; window.__punaSR(my, "result", { final: x.isFinal, text: x[0].transcript }); };
  r.onerror = function (e) { window.__punaSR(my, "error", { error: e.error }); };
  r.onend = function () { window.__punaSR(my, "end"); };
  r.start();
}
function stopListening(abort) {
  if (!SRec.h) return;
  if (N && N.srStop) { abort ? N.srAbort(SRec.id) : N.srStop(SRec.id); }
  else if (SRec.web) { try { abort ? SRec.web.abort() : SRec.web.stop(); } catch (e) {} }
  if (abort) { SRec.h = null; }
}

/* ------------------------------------------------------------------ AI brain (Groq / Gemini) */
var MODELS = load("puna.models", { groq: [], gemini: [] });
var GROQ = "https://api.groq.com/openai/v1";
var GEM = "https://generativelanguage.googleapis.com/v1beta";
function NoKey() { var e = new Error("NOKEY"); e.nokey = true; return e; }
function brainKind() {
  if (S.brain === "gemini" && S.geminiKey) return "gemini";
  if (S.groqKey) return "groq";
  if (S.geminiKey) return "gemini";
  return null;
}
function usableGroq(id) { return !/whisper|guard|orpheus|tts|safeguard|prompt-guard|allam|playai/i.test(id); }
function groqModel(kind) {
  var list = MODELS.groq || [], want;
  if (kind === "chat" && S.model) return S.model;
  if (kind === "fast") want = ["openai/gpt-oss-20b", "llama-3.1-8b-instant", "openai/gpt-oss-120b"];
  else if (S.level === "low") want = ["openai/gpt-oss-20b", "openai/gpt-oss-120b"];
  else want = ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"];
  if (!list.length) return want[0];
  for (var i = 0; i < want.length; i++) if (list.indexOf(want[i]) >= 0) return want[i];
  var any = list.filter(usableGroq); return any[0] || want[0];
}
function effort() { return S.level === "high" ? "medium" : "low"; }
function gemModel() {
  var list = (MODELS.gemini || []).filter(function (m) { return /^gemini-[\d.]+-flash$/.test(m); });
  list.sort(function (a, b) { return parseFloat(b.split("-")[1]) - parseFloat(a.split("-")[1]); });
  return (S.model && S.model.indexOf("gemini") === 0) ? S.model : (list[0] || "gemini-2.5-flash");
}
async function httpErr(r, who) {
  var t = ""; try { t = await r.text(); } catch (e) {}
  var m = t; try { var j = JSON.parse(t); m = (j.error && (j.error.message || j.error)) || t; } catch (e) {}
  var e = new Error(who + " " + r.status + ": " + String(m).slice(0, 300)); e.status = r.status; e.body = t; throw e;
}
async function fetchModels(kind) {
  if (kind === "groq") {
    var r = await fetch(GROQ + "/models", { headers: { Authorization: "Bearer " + S.groqKey } });
    if (!r.ok) await httpErr(r, "Groq");
    var j = await r.json(); MODELS.groq = (j.data || []).map(function (m) { return m.id; }).sort();
  } else {
    var r2 = await fetch(GEM + "/models?pageSize=200", { headers: { "x-goog-api-key": S.geminiKey } });
    if (!r2.ok) await httpErr(r2, "Gemini");
    var j2 = await r2.json(); MODELS.gemini = (j2.models || []).filter(function (m) { return (m.supportedGenerationMethods || []).indexOf("generateContent") >= 0; }).map(function (m) { return m.name.replace("models/", ""); });
  }
  save("puna.models", MODELS); fillModelSelect(); return MODELS[kind];
}
async function readSSE(resp, onData, signal) {
  var rd = resp.body.getReader(), dec = new TextDecoder(), buf = "";
  for (;;) {
    var r = await rd.read(); if (r.done) break;
    buf += dec.decode(r.value, { stream: true });
    var lines = buf.split(/\r?\n/); buf = lines.pop();
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i].trim(); if (l.indexOf("data:") !== 0) continue;
      var d = l.slice(5).trim(); if (d === "[DONE]") return;
      try { onData(JSON.parse(d)); } catch (e) {}
    }
  }
}
// Test hook: a scripted streaming "LLM" (time-to-first-token + chars/sec) so the voice pipeline can be timed without a key.
async function mockChat(messages, o) {
  var m = window.Puna.mockLLM, model = o.model || (brainKind() === "gemini" ? gemModel() : groqModel(o.fast ? "fast" : "chat"));
  (window.Puna.mockCalls = window.Puna.mockCalls || []).push({ model: model, fast: !!o.fast, search: !!o.search, max: o.max, effort: o.effort, stream: !!o.onDelta, sys: (messages[0] && messages[0].content || "").slice(0, 4000) });
  await sleep(m.ttft || 300);
  if (o.json) return { text: m.json || '{"add":[],"remove":[],"settings":{}}', sources: [], model: "mock:" + model };
  var t = (o.search ? m.searchText : null) || m.text || "Haan, main yahan hoon.", out = "", per = Math.max(1, Math.round((m.cps || 600) / 50));
  if (!o.onDelta) { await sleep(t.length / (m.cps || 600) * 1000); return { text: t, sources: [], model: "mock:" + model }; }
  for (var i = 0; i < t.length; i += per) {
    if (o.signal && o.signal.aborted) { var ab = new Error("aborted"); ab.name = "AbortError"; throw ab; }
    var ch = t.slice(i, i + per); out += ch; o.onDelta(ch, out); await sleep(20);
  }
  return { text: out, sources: [], model: "mock:" + model };
}
var FAST_CHAIN = ["openai/gpt-oss-20b", "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b"];
async function groqChat(messages, o) {
  if (window.Puna.mockLLM) return mockChat(messages, o);
  var model = o.model || groqModel(o.fast ? "fast" : "chat"), oss = /gpt-oss/.test(model);
  var body = { model: model, messages: messages, stream: !!o.onDelta, temperature: o.temperature == null ? 0.7 : o.temperature, max_completion_tokens: o.max || 2048 };
  if (oss) { body.reasoning_effort = o.effort || effort(); body.include_reasoning = false; }
  if (o.json) body.response_format = { type: "json_object" };
  if (o.search) { body.tools = [{ type: "browser_search" }]; body.tool_choice = "required"; body.stream = false; delete body.include_reasoning; body.reasoning_effort = "low"; }
  if (!oss) delete body.reasoning_effort;
  var send = async function (b) {
    return fetch(GROQ + "/chat/completions", { method: "POST", signal: o.signal, headers: { Authorization: "Bearer " + S.groqKey, "Content-Type": "application/json" }, body: JSON.stringify(b) });
  };
  var r = await send(body);
  if (!r.ok && r.status === 400 && (body.include_reasoning !== undefined || body.response_format)) {
    var t = await r.text();
    if (/include_reasoning|reasoning_effort|response_format|json/i.test(t)) { delete body.include_reasoning; delete body.response_format; r = await send(body); }
    else { var e0 = new Error("Groq 400: " + t.slice(0, 300)); e0.status = 400; throw e0; }
  }
  if (!r.ok) await httpErr(r, "Groq");
  if (body.stream) {
    var text = "";
    await readSSE(r, function (j) { var d = j.choices && j.choices[0] && j.choices[0].delta; if (d && d.content) { text += d.content; o.onDelta(d.content, text); } }, o.signal);
    return { text: text, sources: [], model: model };
  }
  var j = await r.json(), msg = (j.choices && j.choices[0] && j.choices[0].message) || {}, src = [];
  (msg.executed_tools || []).forEach(function (t) {
    var sr = t.search_results; var arr = (sr && (sr.results || sr)) || [];
    if (Array.isArray(arr)) arr.forEach(function (x) { if (x && x.url) src.push({ title: x.title || x.url, url: x.url }); });
  });
  var txt = msg.content || "";
  if (!src.length) (txt.match(/https?:\/\/[^\s)\]】>"']+/g) || []).forEach(function (u) { src.push({ title: u.replace(/^https?:\/\/(www\.)?/, "").split("/")[0], url: u }); });
  if (o.onDelta && txt) o.onDelta(txt, txt);
  return { text: txt, sources: dedupe(src), model: model };
}
function dedupe(src) { var seen = {}, out = []; src.forEach(function (s) { var k = s.url.split("#")[0]; if (!seen[k]) { seen[k] = 1; out.push(s); } }); return out.slice(0, 8); }
async function groqSearch(messages, o) {
  try { return await groqChat(messages, Object.assign({}, o, { search: true, model: (o.fast || S.level === "low") ? "openai/gpt-oss-20b" : "openai/gpt-oss-120b" })); }
  catch (e) {
    if (e.status === 401 || o.signal && o.signal.aborted) throw e;
    var alt = (MODELS.groq || []).filter(function (m) { return /^groq\/compound/.test(m); })[0];
    if (alt) { try { return await groqChat(messages, Object.assign({}, o, { model: alt })); } catch (e2) {} }
    var r = await groqChat(messages, o); r.noWeb = true; return r;
  }
}
async function gemChat(messages, o) {
  if (window.Puna.mockLLM) return mockChat(messages, o);
  var sys = messages.filter(function (m) { return m.role === "system"; }).map(function (m) { return m.content; }).join("\n\n");
  var contents = messages.filter(function (m) { return m.role !== "system"; }).map(function (m) { return { role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content }] }; });
  var model = gemModel(), stream = !!o.onDelta && !o.search;
  var body = { contents: contents, generationConfig: { temperature: o.temperature == null ? 0.7 : o.temperature } };
  if (sys) body.systemInstruction = { parts: [{ text: sys }] };
  if (o.search) body.tools = [{ google_search: {} }];
  if (o.json) body.generationConfig.responseMimeType = "application/json";
  if (o.fast && /2\.5-flash/.test(model)) body.generationConfig.thinkingConfig = { thinkingBudget: 0 };   // no "thinking" pause in Fast voice mode
  var url = GEM + "/models/" + model + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
  var post = function () { return fetch(url, { method: "POST", signal: o.signal, headers: { "x-goog-api-key": S.geminiKey, "Content-Type": "application/json" }, body: JSON.stringify(body) }); };
  var r = await post();
  if (!r.ok && r.status === 400 && body.generationConfig.thinkingConfig) { delete body.generationConfig.thinkingConfig; r = await post(); }
  if (!r.ok) await httpErr(r, "Gemini");
  var text = "", src = [];
  function eat(j) {
    var c = j.candidates && j.candidates[0]; if (!c) return;
    ((c.content && c.content.parts) || []).forEach(function (p) { if (p.text && !p.thought) { text += p.text; if (o.onDelta) o.onDelta(p.text, text); } });
    var gm = c.groundingMetadata; if (gm && gm.groundingChunks) gm.groundingChunks.forEach(function (g) { if (g.web) src.push({ title: g.web.title || g.web.uri, url: g.web.uri }); });
  }
  if (stream) await readSSE(r, eat, o.signal); else eat(await r.json());
  return { text: text, sources: dedupe(src), model: model };
}
var Brain = {
  ask: async function (messages, o) {
    var k = brainKind(); if (!k) throw NoKey();
    if (k === "gemini") return gemChat(messages, o);
    if (o.search) return groqSearch(messages, o);
    if (!o.fast || o.model) return groqChat(messages, o);
    // Fast: try the quick models in order; skip one the key can't use (model not found / no access)
    var list = FAST_CHAIN.filter(function (m) { return !(MODELS.groq || []).length || MODELS.groq.indexOf(m) >= 0; }), last = null;
    if (!list.length) list = FAST_CHAIN.slice(0, 1);
    for (var i = 0; i < list.length; i++) {
      try { return await groqChat(messages, Object.assign({}, o, { model: list[i] })); }
      catch (e) { last = e; if (!(e.status === 400 || e.status === 403 || e.status === 404) || !/model|access|decommission|not.?found|permission/i.test(e.message)) throw e; }
    }
    throw last;
  }
};
window.Puna.Brain = Brain;
function friendlyErr(e) {
  if (e.nokey) return "Pehle Settings mein Groq key daalo 🙏 (Settings → AI Brain → Groq API key). Key free milti hai: console.groq.com/keys";
  if (e.name === "AbortError") return "Ruk gaya.";
  if (e.status === 401 || /invalid api key|API key not valid/i.test(e.message)) return "API key galat lag rahi hai — Settings mein check karo.";
  if (e.status === 429) return "Thoda ruk kar try karo — API limit (429) lag gayi.";
  if (/Failed to fetch|NetworkError|network/i.test(e.message)) return "Internet nahi mil raha. Connection check karo.";
  return "Gadbad ho gayi: " + e.message;
}
// Web search only when the question needs fresh facts. Strong words alone trigger it; a time word ("aaj", "abhi",
// "latest") needs a topic word too, so small talk like "abhi kya kar rahi ho" or "aaj main thak gayi" stays fast.
var WEB_STRONG = /\b(news|khabar|khabre|khabrein|samachar|headlines?|weather|mausam|temperature|forecast|baarish hogi|score|scorecard|sensex|nifty|share market|stock market|stock price|share price|election|chunav|kaun jeeta|who won|trending|breaking|exchange rate|gold rate|petrol price|diesel price|sone ka bhav)\b|समाचार|ख़बर|खबर|मौसम|ଖବର|ପାଣିପାଗ|খবর|আবহাওয়া/i;
var WEB_TIME = /\b(aaj|aaj ki|aaj ka|today|tonight|latest|current|currently|abhi|live|recent|recently|kal|yesterday|tomorrow|this week|is hafte|2025|2026|2027|right now)\b|आज|अभी|ଆଜି|আজ/i;
var WEB_TOPIC = /\b(price|prices|rate|rates|bhav|daam|kitne ka|kimat|keemat|result|results|match|game|update|updates|happened|hua|hui|release|released|launch|launched|ipl|cricket|movie|film|box office|dollar|rupee|gold|petrol|stock|share|weather|traffic|flight|train|holiday|chhutti|festival|tyohar|kaun|who is|what is the|minister|president|pm|cm)\b|कीमत|दाम|रिजल्ट|मैच|क्या हुआ/i;
function needsWeb(t) {
  if (S.web === "always") return true; if (S.web === "never") return false;
  t = String(t || ""); return WEB_STRONG.test(t) || (WEB_TIME.test(t) && WEB_TOPIC.test(t));
}
window.Puna.needsWeb = needsWeb;

function systemPrompt(mode) {
  var p = persona(S.voice), L = LBYID[S.replyLang] || LBYID.auto;
  var now = new Date().toLocaleString("en-IN", { timeZone: "Asia/Kolkata", weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "numeric", minute: "2-digit" });
  var who = S.callMe ? "Call the user \"" + S.callMe + "\"." : "";
  var g = S.gender === "female" ? "The user is female — use feminine grammar when addressing her in Hindi/Hinglish (e.g. 'aap kaisi ho')." :
    S.gender === "male" ? "The user is male — use masculine grammar when addressing him in Hindi/Hinglish (e.g. 'aap kaise ho')." : "";
  var mem = MEM.length ? "Things you know about the user (memory):\n" + MEM.slice(-40).map(function (m) { return "- " + m.text; }).join("\n") : "";
  var style = (mode === "voice" && S.fast)
    ? "This is a live VOICE conversation (Fast mode): answer in 1-3 short spoken sentences like a caring friend, and make the FIRST sentence very short (under 10 words) so it can be spoken instantly. Only give long answers, steps or lists when the user explicitly asks. No tables, markdown symbols, emojis or URLs."
    : mode === "voice"
    ? "This is a VOICE conversation: keep answers short and natural (1-4 sentences) like a caring friend talking, unless the user asks for details, steps, a list or a recipe. No tables, no markdown symbols, no emojis, no URLs read aloud."
    : "This is the text chat: use helpful Markdown (lists, code blocks) when useful; be clear and complete but not padded.";
  return [
    "You are Puna, a warm, smart and capable personal AI assistant inside the 'Puna AI' Android app.",
    "Current date/time in India: " + now + ".",
    "You speak with the voice named \"" + p.name + "\" (" + (p.g === "F" ? "female" : "male") + "). When talking about yourself in Hindi/Hinglish/Odia use " + (p.g === "F" ? "feminine forms (e.g. 'main karungi', 'main bataungi')" : "masculine forms (e.g. 'main karunga', 'main bataunga')") + ". If asked your name, you are Puna speaking in " + p.name + "'s voice.",
    who, g, "LANGUAGE: " + L.rule, style, mem,
    "If the user asks you to remember something, confirm warmly in one line (the app saves it to memory automatically).",
    "The user can change your voice by saying e.g. 'Rohan se baat karni hai'. Voices: " + VOICES.map(function (v) { return v.name; }).join(", ") + ". The Broadcast tab turns any topic into a multi-voice audio or video to share on WhatsApp/Telegram. The Agent tab (and voice commands like 'WhatsApp kholo') operates the phone: open apps, call, WhatsApp/SMS, alarms, torch, volume, settings, YouTube, Maps, tapping/typing on screen. The Builder (app/website IDE) is coming soon.",
    "Never invent current events, prices or scores. When web results are available use them and mention the source names briefly."
  ].filter(Boolean).join("\n");
}

/* ------------------------------------------------------------------ memory extraction */
var MEM_HINT = /\b(mera|meri|mere|mujhe|main|mai|hum|my|i am|i'm|i like|i love|i live|yaad|remember|naam|name|call me|bulao|hamesha|always|from now|ab se|pasand|favou?rite|birthday|janamdin|rehti|rehta|kaam|job|study|padhai|wife|husband|beti|beta|maa|papa|dost|friend)\b|मेरा|मेरी|मुझे|याद|नाम|हमेशा|ମୋର|ମନେ|ମୁଁ/i;
function addMemory(text, src) {
  text = String(text || "").trim(); if (!text) return false;
  var low = text.toLowerCase();
  if (MEM.some(function (m) { return m.text.toLowerCase() === low; })) return false;
  MEM.push({ id: uid(), text: text, at: Date.now(), src: src || "chat" }); saveMem(); return true;
}
async function extractMemory(userText, replyText) {
  if (!S.memoryOn || !brainKind() || !MEM_HINT.test(userText)) return;
  var list = MEM.map(function (m) { return { id: m.id, text: m.text }; });
  var prompt = "You maintain a long-term memory of durable facts about the USER of a voice assistant (name, what to call them, language preference, likes/dislikes, family, work, city, goals, birthdays, standing instructions).\n" +
    "Existing memory (JSON): " + JSON.stringify(list) + "\n" +
    "Latest user message: " + JSON.stringify(userText) + "\nAssistant reply: " + JSON.stringify((replyText || "").slice(0, 600)) + "\n" +
    "Return ONLY JSON: {\"add\":[short third-person facts in English, e.g. \"User's name is Rajesh\", \"User wants replies only in Hindi Devanagari\"], \"remove\":[ids of memories that the new message clearly contradicts or the user asked to forget], \"settings\":{\"callMe\":string|null, \"replyLang\":one of [auto,hinglish,hi,en,or,bn,mr,gu,ta,te,kn,ml,pa,ur]|null}}. " +
    "Add nothing for small talk, questions, or temporary things. Never duplicate an existing memory.";
  try {
    var r = await Brain.ask([{ role: "system", content: "You output strict JSON only." }, { role: "user", content: prompt }], { json: true, fast: true, temperature: 0, max: 400, effort: "low" });
    var t = r.text, j = JSON.parse(t.slice(t.indexOf("{"), t.lastIndexOf("}") + 1));
    var added = 0;
    (j.remove || []).forEach(function (id) { var i = MEM.findIndex(function (m) { return m.id === id; }); if (i >= 0) { MEM.splice(i, 1); } });
    (j.add || []).forEach(function (f) { if (addMemory(f, "auto")) added++; });
    var st = j.settings || {};
    if (st.callMe && !S.callMe) { S.callMe = st.callMe; }
    if (st.replyLang && LBYID[st.replyLang] && st.replyLang !== S.replyLang) { S.replyLang = st.replyLang; }
    saveS(); saveMem(); syncSettingsUI();
    if (added) toast("🧠 Memory mein save: " + MEM[MEM.length - 1].text);
  } catch (e) { console.warn("memory", e); }
}
window.Puna.memory = { add: addMemory, list: function () { return MEM; } };

/* ------------------------------------------------------------------ markdown */
function inl(s) {
  return s.replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>").replace(/(^|[^*])\*([^*\s][^*]*)\*/g, "$1<i>$2</i>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a class="ext" href="$2">$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a class="ext" href="$2">$2</a>')
    .replace(/【[^】]*】/g, "");
}
function md(src) {
  var parts = String(src || "").split(/```/), html = "";
  parts.forEach(function (part, i) {
    if (i % 2 === 1) { var nl = part.indexOf("\n"), code = nl >= 0 ? part.slice(nl + 1) : part; html += "<pre><code>" + esc(code.replace(/\n$/, "")) + "</code></pre>"; return; }
    var lines = esc(part).split("\n"), list = null, para = [], tbl = [];
    function flushP() { if (para.length) { html += "<p>" + inl(para.join("<br>")) + "</p>"; para = []; } }
    function flushL() { if (list) { html += "</" + list + ">"; list = null; } }
    function flushT() {
      if (!tbl.length) return;
      var rows = tbl.filter(function (r) { return !/^\|?\s*:?-{2,}/.test(r); });
      html += "<table>" + rows.map(function (r, k) { var c = r.replace(/^\||\|$/g, "").split("|"); return "<tr>" + c.map(function (x) { return (k ? "<td>" : "<th>") + inl(x.trim()) + (k ? "</td>" : "</th>"); }).join("") + "</tr>"; }).join("") + "</table>";
      tbl = [];
    }
    lines.forEach(function (l) {
      var m;
      if (/^\s*\|.*\|\s*$/.test(l)) { flushP(); flushL(); tbl.push(l.trim()); return; } else flushT();
      if ((m = l.match(/^\s*(#{1,4})\s+(.*)$/))) { flushP(); flushL(); html += "<h3>" + inl(m[2]) + "</h3>"; }
      else if ((m = l.match(/^\s*[-*•]\s+(.*)$/))) { flushP(); if (list !== "ul") { flushL(); html += "<ul>"; list = "ul"; } html += "<li>" + inl(m[1]) + "</li>"; }
      else if ((m = l.match(/^\s*\d+[.)]\s+(.*)$/))) { flushP(); if (list !== "ol") { flushL(); html += "<ol>"; list = "ol"; } html += "<li>" + inl(m[1]) + "</li>"; }
      else if ((m = l.match(/^\s*&gt;\s?(.*)$/))) { flushP(); flushL(); html += "<blockquote>" + inl(m[1]) + "</blockquote>"; }
      else if (!l.trim()) { flushP(); flushL(); }
      else { flushL(); para.push(l); }
    });
    flushT(); flushP(); flushL();
  });
  return html;
}
window.Puna.md = md;
document.addEventListener("click", function (e) {
  var a = e.target.closest && e.target.closest("a[href^='http']");
  if (!a) return;
  e.preventDefault();
  if (N && N.openUrl) N.openUrl(a.href); else window.open(a.href, "_blank");
});
function copyText(t) { if (N && N.copy) N.copy(t); else if (navigator.clipboard) navigator.clipboard.writeText(t).catch(function () {}); toast("Copy ho gaya"); }

/* ------------------------------------------------------------------ local commands */
var INTENT_VOICE = /(se baat|baat kar|baat karni|baat karna|ki awaaz|ki aawaz|ki aavaz|ki voice|voice|awaaz|aawaz|aavaz|talk to|talk with|speak with|speak like|switch|change|chahiye|chahie|bulao|lagao|use karo|से बात|की आवाज़|की आवाज|आवाज़|आवाज|ସହ କଥା|ସ୍ୱର|কথা|আওয়াজ)/i;
function voiceNamesIn(text) {
  var low = text.toLowerCase(), found = [];
  VOICES.forEach(function (v) {
    [v.name.toLowerCase(), v.hi].concat(v.id === "anaya" ? ["ananya", "अनन्या"] : v.id === "christina" ? ["kristina", "cristina", "christine"] : v.id === "primosh" ? ["pramosh", "primos", "प्रमोश"] : v.id === "priyanshi" ? ["priyanshee", "प्रियंशी"] : v.id === "emma" ? ["ema", "एमा"] : v.id === "sonali" ? ["ସୋନାଲୀ", "সোনালী"] : [])
      .forEach(function (n) { var i = low.lastIndexOf(n); if (i >= 0) found.push({ v: v, i: i }); });
  });
  found.sort(function (a, b) { return b.i - a.i; });
  return found.map(function (f) { return f.v; });
}
function confirmVoiceLine(v) {
  var f = v.g === "F", L = S.replyLang;
  if (L === "hi") return "नमस्ते! अब से मैं " + v.hi + " की आवाज़ में बात " + (f ? "करूँगी" : "करूँगा") + "। बताइए, ये आवाज़ कैसी लगी?";
  if (L === "en") return "Hello! From now on I'll talk in " + v.name + "'s voice. How do you like it?";
  if (L === "or") return "ନମସ୍କାର! ଏବେଠୁ ମୁଁ " + v.name + " ସ୍ୱରରେ କଥା ହେବି। କେମିତି ଲାଗିଲା?";
  return "Hello! Ab se main " + v.name + " ki awaaz mein baat " + (f ? "karungi" : "karunga") + ". Batao, ye awaaz kaisi lagi?" + (v.love ? " 💖" : "");
}
var LANG_EN = { hinglish: "Hinglish (Roman script)", hi: "Hindi (Devanagari script)", en: "English", or: "Odia", bn: "Bengali", mr: "Marathi", gu: "Gujarati", ta: "Tamil", te: "Telugu", kn: "Kannada", ml: "Malayalam", pa: "Punjabi", ur: "Urdu" };
var LANG_WORDS = [["hinglish", /hinglish|roman/i], ["hi", /hindi|devanagari|हिंदी|हिन्दी/i], ["en", /english|angrezi|अंग्रेज़ी/i], ["or", /odia|odiya|oriya|ଓଡ଼ିଆ|ओड़िया|ओडिया/i],
  ["bn", /bangla|bengali|বাংলা/i], ["mr", /marathi|मराठी/i], ["gu", /gujarati|ગુજરાતી/i], ["ta", /tamil/i], ["te", /telugu/i], ["kn", /kannada/i], ["ml", /malayalam/i], ["pa", /punjabi/i], ["ur", /urdu/i]];
function localCommand(text) {
  var t = text.trim(), low = t.toLowerCase();
  if (/^(chup|ruko|ruk jao|stop|bas|band karo|chup ho jao|चुप|रुको|ବନ୍ଦ କର)[.!। ]*$/i.test(t)) return { reply: "", stop: true };
  var names = voiceNamesIn(t);
  if (names.length && INTENT_VOICE.test(t) && !/mera naam|my name|naam hai|i am /i.test(low)) {
    var v = names[0]; S.voice = v.id; saveS(); syncSettingsUI();
    return { reply: confirmVoiceLine(v), voice: v.id };
  }
  var sm = low.match(/(?:speed|raftaar|raftar|gati)\D{0,12}(\d(?:\.\d)?)/);
  if (sm) { var sp = Math.max(0.8, Math.min(1.6, parseFloat(sm[1]))); S.speed = Math.round(sp * 10) / 10; saveS(); syncSettingsUI(); return { reply: "Theek hai, speed ab " + S.speed + "× hai." }; }
  if (/(tez|fast|jaldi) bolo|speed badha|speed increase/i.test(low)) { S.speed = Math.min(1.6, Math.round((S.speed + 0.1) * 10) / 10); saveS(); syncSettingsUI(); return { reply: "Ok, ab thoda tez — " + S.speed + "×." }; }
  if (/(dheere|slow|aaram se) bolo|speed kam|speed decrease/i.test(low)) { S.speed = Math.max(0.8, Math.round((S.speed - 0.1) * 10) / 10); saveS(); syncSettingsUI(); return { reply: "Ok, ab thoda dheere — " + S.speed + "×." }; }
  if (/(ab se|from now|hamesha|always|sirf|only|अब से|हमेशा|ଏବେଠୁ|ସବୁବେଳେ)/i.test(t) && /(baat|bol|reply|jawab|talk|speak|answer|बात|बोल|जवाब|କଥା|ଉତ୍ତର)/i.test(t)) {
    for (var i = 0; i < LANG_WORDS.length; i++) if (LANG_WORDS[i][1].test(t)) {
      var id = LANG_WORDS[i][0]; S.replyLang = id; saveS(); syncSettingsUI();
      addMemory("User wants replies only in " + LANG_EN[id], "command");
      return { reply: id === "hi" ? "ज़रूर! अब से हमारी सारी बातें सिर्फ़ हिन्दी (देवनागरी) में होंगी — और ये मैंने memory में भी save कर लिया है।" : id === "or" ? "ନିଶ୍ଚୟ! ଏବେଠୁ ମୁଁ କେବଳ ଓଡ଼ିଆରେ କଥା ହେବି — memory ରେ ମଧ୍ୟ save କଲି।" : id === "en" ? "Sure! From now on I'll reply only in English — saved to memory too." : "Done! Ab se main " + LBYID[id].label + " mein jawab dungi — memory mein bhi save kar liya.", lang: id };
    }
  }
  if (/(yaad rakh|yaad rakhna|remember (that|this)|याद रख|ମନେ ରଖ)/i.test(t) && !brainKind()) {
    var fact = t.replace(/(please|plz|ye|yeh|isko|is baat ko|ki|ko)?\s*(yaad rakh(na|o|iye)?|remember (that|this)?|याद रखो|याद रखना|ମନେ ରଖ)\s*/ig, " ").trim();
    if (fact.length > 3) { addMemory(fact, "command"); return { reply: "Yaad rakh liya: “" + fact + "” 🧠" }; }
  }
  return null;
}
window.Puna.localCommand = localCommand;

/* ------------------------------------------------------------------ conversations */
function curConv() {
  var c = CONVS.find(function (x) { return x.id === CUR; });
  if (!c) { c = { id: uid(), title: "Nayi chat", created: Date.now(), updated: Date.now(), messages: [] }; CONVS.unshift(c); CUR = c.id; saveConvs(); }
  return c;
}
function newConv() { var c = { id: uid(), title: "Nayi chat", created: Date.now(), updated: Date.now(), messages: [] }; CONVS.unshift(c); CUR = c.id; saveConvs(); renderChat(); return c; }
var active = { ctrl: null, busy: false, mode: null };
function stopAll() {
  if (active.ctrl) { try { active.ctrl.abort(); } catch (e) {} }
  active.ctrl = null; active.busy = false;
  Speaker.stop(); stopListening(true); setOrb("idle"); voiceLoop = false;
  if (typeof agentStop === "function") agentStop("stopAll");
}
window.Puna.stopAll = stopAll;

async function ask(text, mode, opt) {
  opt = opt || {};
  text = String(text || "").trim(); if (!text && !opt.regenerate) return;
  var c = curConv();
  if (active.busy) { if (active.ctrl) active.ctrl.abort(); }
  Speaker.stop();
  if (!opt.regenerate) {
    c.messages.push({ id: uid(), role: "user", content: text, at: Date.now(), via: mode });
    if (c.title === "Nayi chat") c.title = text.slice(0, 42);
  }
  c.updated = Date.now(); saveConvs();
  var cmd = opt.regenerate ? null : localCommand(text);
  if (cmd && cmd.stop) { c.messages.pop(); saveConvs(); stopAll(); setStatus("Theek hai, chup ho gayi."); return; }
  var am = { id: uid(), role: "assistant", content: "", at: Date.now(), voice: S.voice, sources: [], via: mode };
  c.messages.push(am); renderChat(); showReply(am, true);
  var speak = mode === "voice" ? S.autoSpeak : false;
  if (!cmd && !opt.regenerate && mode === "voice" && S.agentVoice && N && N.agentTool && isPhoneTask(text) && (brainKind() || window.Puna.mockAgent)) {
    am.content = "🤖 Phone par kar rahi hoon: " + text; showReply(am, true); setOrb("thinking"); setStatus("Agent kaam kar raha hai… (⏹ Stop)");
    voiceLoop = false;
    var ar = await agentRun(text, { voice: true });
    am.content = (ar && ar.summary) || "…"; am.model = "agent"; finishTurn(c, am, mode, speak, true); return;
  }
  if (cmd && (!cmd.lang || !brainKind())) {
    am.content = cmd.reply; am.voice = S.voice; finishTurn(c, am, mode, speak, true); return;
  }
  var ctrl = new AbortController(); active.ctrl = ctrl; active.busy = true; active.mode = mode;
  var fast = mode === "voice" && S.fast;
  if (speak && N && N.edgeWarm) N.edgeWarm(2);          // open the voice connection while the brain thinks
  setOrb("thinking"); setStatus(needsWeb(text) ? "Web par dhoondh rahi hoon… 🌐" : "Soch rahi hoon…");
  var hist = c.messages.slice(0, -1).filter(function (m) { return !m.error && m.content; }).slice(fast ? -10 : -16).map(function (m) { return { role: m.role, content: m.content }; });
  var msgs = [{ role: "system", content: systemPrompt(mode) }].concat(hist);
  var search = needsWeb(hist.length ? hist[hist.length - 1].content : text);
  var pid = S.voice, first = true;
  if (speak) LAT = { t0: now(), speechEnd: opt.speechEnd || null, search: search };
  if (speak && fast && search) { Speaker.say(fillerLine(), pid); if (LAT) { LAT.filler = true; LAT.sent = now(); } }
  try {
    var r = await Brain.ask(msgs, {
      signal: ctrl.signal, search: search, fast: fast, effort: fast ? "low" : undefined, max: fast ? 400 : undefined,
      onDelta: search ? null : function (d, full) {
        if (ctrl !== active.ctrl) return;
        if (first) { first = false; if (LAT && !LAT.tok) LAT.tok = now(); setOrb(speak ? "speaking" : "idle"); setStatus(""); }
        am.content = full; updateStreaming(am);
        if (speak) Speaker.feed(d, pid);
      }
    });
    if (ctrl !== active.ctrl) return;
    am.content = r.text || am.content || "…"; am.sources = r.sources || []; am.model = r.model;
    if (LAT) { LAT.model = r.model; if (!LAT.tok) LAT.tok = now(); if (LAT.play) renderLat(); }
    if (search && r.noWeb) am.content += "\n\n_(Web search abhi nahi chal paya — ye jawab bina live web ke hai.)_";
    if (speak) { if (search) Speaker.say(am.content, pid); else Speaker.flush(pid); }
    finishTurn(c, am, mode, false);
    // memory is learnt in the background, after the reply has started speaking — never in front of it
    var memText = am.content, myLat = LAT, waited = 0;
    (function memLater() {   // wait until the first words are actually playing (or speech is over), then learn
      if (!speak || (myLat && myLat.play) || !Speaker.busy() || waited > 12000) { setTimeout(function () { extractMemory(text, memText); }, speak ? 400 : 100); return; }
      waited += 250; setTimeout(memLater, 250);
    })();
  } catch (e) {
    if (ctrl.signal.aborted) { am.content = am.content || "(ruk gaya)"; finishTurn(c, am, mode, false); return; }
    am.content = friendlyErr(e); am.error = true;
    finishTurn(c, am, mode, speak, true);
  } finally { if (ctrl === active.ctrl) { active.busy = false; active.ctrl = null; } }
}
window.Puna.ask = ask;
function fillerLine() {
  var f = persona(S.voice).g === "F", L = S.replyLang;
  if (L === "hi") return "एक सेकंड, देख " + (f ? "रही" : "रहा") + " हूँ।";
  if (L === "en") return "One second, let me check.";
  if (L === "or") return "ଟିକେ ରୁହ, ଦେଖୁଛି।";
  return "Ek second, dekh " + (f ? "rahi" : "raha") + " hoon.";
}
function finishTurn(c, am, mode, speakNow, local) {
  c.updated = Date.now(); saveConvs(); renderChat(); showReply(am, false);
  if (speakNow && am.content) Speaker.say(am.content, am.voice || S.voice);
  if (!Speaker.busy()) { setOrb("idle"); maybeContinue(); }
  setStatus(Speaker.busy() ? "" : idleStatus());
}

/* ------------------------------------------------------------------ Assistant view */
var voiceLoop = false;   // true while a voice conversation is going (continuous mode)
function setOrb(st) { $("orbWrap").dataset.state = st; }
function setStatus(t) { $("aStatus").textContent = t; }
function idleStatus() { return brainKind() ? "Mic dabao aur bolo · Tap the mic & talk" : "Settings mein Groq key daalo, phir mic dabao 🎙️"; }
Speaker.onState = function (on) {
  if (on) { if ($("orbWrap").dataset.state !== "listening") setOrb("speaking"); if (S.bargeIn && voiceLoop) setTimeout(startBarge, 300); }
  else { if (!active.busy && $("orbWrap").dataset.state === "speaking") setOrb("idle"); if (!active.busy) { setStatus(idleStatus()); maybeContinue(); } }
};
function maybeContinue() {
  if (!voiceLoop || !S.continuous || active.busy || Speaker.busy() || currentTab !== "assistant") return;
  setTimeout(function () { if (voiceLoop && !active.busy && !Speaker.busy() && !SRec.h && currentTab === "assistant") startVoice(); }, S.fast ? 250 : 450);
}
function startVoice() {
  Speaker.stop();
  voiceLoop = true;
  setOrb("listening"); setStatus("Sun rahi hoon… bolo 👂"); $("aHeard").textContent = "";
  if (N && N.edgeWarm && S.autoSpeak) N.edgeWarm(2);
  var got = "", fired = false, speechEnd = null, stableT = null, h;
  function go(t) {   // Fast mode: send as soon as the final text is in, without waiting for the recogniser to wind down
    if (fired || !t.trim()) return; fired = true; clearTimeout(stableT); ask(t, "voice", { speechEnd: speechEnd || now() });
  }
  listen(S.srLang, h = {
    onStart: function () { setOrb("listening"); },
    onSpeechEnd: function () { speechEnd = now(); },
    onPartial: function (t) {
      $("aHeard").textContent = t;
      if (S.fast) { clearTimeout(stableT); stableT = setTimeout(function () { if (SRec.h === h && !fired) { speechEnd = speechEnd || now(); stopListening(false); } }, 1300); }
    },
    onFinal: function (t) { got = t; $("aHeard").textContent = t; if (S.fast) go(t); },
    onError: function (e, m) {
      if (e === "no-speech") { setStatus("Kuch suna nahi — phir se mic dabao"); voiceLoop = false; }
      else if (e === "not-allowed") { setStatus("Mic ki permission do (Settings › Apps › Puna AI › Permissions)"); voiceLoop = false; }
      else if (e === "language-not-supported") { setStatus("Is bhasha ki speech recognition phone mein nahi — Settings mein mic ki bhasha badlo"); voiceLoop = false; }
      else if (e === "service-not-allowed" || e === "unsupported") { setStatus(m || "Speech recognition nahi mila"); voiceLoop = false; }
      else if (e !== "aborted") { setStatus("Mic error: " + e); voiceLoop = false; }
    },
    onEnd: function () {
      document.documentElement.style.setProperty("--lvl", 0); clearTimeout(stableT);
      if (fired) return;
      if (got.trim()) { fired = true; ask(got, "voice", { speechEnd: speechEnd }); }
      else if ($("orbWrap").dataset.state === "listening") { setOrb("idle"); if (!/Kuch suna|permission|bhasha|error|nahi/i.test($("aStatus").textContent)) setStatus(idleStatus()); }
    }
  }, S.fast ? { silence: 900 } : null);
}
/* Barge-in (beta): keep the mic open while Puna speaks; real speech (not her own voice echoed back) cuts her off. */
function isEcho(t) {
  var w = String(t || "").toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(function (x) { return x.length > 1; });
  if (w.length < 2) return true;
  var spoken = (" " + Speaker.recent.join(" ").toLowerCase() + " ");
  var hit = w.filter(function (x) { return spoken.indexOf(x) >= 0; }).length;
  return hit / w.length >= 0.5;
}
window.Puna.isEcho = isEcho;
function startBarge() {
  if (!S.bargeIn || !voiceLoop || SRec.h || active.mode === "chat" || currentTab !== "assistant") return;
  var got = "", cut = false;
  listen(S.srLang, {
    onPartial: function (t) { if (!cut && Speaker.busy() && !isEcho(t)) { cut = true; Speaker.stop(); if (active.ctrl) { try { active.ctrl.abort(); } catch (e) {} } setOrb("listening"); setStatus("Sun rahi hoon… 👂"); } if (cut) $("aHeard").textContent = t; },
    onFinal: function (t) { got = t; },
    onError: function () {},
    onEnd: function () {
      if (got.trim() && (cut || !isEcho(got))) { $("aHeard").textContent = got; ask(got, "voice"); return; }
      if (Speaker.busy()) setTimeout(startBarge, 150); else if (!active.busy) { setOrb("idle"); maybeContinue(); }
    }
  }, { silence: 900 });
}
$("micBtn").addEventListener("click", function () {
  var st = $("orbWrap").dataset.state;
  if (st === "listening") { voiceLoop = false; stopListening(false); return; }
  // Tap while Puna thinks/speaks = interrupt and listen right away (barge-in by tap)
  if (st === "thinking" || st === "speaking" || Speaker.busy()) {
    if (active.ctrl) { try { active.ctrl.abort(); } catch (e) {} }
    active.ctrl = null; active.busy = false; stopListening(true); Speaker.stop(); startVoice(); return;
  }
  startVoice();
});
$("aTypeForm").addEventListener("submit", function (e) {
  e.preventDefault(); var t = $("aType").value.trim(); if (!t) return; $("aType").value = ""; $("aType").blur();
  voiceLoop = false; $("aHeard").textContent = t; ask(t, "voice");
});
document.querySelectorAll("#aQuick .pill").forEach(function (b) { b.addEventListener("click", function () { voiceLoop = false; $("aHeard").textContent = b.dataset.q; ask(b.dataset.q, "voice"); }); });
var lastReply = null;
function showReply(am, streaming) {
  lastReply = am;
  var p = persona(am.voice || S.voice);
  $("aReplyCard").hidden = false;
  $("aReplyAvatar").textContent = p.name[0]; $("aReplyAvatar").style.background = p.color; $("aReplyName").textContent = p.name;
  $("aReplyTag").textContent = am.error ? "error" : (am.model || "");
  $("aReply").innerHTML = am.content ? md(am.content) : "<p class='hint'>…</p>";
  renderSources($("aSources"), am.sources);
  $("aQuick").hidden = true;
}
function renderSources(el, src) {
  el.innerHTML = "";
  (src || []).forEach(function (s) { var a = document.createElement("a"); a.className = "src"; a.href = s.url; a.textContent = "🔗 " + (s.title || s.url); el.appendChild(a); });
}
var rafPending = false;
function updateStreaming(am) {
  if (rafPending) return; rafPending = true;
  requestAnimationFrame(function () {
    rafPending = false;
    if (lastReply === am) $("aReply").innerHTML = md(am.content);
    var el = document.querySelector('.msg[data-id="' + am.id + '"] .md');
    if (el) { el.innerHTML = md(am.content); scrollChat(); }
  });
}
$("aCopy").addEventListener("click", function () { if (lastReply) copyText(lastReply.content); });
$("aReplay").addEventListener("click", function () { if (lastReply) { Speaker.stop(); Speaker.say(lastReply.content, lastReply.voice || S.voice); } });
$("aStopSpeak").addEventListener("click", function () { voiceLoop = false; stopAll(); });
$("aSpeed").addEventListener("input", function () { S.speed = parseFloat(this.value); saveS(); syncSettingsUI(); });
$("aContinuous").addEventListener("change", function () { S.continuous = this.checked; saveS(); syncSettingsUI(); });
window.__punaLife = function (ev) {
  if (ev === "resume") { if (currentTab === "auto") renderAgent(); if (currentTab === "settings") renderBg(); return; }
  if (ev === "pause") { voiceLoop = false; stopListening(true); if ($("orbWrap").dataset.state === "listening") setOrb("idle"); } };

/* ------------------------------------------------------------------ Chat view */
function scrollChat() { var s = $("cScroll"); s.scrollTop = s.scrollHeight; }
function renderChat() {
  var c = curConv(), box = $("cMsgs");
  $("cTitle").textContent = c.title; $("cSub").textContent = c.messages.length + " messages · " + fmtTime(c.updated);
  if (!c.messages.length) { box.innerHTML = "<div class='empty'>👋 Namaste" + (S.callMe ? " " + esc(S.callMe) : "") + "! Kuch bhi poocho — likh kar ya 🎤 se.<br><small>Aaj ki news, recipes, code, ideas…</small></div>"; return; }
  var lastA = -1; c.messages.forEach(function (m, i) { if (m.role === "assistant") lastA = i; });
  box.innerHTML = c.messages.map(function (m, i) {
    var p = persona(m.voice || S.voice);
    var meta = "<div class='meta'>" + (m.role === "assistant" ? "<span>" + esc(p.name) + (m.model ? " · " + esc(m.model) : "") + "</span>" : "<span>" + fmtTime(m.at) + "</span>") +
      "<button data-act='copy' data-i='" + i + "'>Copy</button>" +
      (m.role === "assistant" ? "<button data-act='speak' data-i='" + i + "'>🔊 Suno</button><button data-act='share' data-i='" + i + "'>Share</button>" : "") +
      (i === lastA && i === c.messages.length - 1 ? "<button data-act='regen' data-i='" + i + "'>↻ Regenerate</button>" : "") + "</div>";
    var src = (m.sources && m.sources.length) ? "<div class='sources'>" + m.sources.map(function (s) { return "<a class='src' href='" + esc(s.url) + "'>🔗 " + esc(s.title || s.url) + "</a>"; }).join("") + "</div>" : "";
    return "<div class='msg " + m.role + "' data-id='" + m.id + "'><div class='md'>" + (m.role === "user" ? "<p>" + esc(m.content).replace(/\n/g, "<br>") + "</p>" : md(m.content || "…")) + "</div>" + src + meta + "</div>";
  }).join("");
  scrollChat();
}
$("cMsgs").addEventListener("click", function (e) {
  var b = e.target.closest("button[data-act]"); if (!b) return;
  var c = curConv(), m = c.messages[+b.dataset.i]; if (!m) return;
  var a = b.dataset.act;
  if (a === "copy") copyText(m.content);
  else if (a === "speak") { Speaker.stop(); Speaker.say(m.content, m.voice || S.voice); }
  else if (a === "share") { if (N && N.shareText) N.shareText(m.content); else copyText(m.content); }
  else if (a === "regen") { c.messages.pop(); saveConvs(); renderChat(); ask("", "chat", { regenerate: true }); }
});
$("cForm").addEventListener("submit", function (e) { e.preventDefault(); var t = $("cInput").value.trim(); if (!t) return; $("cInput").value = ""; autoGrow(); ask(t, "chat"); });
$("cInput").addEventListener("keydown", function (e) { if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); $("cForm").requestSubmit(); } });
function autoGrow() { var t = $("cInput"); t.style.height = "auto"; t.style.height = Math.min(140, t.scrollHeight) + "px"; }
$("cInput").addEventListener("input", autoGrow);
$("cNew").addEventListener("click", function () { newConv(); toast("Nayi chat shuru"); });
$("cListBtn").addEventListener("click", function () { renderConvList(); $("cDrawer").hidden = false; });
$("cDrawerClose").addEventListener("click", function () { $("cDrawer").hidden = true; });
$("cSearch").addEventListener("input", renderConvList);
function renderConvList() {
  var q = $("cSearch").value.trim().toLowerCase();
  var list = CONVS.slice().sort(function (a, b) { return b.updated - a.updated; }).filter(function (c) { return !q || c.title.toLowerCase().indexOf(q) >= 0 || c.messages.some(function (m) { return m.content.toLowerCase().indexOf(q) >= 0; }); });
  $("cList").innerHTML = list.length ? list.map(function (c) {
    return "<div class='conv" + (c.id === CUR ? " on" : "") + "' data-id='" + c.id + "'><div class='t'><b>" + esc(c.title) + "</b><small>" + c.messages.length + " msgs · " + fmtTime(c.updated) + "</small></div><button class='mini' data-del='" + c.id + "'>🗑</button></div>";
  }).join("") : "<p class='hint'>Koi chat nahi.</p>";
}
$("cList").addEventListener("click", function (e) {
  var d = e.target.closest("[data-del]");
  if (d) { if (!confirm("Ye chat mita dein?")) return; CONVS = CONVS.filter(function (c) { return c.id !== d.dataset.del; }); if (CUR === d.dataset.del) CUR = CONVS[0] ? CONVS[0].id : null; saveConvs(); renderConvList(); renderChat(); return; }
  var c = e.target.closest(".conv"); if (c) { CUR = c.dataset.id; saveConvs(); $("cDrawer").hidden = true; renderChat(); }
});
var chatMicOn = false;
$("cMic").addEventListener("click", function () {
  if (chatMicOn) { stopListening(false); return; }
  chatMicOn = true; $("cMic").classList.add("on"); var base = $("cInput").value ? $("cInput").value + " " : "";
  listen(S.srLang, { onPartial: function (t) { $("cInput").value = base + t; autoGrow(); }, onFinal: function (t) { $("cInput").value = base + t; autoGrow(); },
    onError: function (e, m) { if (e !== "no-speech" && e !== "aborted") toast("Mic: " + (m || e)); }, onEnd: function () { chatMicOn = false; $("cMic").classList.remove("on"); } });
});

/* ------------------------------------------------------------------ voice + language sheets */
var vsFilter = "all", vsPick = null;
function openVoiceSheet(onPick) { vsPick = onPick || function (id) { S.voice = id; saveS(); syncSettingsUI(); toast(persona(id).name + " ki awaaz chuni"); }; renderVoiceSheet(); $("voiceSheet").hidden = false; }
function renderVoiceSheet() {
  $("vsList").innerHTML = VOICES.filter(function (v) { return vsFilter === "all" || v.g === vsFilter; }).map(function (v) {
    return "<div class='vitem" + (v.id === S.voice ? " on" : "") + "' data-id='" + v.id + "'><span class='avatar' style='background:" + v.color + "'>" + v.name[0] + "</span><div class='t'><b>" + v.name + (v.love ? " <span class='heart'>♥</span>" : "") +
      "</b> <small>" + (v.g === "F" ? "Female" : "Male") + " · " + esc(v.desc) + "</small></div><button class='mini' data-try='" + v.id + "'>▶ Suno</button></div>";
  }).join("");
}
$("vsFilter").addEventListener("click", function (e) { var b = e.target.closest("button"); if (!b) return; vsFilter = b.dataset.v; [].forEach.call(this.children, function (x) { x.classList.toggle("on", x === b); }); renderVoiceSheet(); });
$("vsList").addEventListener("click", function (e) {
  var t = e.target.closest("[data-try]");
  if (t) { var v = persona(t.dataset.try); Speaker.stop(); Speaker.say(sampleLine(v), v.id); return; }
  var it = e.target.closest(".vitem"); if (!it) return;
  vsPick(it.dataset.id); $("voiceSheet").hidden = true;
});
function sampleLine(v) {
  var L = S.replyLang, n = S.callMe || "dost";
  if (L === "hi") return "नमस्ते " + n + "! मैं " + v.hi + " हूँ। बताइए, आज क्या करें?";
  if (L === "en") return "Hi " + n + "! I'm " + v.name + ". What shall we do today?";
  if (L === "or") return "ନମସ୍କାର " + n + "! ମୁଁ " + v.name + "। ଆଜି କଣ କରିବା?";
  if (v.scripts.indexOf("deva") < 0 && v.scripts.indexOf("beng") >= 0 && L === "bn") return "নমস্কার " + n + "! আমি " + v.name + "। আজ কী করবো?";
  return "Hello " + n + "! Main " + v.name + " hoon. Batao, aaj kya karein?";
}
$("vsClose").addEventListener("click", function () { $("voiceSheet").hidden = true; });
$("voiceChip").addEventListener("click", function () { openVoiceSheet(); });
$("sVoiceBtn").addEventListener("click", function () { openVoiceSheet(); });
$("langChip").addEventListener("click", function () {
  $("lsList").innerHTML = LANGS.map(function (l) { return "<div class='vitem" + (l.id === S.replyLang ? " on" : "") + "' data-id='" + l.id + "'><div class='t'><b>" + esc(l.label) + "</b></div></div>"; }).join("");
  $("langSheet").hidden = false;
});
$("lsList").addEventListener("click", function (e) { var it = e.target.closest(".vitem"); if (!it) return; S.replyLang = it.dataset.id; var L = LBYID[S.replyLang]; if (L.id !== "auto" && L.id !== "hinglish" && L.id !== "en") S.srLang = L.sr; saveS(); syncSettingsUI(); $("langSheet").hidden = true; });
$("lsClose").addEventListener("click", function () { $("langSheet").hidden = true; });

/* ------------------------------------------------------------------ Broadcast */
var BC = { mode: "ai", sel: ["all"], lang: "hi", speed: 1.3, out: "audio", format: "short", tone: "fun", job: null, cur: null };
var LANG_SCRIPT = { hinglish: "latn", en: "latn", hi: "deva", mr: "deva", or: "orya", bn: "beng", gu: "gujr", ta: "taml", te: "telu", kn: "knda", ml: "mlym", pa: "guru", ur: "arab" };
function shuffle(a) { a = a.slice(); for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; } return a; }
function castFor(lang, sel) {
  if (sel.indexOf("all") < 0 && sel.length) return sel.slice(0, 2).map(persona);
  var sc = LANG_SCRIPT[lang] || "latn";
  var ok = VOICES.filter(function (v) { return v.scripts.indexOf(sc) >= 0; });
  if (ok.length < 2) ok = VOICES.slice();
  var F = shuffle(ok.filter(function (v) { return v.g === "F" && v.id !== "sonali"; })), M = shuffle(ok.filter(function (v) { return v.g === "M"; }));
  if (ok.indexOf(VBYID.sonali) >= 0) F.unshift(VBYID.sonali);
  var out = [];
  for (var i = 0; out.length < 4 && (i < F.length || i < M.length); i++) { if (F[i]) out.push(F[i]); if (M[i] && out.length < 4) out.push(M[i]); }
  return out;
}
window.Puna.castFor = function (lang, sel) { return castFor(lang, sel).map(function (v) { return v.id; }); };
function renderBVoices() {
  var html = "<button class='vchip" + (BC.sel.indexOf("all") >= 0 ? " on" : "") + "' data-id='all'>🌈 All</button>";
  html += VOICES.map(function (v) { return "<button class='vchip" + (BC.sel.indexOf(v.id) >= 0 ? " on" : "") + "' data-id='" + v.id + "'>" + v.name + "<small>" + v.g + "</small></button>"; }).join("");
  $("bVoices").innerHTML = html;
}
$("bVoices").addEventListener("click", function (e) {
  var b = e.target.closest(".vchip"); if (!b) return; var id = b.dataset.id;
  if (id === "all") BC.sel = ["all"];
  else {
    BC.sel = BC.sel.filter(function (x) { return x !== "all"; });
    var i = BC.sel.indexOf(id);
    if (i >= 0) BC.sel.splice(i, 1); else { BC.sel.push(id); if (BC.sel.length > 2) BC.sel.shift(); }
    if (!BC.sel.length) BC.sel = ["all"];
  }
  renderBVoices();
});
function segBind(id, key, after) {
  $(id).addEventListener("click", function (e) { var b = e.target.closest("button"); if (!b) return; BC[key] = b.dataset.v; [].forEach.call(this.children, function (x) { x.classList.toggle("on", x === b); }); if (after) after(); });
}
segBind("bMode", "mode", function () { var s = BC.mode === "script"; $("bScriptHint").hidden = !s; $("bTopicLbl").textContent = s ? "Apna script (Naam: line)" : "Topic / kya batana hai?"; $("bTopic").rows = s ? 8 : 3; $("bTopic").placeholder = s ? "Sonali: Namaste dosto! Aaj hum baat karenge…\nRohan: Haan, aur ye bahut mazedaar hai!" : "Jaise: Aloo paratha ki recipe — girlfriend ko bhejni hai 😄"; });
segBind("bOut", "out", function () { $("bFormat").hidden = BC.out !== "video"; });
segBind("bFormat", "format");
$("bLang").addEventListener("change", function () { BC.lang = this.value; });
$("bTone").addEventListener("change", function () { BC.tone = this.value; });
$("bSpeed").addEventListener("input", function () { BC.speed = parseFloat(this.value); $("bSpeedVal").textContent = BC.speed.toFixed(1) + "×"; });
$("bMic").addEventListener("click", function () {
  var btn = this; if (btn.classList.contains("on")) { stopListening(false); return; }
  btn.classList.add("on"); var base = $("bTopic").value ? $("bTopic").value + " " : "";
  listen(S.srLang, { onPartial: function (t) { $("bTopic").value = base + t; }, onFinal: function (t) { $("bTopic").value = base + t; }, onError: function (e, m) { if (e !== "no-speech" && e !== "aborted") toast("Mic: " + (m || e)); }, onEnd: function () { btn.classList.remove("on"); } });
});

var STEP_NAMES = { research: "🌐 Research", script: "✍️ Script", voices: "🗣️ Awaazein", mix: "🎚️ Mixing", audio: "🎧 Audio file", video: "🎬 Video" };
function stepsInit(list) { $("bSteps").innerHTML = list.map(function (k) { return "<div class='step' data-k='" + k + "'>○ " + STEP_NAMES[k] + "</div>"; }).join(""); $("bProgCard").hidden = false; setBar(0, ""); }
function step(k, st, extra) { var el = document.querySelector(".step[data-k='" + k + "']"); if (!el) return; el.className = "step " + st; el.textContent = (st === "done" ? "✓ " : st === "fail" ? "✗ " : st === "doing" ? "◉ " : "○ ") + STEP_NAMES[k] + (extra ? " — " + extra : ""); }
function setBar(p, t) { $("bBar").style.width = Math.round(p) + "%"; $("bProgText").textContent = t ? t + " · " + Math.round(p) + "%" : ""; }
function langScriptRule(lang) { var L = LBYID[lang] || LBYID.hi; return L.rule.replace(/^Always reply/, "Write everything"); }

function parseCustomScript(text, cast) {
  var lines = [], map = {}, next = 0, last = null;
  text.split(/\n+/).forEach(function (raw) {
    var l = raw.trim(); if (!l) return;
    var m = l.match(/^([^:：]{1,24})[:：]\s*(.+)$/), sp = null, body = l;
    if (m) {
      var nm = m[1].trim(), found = voiceNamesIn(nm)[0];
      if (found) sp = found;
      else { if (!map[nm.toLowerCase()]) { map[nm.toLowerCase()] = cast[next % cast.length]; next++; } sp = map[nm.toLowerCase()]; }
      body = m[2].trim();
    }
    if (!sp) sp = last || cast[0];
    last = sp; lines.push({ p: sp, text: body });
  });
  return lines;
}
async function writeScriptAI(topic, cast, lang, tone, notes) {
  var names = cast.map(function (v) { return v.name + " (" + (v.g === "F" ? "female" : "male") + ")"; }).join(", ");
  var toneTxt = { fun: "fun, friendly, desi and warm (light humour)", pro: "professional and clear", news: "crisp news-bulletin style", story: "story-telling style with emotion" }[tone] || "friendly";
  var solo = cast.length === 1;
  var prompt = (solo ? "Write a natural spoken monologue (radio/podcast host) " : "Write a natural, engaging spoken conversation (podcast/radio show) between " + names + " ") +
    "about: \"" + topic + "\". Tone: " + toneTxt + ".\nLANGUAGE: " + langScriptRule(lang) + "\n" +
    (notes ? "Research notes (use these facts, do not invent new facts):\n" + notes.slice(0, 3500) + "\n" : "") +
    "Rules: " + (solo ? "8-12 short lines" : "12-16 short turns; speakers take turns naturally, ask each other questions, react") + "; each line 1-3 sentences (max ~240 characters); start with a catchy hello and intro; end with a warm sign-off; only words to be spoken — no stage directions, sound effects, emojis, markdown or speaker labels inside text; write numbers the way they are spoken.\n" +
    "Return ONLY JSON: {\"title\":\"short catchy title in the same language\",\"lines\":[{\"speaker\":\"" + cast[0].name + "\",\"text\":\"...\"}]} using only these speaker names: " + cast.map(function (v) { return v.name; }).join(", ") + ".";
  var r = await Brain.ask([{ role: "system", content: "You are an expert scriptwriter for short multi-voice audio broadcasts. Output strict JSON only." }, { role: "user", content: prompt }], { json: true, temperature: 0.8, max: 3500 });
  var t = r.text, j = JSON.parse(t.slice(t.indexOf("{"), t.lastIndexOf("}") + 1));
  var lines = (j.lines || []).filter(function (l) { return l && l.text && String(l.text).trim(); }).map(function (l, i) {
    var p = voiceNamesIn(String(l.speaker || ""))[0];
    if (!p || cast.indexOf(p) < 0) p = cast[i % cast.length];
    return { p: p, text: String(l.text).trim() };
  });
  if (!lines.length) throw new Error("Script khaali aaya, phir try karo");
  return { title: String(j.title || topic).slice(0, 90), lines: lines };
}
var decodeCtx = null;
async function toPcm24k(arrayBuf) {
  var C = window.OfflineAudioContext || window.webkitOfflineAudioContext;
  var ctx = new C(1, 24000, 24000);
  var ab = await new Promise(function (res, rej) { var p = ctx.decodeAudioData(arrayBuf, res, rej); if (p && p.then) p.then(res, rej); });
  if (ab.sampleRate === 24000 && ab.numberOfChannels === 1) return ab.getChannelData(0).slice();
  var off = new C(1, Math.ceil(ab.duration * 24000), 24000), src = off.createBufferSource();
  src.buffer = ab; src.connect(off.destination); src.start(); var out = await off.startRendering(); return out.getChannelData(0).slice();
}
async function synthLine(line, speed) {
  var v = resolveVoice(line.p, line.text, true), buf;
  if (v.engine === "mms") buf = await (await mmsSpeak(line.text, v.lang)).arrayBuffer();
  else {
    var r = await edgeSynth(v.text || line.text, v.voice, speed, v.pitch);
    buf = await (await fetch(r.url)).arrayBuffer();
    if (N && N.deleteMedia) N.deleteMedia(r.name);
  }
  return toPcm24k(buf);
}
window.Puna.synthLine = function (pid, text, speed) { return synthLine({ p: persona(pid), text: text }, speed || 1.3); };
async function synthAll(lines, speed, onEach) {
  var out = new Array(lines.length), idx = 0, done = 0;
  async function worker() {
    while (idx < lines.length) {
      var i = idx++, tries = 0;
      for (;;) {
        if (BC.job && BC.job.cancel) throw new Error("Cancel kiya");
        try { out[i] = await synthLine(lines[i], speed); break; }
        catch (e) { if (++tries >= 3) throw new Error("Line " + (i + 1) + " (" + lines[i].p.name + ") ki awaaz nahi bani: " + e.message); await sleep(800 * tries); }
      }
      done++; onEach(done);
    }
  }
  await Promise.all([worker(), worker(), worker()]);
  return out;
}
function mix(pcms, lines) {
  var R = 24000, lead = new Float32Array(R * 0.25), gap = new Float32Array(Math.round(R * 0.32)), tail = new Float32Array(R * 0.7);
  var chunks = [lead], t = lead.length, timed = [], peak = 0.0001;
  pcms.forEach(function (p, i) {
    var s = t / R; chunks.push(p); t += p.length;
    timed.push({ name: lines[i].p.name, color: lines[i].p.color, text: lines[i].text, start: s, end: t / R });
    if (i < pcms.length - 1) { chunks.push(gap); t += gap.length; }
    for (var k = 0; k < p.length; k++) { var a = Math.abs(p[k]); if (a > peak) peak = a; }
  });
  chunks.push(tail); t += tail.length;
  var g = Math.min(3, 0.95 / peak);
  if (Math.abs(g - 1) > 0.02) pcms.forEach(function (p) { for (var k = 0; k < p.length; k++) p[k] *= g; });
  if (timed.length) timed[timed.length - 1].end = t / R;
  return { wav: wavBlob(chunks, R), timed: timed, duration: t / R };
}
async function makeVideoFor(item, onPct) {
  if (!N || !N.makeVideo) throw new Error("Video sirf Android app mein banti hai");
  var portrait = (item.format || BC.format) !== "landscape";
  var spec = { w: portrait ? 720 : 1280, h: portrait ? 1280 : 720, fps: 20, title: item.title, brand: "Made with Puna AI", lines: item.timed };
  var out = item.id + (portrait ? "-short" : "-wide") + ".mp4";
  var r = await nativeTask(function (id) { N.makeVideo(id, JSON.stringify(spec), item.wav, out); }, onPct, 30 * 60000);
  item.video = r.name; item.format = portrait ? "short" : "landscape"; return r;
}

$("bGo").addEventListener("click", runBroadcast);
$("bCancel").addEventListener("click", function () { if (BC.job) { BC.job.cancel = true; toast("Cancel ho raha hai…"); } });
async function runBroadcast() {
  $("bErr").textContent = "";
  var topic = $("bTopic").value.trim();
  if (!topic) { $("bErr").textContent = BC.mode === "script" ? "Pehle script likho (Naam: line)." : "Pehle topic likho ya 🎤 se bolo."; return; }
  if (BC.mode === "ai" && !brainKind()) { $("bErr").textContent = "AI script ke liye Settings mein Groq key daalo — ya '✍️ Apna script' chuno (bina key ke chalta hai)."; return; }
  if (BC.job) { toast("Ek broadcast already ban raha hai…"); return; }
  var job = BC.job = { cancel: false }, wantVideo = BC.out === "video";
  var cast = castFor(BC.lang, BC.sel), lang = BC.lang, speed = BC.speed;
  $("bGo").disabled = true; $("bResult").hidden = true; if (N && N.keepAwake) N.keepAwake(true);
  var steps = (BC.mode === "ai" ? (S.web !== "never" ? ["research", "script"] : ["script"]) : []).concat(["voices", "mix", "audio"]).concat(wantVideo ? ["video"] : []);
  stepsInit(steps);
  var item = { id: "bc-" + uid(), at: Date.now(), lang: lang, title: "", cast: cast.map(function (v) { return v.id; }), format: BC.format };
  try {
    var script;
    if (BC.mode === "ai") {
      var notes = "";
      if (steps.indexOf("research") >= 0) {
        step("research", "doing"); setBar(3, "Web par research");
        try {
          var rr = await Brain.ask([{ role: "system", content: "You are a careful researcher. Reply in English bullet points only." }, { role: "user", content: "Collect up to 12 key accurate, current facts (with numbers/dates where relevant) for a short audio broadcast about: " + topic }], { search: true, temperature: 0.3, max: 1500 });
          notes = rr.text; item.sources = rr.sources || []; step("research", "done", (item.sources.length || 0) + " sources");
        } catch (e) { step("research", "fail", "bina web ke aage"); }
      }
      if (job.cancel) throw new Error("Cancel kiya");
      step("script", "doing"); setBar(10, "Script likh rahi hoon");
      script = await writeScriptAI(topic, cast, lang, BC.tone, notes);
      step("script", "done", script.lines.length + " lines");
    } else {
      script = { title: topic.split("\n")[0].replace(/^[^:：]{1,24}[:：]\s*/, "").slice(0, 60) || "Mera Broadcast", lines: parseCustomScript(topic, cast) };
    }
    item.title = script.title;
    item.script = script.lines.map(function (l) { return { v: l.p.id, text: l.text }; });
    if (job.cancel) throw new Error("Cancel kiya");
    step("voices", "doing", "0/" + script.lines.length); setBar(15, "Awaazein ban rahi hain");
    var pcms = await synthAll(script.lines, speed, function (d) { step("voices", "doing", d + "/" + script.lines.length); setBar(15 + 50 * d / script.lines.length, "Awaazein ban rahi hain"); });
    step("voices", "done", script.lines.length + " lines");
    step("mix", "doing"); setBar(66, "Mixing");
    var m = mix(pcms, script.lines); item.timed = m.timed; item.duration = m.duration;
    step("mix", "done", Math.round(m.duration) + " sec");
    step("audio", "doing"); setBar(70, "Audio file");
    if (N && N.writeBegin) {
      item.wav = item.id + ".wav";
      await writeBlob(m.wav, item.wav);
      var a = await nativeTask(function (id) { N.makeM4a(id, item.wav, item.id + ".m4a"); }, function (p) { setBar(70 + 0.08 * p, "Audio encode"); }, 10 * 60000);
      item.audio = a.name;
    } else { item.blobUrl = URL.createObjectURL(m.wav); }
    step("audio", "done");
    if (wantVideo) {
      step("video", "doing", "0%"); setBar(80, "Video ban rahi hai");
      await makeVideoFor(item, function (p) { step("video", "doing", p + "%"); setBar(80 + 0.2 * p, "Video ban rahi hai"); });
      step("video", "done");
    }
    setBar(100, "Taiyaar!");
    BHIST.unshift(item); if (BHIST.length > 20) { BHIST.slice(20).forEach(deleteItemFiles); BHIST = BHIST.slice(0, 20); } save("puna.broadcasts", BHIST);
    showBroadcast(item, wantVideo);
    renderBHist();
    Speaker.say(lang === "hi" ? "आपका ब्रॉडकास्ट " + (wantVideo ? "वीडियो" : "ऑडियो") + " तैयार है!" : lang === "en" ? "Your broadcast " + (wantVideo ? "video" : "audio") + " is ready!" : lang === "or" ? "ଆପଣଙ୍କ ବ୍ରଡକାଷ୍ଟ ପ୍ରସ୍ତୁତ!" : "Aapka broadcast " + (wantVideo ? "video" : "audio") + " taiyaar hai!", cast[0].id);
    window.Puna.lastBroadcast = item;
  } catch (e) {
    var k = document.querySelector(".step.doing"); if (k) step(k.dataset.k, "fail");
    $("bErr").textContent = e.nokey ? friendlyErr(e) : (e.message === "Cancel kiya" ? "Cancel ho gaya." : "Broadcast nahi bana: " + friendlyErr(e).replace(/^Gadbad ho gayi: /, ""));
    window.Puna.lastBroadcastError = e.message;
  } finally {
    BC.job = null; $("bGo").disabled = false; if (N && N.keepAwake) N.keepAwake(false);
  }
}
window.Puna.runBroadcast = runBroadcast;
function mediaUrl(name) { return "/media/" + encodeURIComponent(name); }
function showBroadcast(item, preferVideo) {
  BC.cur = item; $("bResult").hidden = false; $("bTitle").textContent = item.title;
  var au = $("bAudio"), vi = $("bVideo");
  au.hidden = !(item.audio || item.blobUrl); if (!au.hidden) au.src = item.audio ? mediaUrl(item.audio) : item.blobUrl;
  vi.hidden = !item.video; if (item.video) vi.src = mediaUrl(item.video);
  $("bMakeVideo").hidden = !!item.video || !item.wav;
  $("bScript").innerHTML = (item.script || []).map(function (l) { return "<p><b>" + esc(persona(l.v).name) + ":</b> " + esc(l.text) + "</p>"; }).join("") +
    ((item.sources && item.sources.length) ? "<div class='sources'>" + item.sources.map(function (s) { return "<a class='src' href='" + esc(s.url) + "'>🔗 " + esc(s.title) + "</a>"; }).join("") + "</div>" : "");
  setTimeout(function () { $("bResult").scrollIntoView({ behavior: "smooth", block: "start" }); }, 100);
}
function curFile() { var it = BC.cur; if (!it) return null; var v = !$("bVideo").hidden && it.video; return v ? { name: it.video, ext: "mp4" } : (it.audio ? { name: it.audio, ext: "m4a" } : null); }
function niceName(it, ext) { return "Puna-" + (it.title || "Broadcast").replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "").slice(0, 40) + "-" + new Date(it.at).toISOString().slice(0, 10) + "." + ext; }
$("bSave").addEventListener("click", async function () {
  var f = curFile(); if (!f) { if (BC.cur && BC.cur.blobUrl) { var a = document.createElement("a"); a.href = BC.cur.blobUrl; a.download = niceName(BC.cur, "wav"); a.click(); } return; }
  try { var r = await nativeTask(function (id) { N.saveToDownloads(id, f.name, niceName(BC.cur, f.ext)); }, null, 60000); toast("Save ho gaya: Download/PunaAI/" + r.name); }
  catch (e) { toast("Save nahi hua: " + e.message); }
});
function shareTo(target) {
  var f = curFile(); if (!f || !N) { toast("Share ke liye Android app chahiye"); return; }
  var r = N.share(f.name, BC.cur.title + " — made with Puna AI", target);
  if (r === "app-missing") { toast((target === "whatsapp" ? "WhatsApp" : "Telegram") + " install nahi hai — share list khol rahi hoon"); N.share(f.name, BC.cur.title, ""); }
}
$("bWa").addEventListener("click", function () { shareTo("whatsapp"); });
$("bTg").addEventListener("click", function () { shareTo("telegram"); });
$("bShare").addEventListener("click", function () { shareTo(""); });
$("bMakeVideo").addEventListener("click", async function () {
  var it = BC.cur; if (!it || !it.wav || BC.job) return;
  BC.job = { cancel: false }; this.disabled = true; stepsInit(["video"]); step("video", "doing", "0%");
  try { it.format = BC.format; await makeVideoFor(it, function (p) { step("video", "doing", p + "%"); setBar(p, "Video ban rahi hai"); }); step("video", "done"); save("puna.broadcasts", BHIST); showBroadcast(it, true); renderBHist(); }
  catch (e) { step("video", "fail"); $("bErr").textContent = "Video nahi bani: " + e.message; }
  finally { BC.job = null; this.disabled = false; }
});
function deleteItemFiles(it) { if (!N || !N.deleteMedia) return; [it.wav, it.audio, it.video].forEach(function (n) { if (n) N.deleteMedia(n); }); }
function renderBHist() {
  $("bHist").innerHTML = BHIST.length ? BHIST.map(function (it, i) {
    return "<div class='h'><div class='t'>" + esc(it.title) + "<small>" + fmtTime(it.at) + " · " + Math.round(it.duration || 0) + "s · " + (it.video ? "🎬+🎧" : "🎧") + " · " + (it.cast || []).map(function (c) { return persona(c).name; }).join(", ") + "</small></div><button class='mini' data-open='" + i + "'>▶</button><button class='mini' data-del='" + i + "'>🗑</button></div>";
  }).join("") : "<p class='hint'>Abhi koi nahi.</p>";
}
$("bHist").addEventListener("click", function (e) {
  var o = e.target.closest("[data-open]"), d = e.target.closest("[data-del]");
  if (o) showBroadcast(BHIST[+o.dataset.open], true);
  if (d) { var it = BHIST.splice(+d.dataset.del, 1)[0]; deleteItemFiles(it); save("puna.broadcasts", BHIST); renderBHist(); }
});

/* ------------------------------------------------------------------ Settings */
function fillSelect(el, items, val) { el.innerHTML = items.map(function (x) { return "<option value='" + esc(x[0]) + "'>" + esc(x[1]) + "</option>"; }).join(""); if (val != null) el.value = val; }
function fillModelSelect() {
  var cur = S.model, opts = [["", "Auto (level ke hisaab se)"]];
  (MODELS.groq || []).filter(usableGroq).forEach(function (m) { opts.push([m, "Groq · " + m]); });
  (MODELS.gemini || []).filter(function (m) { return /^gemini/.test(m); }).forEach(function (m) { opts.push([m, "Gemini · " + m]); });
  if (cur && !opts.some(function (o) { return o[0] === cur; })) opts.push([cur, cur]);
  fillSelect($("sModel"), opts, cur);
}
function refreshBadges() {
  var p = persona(S.voice), k = brainKind();
  $("voiceChipName").textContent = p.name; $("voiceChip").querySelector(".dot").style.background = p.color;
  $("langChipName").textContent = (LBYID[S.replyLang] || LBYID.auto).label.split(" (")[0];
  $("brainBadge").textContent = k ? (k === "groq" ? "Groq" : "Gemini") + " · " + S.level[0].toUpperCase() + S.level.slice(1) : "Key nahi — Settings";
  $("sVoiceBtn").textContent = "🎙️ " + p.name + " — " + p.desc;
}
function syncSettingsUI() {
  $("sGroqKey").value = S.groqKey; $("sGemKey").value = S.geminiKey; $("sBrain").value = S.brain; $("sLevel").value = S.level; $("sWeb").value = S.web;
  $("sCallMe").value = S.callMe; $("sGender").value = S.gender; $("sReplyLang").value = S.replyLang; $("sSrLang").value = S.srLang;
  $("sSpeed").value = S.speed; $("sSpeedVal").textContent = (+S.speed).toFixed(1) + "×"; $("aSpeed").value = S.speed; $("aSpeedVal").textContent = (+S.speed).toFixed(1) + "×";
  $("sFast").checked = S.fast; $("sBarge").checked = S.bargeIn; $("sShowLat").checked = S.showLat; $("aLat").hidden = !(S.showLat || DEBUG) || !window.Puna.lastLatency;
  $("sOdia").value = S.odia; $("sAutoSpeak").checked = S.autoSpeak; $("sContinuous").checked = S.continuous; $("aContinuous").checked = S.continuous; $("sMemOn").checked = S.memoryOn;
  fillModelSelect(); refreshBadges();
}
function bindS(id, key, ev, conv) { $(id).addEventListener(ev || "change", function () { S[key] = conv ? conv(this) : this.value; saveS(); syncSettingsUI(); }); }
bindS("sGroqKey", "groqKey", "change", function (e) { return e.value.trim(); });
bindS("sGemKey", "geminiKey", "change", function (e) { return e.value.trim(); });
bindS("sBrain", "brain"); bindS("sLevel", "level"); bindS("sModel", "model"); bindS("sWeb", "web");
bindS("sCallMe", "callMe", "change", function (e) { return e.value.trim(); }); bindS("sGender", "gender");
bindS("sReplyLang", "replyLang"); bindS("sSrLang", "srLang");
$("sSrLang").addEventListener("change", function () { var st = bgStatus(); if (st && (st.alwaysOn || st.wake)) N.setBackground(st.alwaysOn, st.wake, S.srLang); }); bindS("sOdia", "odia");
bindS("sSpeed", "speed", "input", function (e) { return parseFloat(e.value); });
bindS("sAutoSpeak", "autoSpeak", "change", function (e) { return e.checked; });
bindS("sContinuous", "continuous", "change", function (e) { return e.checked; });
bindS("sMemOn", "memoryOn", "change", function (e) { return e.checked; });
bindS("sFast", "fast", "change", function (e) { return e.checked; });
bindS("sBarge", "bargeIn", "change", function (e) { return e.checked; });
bindS("sShowLat", "showLat", "change", function (e) { return e.checked; });
async function testKey(kind) {
  S.groqKey = $("sGroqKey").value.trim(); S.geminiKey = $("sGemKey").value.trim(); saveS();
  var msg = $("sKeyMsg");
  if (kind === "groq" && !S.groqKey || kind === "gemini" && !S.geminiKey) { msg.textContent = "Pehle key paste karo."; return; }
  msg.textContent = "Check kar rahi hoon…";
  try { var list = await fetchModels(kind); msg.textContent = "✅ " + (kind === "groq" ? "Groq" : "Gemini") + " key sahi hai — " + list.length + " models mile. Chat model: " + (kind === "groq" ? groqModel("chat") : gemModel()); refreshBadges(); }
  catch (e) { msg.textContent = "❌ " + friendlyErr(e); }
}
$("sGroqTest").addEventListener("click", function () { testKey("groq"); });
$("sGemTest").addEventListener("click", function () { testKey("gemini"); });
function renderMem() {
  $("sMemCount").textContent = MEM.length ? "(" + MEM.length + ")" : "";
  $("sMemList").innerHTML = MEM.length ? MEM.slice().reverse().map(function (m) {
    return "<div class='mem'><div class='t'>" + esc(m.text) + "<small>" + fmtTime(m.at) + " · " + esc(m.src || "") + "</small></div><button class='mini' data-mdel='" + m.id + "'>🗑</button></div>";
  }).join("") : "<p class='hint'>Abhi memory khaali hai. Bolo: “Mera naam Sonali hai, yaad rakhna”.</p>";
}
$("sMemList").addEventListener("click", function (e) { var b = e.target.closest("[data-mdel]"); if (!b) return; MEM = MEM.filter(function (m) { return m.id !== b.dataset.mdel; }); saveMem(); });
$("sMemAddBtn").addEventListener("click", function () { if (addMemory($("sMemAdd").value, "manual")) { $("sMemAdd").value = ""; toast("Memory mein jod diya"); } });
$("sMemClear").addEventListener("click", function () { if (confirm("Saari memory mita dein?")) { MEM = []; saveMem(); toast("Memory saaf"); } });
$("sChatsClear").addEventListener("click", function () { if (confirm("Saari chats mita dein?")) { CONVS = []; CUR = null; saveConvs(); renderChat(); toast("Chats saaf"); } });
$("sMediaClear").addEventListener("click", function () {
  if (!confirm("Broadcast ki saari files (app cache) mita dein? Download folder wali files rahengi.")) return;
  BHIST.forEach(deleteItemFiles); BHIST = []; save("puna.broadcasts", BHIST); renderBHist(); toast("Cache saaf");
});

/* ------------------------------------------------------------------ 24/7 background */
function bgStatus() { try { return N && N.bgStatus ? JSON.parse(N.bgStatus()) : null; } catch (e) { return null; } }
function renderBg() {
  var st = bgStatus(), el = $("sBgStat");
  if (!st) { $("sBgCard").querySelectorAll("input,button").forEach(function (x) { x.disabled = true; }); el.innerHTML = "<span class='no'>Sirf Android app mein</span>"; return; }
  $("sAlways").checked = st.alwaysOn; $("sWake").checked = st.wake;
  function chip(ok, a, b) { return "<span class='" + (ok ? "ok" : "no") + "'>" + (ok ? "✓ " + a : "• " + b) + "</span>"; }
  el.innerHTML = chip(st.running, "Service chal rahi hai", "Service band") + chip(st.notif, "Notification on", "Notification permission chahiye") +
    chip(st.battery, "Battery limit hata hua", "Battery optimization ON (Android band kar sakta hai)") +
    (st.wake ? chip(st.overlay, "Apps ke upar khul sakti hai", "‘Apps ke upar dikhao’ do, warna sirf notification aayegi") : "") +
    (st.wakePending ? "<span class='no'>• Restart ke baad Hey Puna: app ek baar kholo</span>" : "");
  $("sBattBtn").hidden = st.battery; $("sOverlayBtn").hidden = !st.wake || st.overlay;
}
function applyBg() { if (N && N.setBackground) { N.setBackground($("sAlways").checked, $("sWake").checked, S.srLang); setTimeout(renderBg, 800); setTimeout(renderBg, 2500); } }
$("sAlways").addEventListener("change", function () { applyBg(); if (this.checked) { var st = bgStatus(); if (st && !st.battery) toast("24/7 ke liye ‘Battery limit hatao’ bhi dabao 🔋", 4500); } });
$("sWake").addEventListener("change", function () {
  if (this.checked && !confirm("“Hey Puna” mic ko lagatar sunta hai — battery zyada lagegi. On karein?")) { this.checked = false; return; }
  applyBg(); if (this.checked) { var st = bgStatus(); if (st && !st.overlay) toast("Lock/dusre app se khulne ke liye ‘Apps ke upar dikhao’ allow karo", 5000); }
});
$("sBattBtn").addEventListener("click", function () { if (N && N.requestBattery) N.requestBattery(); });
$("sOverlayBtn").addEventListener("click", function () { if (N && N.requestOverlay) N.requestOverlay(); });
document.addEventListener("visibilitychange", function () { if (!document.hidden && currentTab === "settings") renderBg(); });
window.__punaIntent = function (a) {
  if (!window.Puna.ready) return false;
  if (a === "talk") { showTab("assistant"); stopAll(); setTimeout(startVoice, 350); }
  else if (a === "broadcast") showTab("broadcast");
  return true;
};


/* ------------------------------------------------------------------ Phase 3: phone Agent (tool-calling loop) */
var AG_TOOLS = [
  ["open_app", "Open an installed app by its name (e.g. WhatsApp, Settings, YouTube, Camera).", { name: { type: "string" } }, ["name"]],
  ["read_screen", "Read the current screen as a list of UI nodes: i=index, t=text, d=description, id=view id, c=class, k=clickable, e=editable, s=scrollable, on=checked, b=centre x,y.", {}, []],
  ["tap", "Tap a UI element. Prefer index from the last screen; else exact visible text; else view id; x/y only as last resort.", { index: { type: "integer" }, text: { type: "string" }, id: { type: "string" }, x: { type: "integer" }, y: { type: "integer" } }, []],
  ["type_text", "Type text into a text box (the focused one, or the one given by index / into=its text). submit=true presses Enter/Search.", { text: { type: "string" }, index: { type: "integer" }, into: { type: "string" }, append: { type: "boolean" }, submit: { type: "boolean" } }, ["text"]],
  ["scroll", "Scroll the screen.", { direction: { type: "string", enum: ["down", "up", "left", "right"] } }, ["direction"]],
  ["press", "Press a system button.", { key: { type: "string", enum: ["back", "home", "recents", "notifications", "quick_settings", "enter", "lock"] } }, ["key"]],
  ["screenshot", "Take a screenshot (saved to the gallery).", {}, []],
  ["wait", "Wait for the screen to load.", { ms: { type: "integer" } }, []],
  ["list_apps", "List installed app names (optionally filtered).", { filter: { type: "string" } }, []],
  ["find_contact", "Look up phone contacts by name.", { name: { type: "string" } }, ["name"]],
  ["call", "Phone call to a number or contact name.", { number: { type: "string" }, contact: { type: "string" } }, []],
  ["send_sms", "Open the SMS app with the message pre-filled for a number/contact (the user still needs Send to be tapped).", { number: { type: "string" }, contact: { type: "string" }, text: { type: "string" } }, ["text"]],
  ["whatsapp_message", "Open the WhatsApp chat of a contact/number with the text pre-filled. Afterwards tap the Send button to send it.", { contact: { type: "string" }, number: { type: "string" }, text: { type: "string" } }, ["text"]],
  ["set_alarm", "Set an alarm (24-hour clock).", { hour: { type: "integer" }, minute: { type: "integer" }, label: { type: "string" } }, ["hour", "minute"]],
  ["set_timer", "Start a countdown timer.", { seconds: { type: "integer" }, label: { type: "string" } }, ["seconds"]],
  ["flashlight", "Turn the flashlight/torch on or off.", { on: { type: "boolean" } }, ["on"]],
  ["volume", "Change volume: action up/down/mute/unmute/set (level 0-100).", { action: { type: "string", enum: ["up", "down", "mute", "unmute", "set"] }, level: { type: "integer" }, stream: { type: "string", enum: ["media", "ring", "alarm", "notification"] } }, ["action"]],
  ["open_settings", "Open a settings page.", { page: { type: "string", enum: ["main", "wifi", "bluetooth", "internet", "display", "sound", "location", "battery", "apps", "accessibility", "date", "airplane", "hotspot"] } }, ["page"]],
  ["youtube", "Search YouTube for a video/song.", { query: { type: "string" } }, ["query"]],
  ["navigate", "Start Google Maps navigation to a place.", { destination: { type: "string" }, mode: { type: "string", enum: ["driving", "walking", "bicycling", "two-wheeler"] } }, ["destination"]],
  ["open_url", "Open a web page in the browser.", { url: { type: "string" } }, ["url"]],
  ["web_search", "Open a Google search in the browser.", { query: { type: "string" } }, ["query"]],
  ["root_shell", "ROOTED PHONES ONLY (when root is enabled): run a shell command as root, e.g. 'input tap 540 1200', 'input swipe ...', 'input text hello', 'am start -n pkg/.Activity', 'pm list packages', 'settings put system screen_brightness 120', 'screencap -p /sdcard/x.png'.", { command: { type: "string" } }, ["command"]],
  ["ask_user", "Ask the user a short question when you need information you cannot find (which contact, what message, etc.).", { question: { type: "string" } }, ["question"]],
  ["finish", "Call when the task is done or impossible. summary = one or two short sentences for the user, in their language.", { summary: { type: "string" }, success: { type: "boolean" } }, ["summary"]]
];
function agToolDefs(root) {
  return AG_TOOLS.filter(function (t) { return root || t[0] !== "root_shell"; }).map(function (t) {
    return { type: "function", function: { name: t[0], description: t[1], parameters: { type: "object", properties: t[2], required: t[3] } } };
  });
}
function appHidden() { try { if (N && N.appVisible) return !N.appVisible(); } catch (e) {} return document.hidden; }
var AG = { running: false, stop: false, log: [], ctrl: null, voice: false, steps: 0, lastScreen: null, history: [] };
window.Puna.Agent = AG;
function agStatus() { try { return N && N.agentStatus ? JSON.parse(N.agentStatus()) : null; } catch (e) { return null; } }
function agRootOn() { var st = agStatus(); return !!(st && st.rootOn && st.rootGranted); }
function agSystem(st) {
  var L = LBYID[S.replyLang] || LBYID.auto;
  return [
    "You are Puna Agent: you operate the user's Android phone for them by calling tools, one step at a time, until the task is done, then call finish.",
    "Phone: Android " + (st && st.sdk || "?") + ". Accessibility (screen reading/tapping): " + (st && st.a11yLive ? "ON" : "OFF — only use direct tools like open_app, call, whatsapp_message, set_alarm, flashlight, volume, open_settings, youtube, navigate") + ". Root: " + (agRootOn() ? "ON (root_shell available)" : "off") + ".",
    "Rules:",
    "- Prefer direct tools (whatsapp_message, call, set_alarm, set_timer, flashlight, volume, open_settings, youtube, navigate) over tapping through menus.",
    "- After a UI action the result includes the new screen; use the node index (i) for tap/type_text. Call read_screen if you need a fresh look.",
    "- To send a WhatsApp message: whatsapp_message(contact, text), then tap the Send button (desc 'Send' / id 'send'). If the contact number isn't found, WhatsApp opens: tap search, type the name, tap the chat, type_text the message, tap Send.",
    "- The app itself asks the user before sending messages, calls, deleting or paying — just call the tool; if a tool result says the user declined, stop and finish.",
    "- Never invent contacts, numbers or messages. If something essential is missing, use ask_user. Don't loop: if the same action fails twice, try another way or finish with an explanation.",
    "- Keep going without asking for confirmation of harmless steps. Max ~20 steps.",
    "- finish.summary: 1-2 short friendly sentences. LANGUAGE: " + L.rule,
    S.callMe ? "Call the user \"" + S.callMe + "\"." : "",
    "Current date/time in India: " + new Date().toLocaleString("en-IN", { timeZone: "Asia/Kolkata" }) + "."
  ].filter(Boolean).join("\n");
}
var AG_RISK = /\b(send|sent|bhej\w*|pay|payment|pay now|paisa|delete|remove|uninstall|clear data|clear all|erase|format|buy|buy now|place order|order now|checkout|transfer|confirm|call|video call|voice call|dial|reset|sign out|log ?out|block|report)\b|भेज|डिलीट|पेमेंट/i;
var AG_ROOT_RISK = /\b(rm|pm uninstall|pm clear|reboot|wipe|dd|mkfs|format|setprop|svc (data|wifi)|service call|am force-stop|input keyevent (5|6|66))\b/;
function agNode(i) { var s = AG.lastScreen; if (!s || !s.nodes) return null; for (var k = 0; k < s.nodes.length; k++) if (s.nodes[k].i === i) return s.nodes[k]; return null; }
function agRisk(name, a) {
  var app = (AG.lastScreen && AG.lastScreen.app) || "";
  var msgApp = /whatsapp|telegram|messag|mms|sms|gm$|gmail|instagram|signal/i.test(app);
  if (name === "call") return "📞 " + (a.contact || a.number) + " ko call karun?";
  if (name === "tap") {
    var n = a.index != null ? agNode(a.index) : null;
    var lbl = a.text || (n && (n.t || n.d || n.id)) || a.id || "";
    if (AG_RISK.test(lbl) || /(^|_)(send|delete|pay|call)/i.test(a.id || (n && n.id) || "")) return "👉 “" + lbl + "” dabaun?" + (msgApp ? " (message chala jayega)" : "");
  }
  if ((name === "type_text" && a.submit && msgApp) || (name === "press" && a.key === "enter" && msgApp)) return "✉️ Message bhejun?";
  if (name === "root_shell" && AG_ROOT_RISK.test(a.command || "")) return "⚠️ Root command chalaun: " + a.command + " ?";
  return null;
}
var agConfirmWait = {};
window.__punaAgentConfirm = function (id, yes) { var w = agConfirmWait[id]; if (w) { delete agConfirmWait[id]; w(!!yes); } };
function agConfirm(text) {
  return new Promise(function (res) {
    var id = "c" + uid(), done = false;
    function fin(v) { if (done) return; done = true; delete agConfirmWait[id]; $("agConfirm").hidden = true; if (N && N.agentConfirmHide) N.agentConfirmHide(); stopListening(true); agLog("ask", (v ? "✅ Haan: " : "✖ Nahi: ") + text); res(v); }
    agConfirmWait[id] = fin;
    agLog("ask", "🛡️ " + text);
    $("agCfText").textContent = text; $("agConfirm").hidden = false;
    $("agCfYes").onclick = function () { fin(true); }; $("agCfNo").onclick = function () { fin(false); };
    if (window.Puna.autoConfirm != null) { setTimeout(function () { fin(window.Puna.autoConfirm); }, 300); return; }   // tests only
    if (appHidden() && N && N.agentConfirm) N.agentConfirm(id, text);
    if (AG.voice && S.autoSpeak) {
      Speaker.stop(); Speaker.say(text.replace(/[^\p{L}\p{N}\s?.,'“”]/gu, " "), S.voice);
      var tries = 0, waitSpeak = setInterval(function () {
        if (done) { clearInterval(waitSpeak); return; }
        if (Speaker.busy() && ++tries < 40) return;
        clearInterval(waitSpeak);
        listen(S.srLang, { onFinal: function (t) {
          if (/\b(haan|han|ha|yes|ok|okay|theek|thik|kar do|karo|bhej do|bhejo|sure|ji)\b|हाँ|हां|ହଁ/i.test(t)) fin(true);
          else if (/\b(nahi|nahin|na|no|mat|ruko|cancel|stop)\b|नहीं|ନା/i.test(t)) fin(false);
        }, onError: function () {}, onEnd: function () {} }, { silence: 900 });
      }, 250);
    }
    setTimeout(function () { fin(false); }, 90000);
  });
}
function agLog(kind, text, sub) {
  AG.log.push({ kind: kind, text: text, sub: sub || "", at: Date.now() });
  $("agLogCard").hidden = false;
  var el = document.createElement("div"); el.className = "st " + kind;
  el.textContent = text; if (sub) { var s = document.createElement("small"); s.textContent = sub; el.appendChild(s); }
  $("agLog").appendChild(el); el.scrollIntoView({ block: "nearest" });
}
function agTool(name, args) {
  if (!N || !N.agentTool) return Promise.resolve({ ok: false, error: "Agent sirf Android app mein chalta hai" });
  return nativeTask(function (id) { N.agentTool(id, name, JSON.stringify(args || {})); }, null, 45000).catch(function (e) { return { ok: false, error: e.message }; });
}
function agTrimHistory(msgs) {
  // keep only the 2 newest screens in full; older ones shrink to save tokens and time
  var seen = 0;
  for (var i = msgs.length - 1; i >= 0; i--) {
    var m = msgs[i]; if (m.role !== "tool" || m.content.indexOf('"screen"') < 0) continue;
    if (++seen > 2) { try { var j = JSON.parse(m.content); if (j.screen) { j.screen = { app: j.screen.app, note: "old screen omitted" }; m.content = JSON.stringify(j); } } catch (e) {} }
  }
  return msgs;
}
async function agBrain(msgs, tools, signal) {
  if (window.Puna.mockAgent) {   // tests: scripted planner
    var mk = window.Puna.mockAgent, step = typeof mk === "function" ? mk(msgs) : mk.shift();
    await sleep(150);
    if (!step) return { content: "", tool_calls: [{ id: "f", type: "function", function: { name: "finish", arguments: JSON.stringify({ summary: "Ho gaya." }) } }] };
    return { content: step.say || "", tool_calls: (step.calls || [step]).filter(function (c) { return c.name; }).map(function (c, k) { return { id: "m" + uid() + k, type: "function", function: { name: c.name, arguments: JSON.stringify(c.args || {}) } }; }) };
  }
  var k = brainKind(); if (!k) throw NoKey();
  if (k === "gemini") return agGemini(msgs, tools, signal);
  var chain = ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"].filter(function (m) { return !(MODELS.groq || []).length || MODELS.groq.indexOf(m) >= 0; });
  if (!chain.length) chain = ["openai/gpt-oss-120b"];
  var last;
  for (var i = 0; i < chain.length; i++) {
    var body = { model: chain[i], messages: msgs, tools: tools, tool_choice: "required", temperature: 0.2, max_completion_tokens: 1200 };
    if (/gpt-oss/.test(chain[i])) body.reasoning_effort = "low";
    var r = await fetch(GROQ + "/chat/completions", { method: "POST", signal: signal, headers: { Authorization: "Bearer " + S.groqKey, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (r.ok) { var j = await r.json(), m = j.choices[0].message; m.model = chain[i]; return m; }
    try { await httpErr(r, "Groq"); } catch (e) {
      last = e;
      if (r.status === 400 && /tool_use_failed|failed to call/i.test(e.message)) { body.tool_choice = "auto"; continue; }
      if (!(r.status === 400 || r.status === 403 || r.status === 404) || !/model|access|decommission|not.?found|permission/i.test(e.message)) throw e;
    }
  }
  throw last;
}
async function agGemini(msgs, tools, signal) {
  var sys = msgs[0].content, contents = [];
  msgs.slice(1).forEach(function (m) {
    if (m.role === "user") contents.push({ role: "user", parts: [{ text: m.content }] });
    else if (m.role === "assistant") {
      var parts = []; if (m.content) parts.push({ text: m.content });
      (m.tool_calls || []).forEach(function (c) { parts.push({ functionCall: { name: c.function.name, args: JSON.parse(c.function.arguments || "{}") } }); });
      contents.push({ role: "model", parts: parts });
    } else if (m.role === "tool") contents.push({ role: "user", parts: [{ functionResponse: { name: m.name, response: { result: m.content.slice(0, 6000) } } }] });
  });
  var decl = tools.map(function (t) { var p = JSON.parse(JSON.stringify(t.function.parameters)); if (!Object.keys(p.properties).length) delete p.properties; return { name: t.function.name, description: t.function.description, parameters: Object.keys(p.properties || {}).length ? p : undefined }; });
  var body = { systemInstruction: { parts: [{ text: sys }] }, contents: contents, tools: [{ functionDeclarations: decl }], toolConfig: { functionCallingConfig: { mode: "ANY" } }, generationConfig: { temperature: 0.2 } };
  var r = await fetch(GEM + "/models/" + gemModel() + ":generateContent", { method: "POST", signal: signal, headers: { "x-goog-api-key": S.geminiKey, "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!r.ok) await httpErr(r, "Gemini");
  var j = await r.json(), parts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [], text = "", calls = [];
  parts.forEach(function (p, k) { if (p.text) text += p.text; if (p.functionCall) calls.push({ id: "g" + uid() + k, type: "function", function: { name: p.functionCall.name, arguments: JSON.stringify(p.functionCall.args || {}) } }); });
  return { content: text, tool_calls: calls, model: gemModel() };
}
function agAskUser(q) {
  return new Promise(function (res) {
    agLog("ask", "❓ " + q);
    if (window.Puna.autoAnswer != null) { setTimeout(function () { res(window.Puna.autoAnswer); }, 200); return; }
    if (N && N.bringToFront && appHidden()) N.bringToFront();
    if (AG.voice && S.autoSpeak) Speaker.say(q, S.voice);
    var a = prompt(q); res(a == null ? "" : a);
  });
}
async function agentRun(task, opt) {
  opt = opt || {};
  task = String(task || "").trim(); if (!task) return null;
  if (AG.running) { toast("Agent pehle se chal raha hai — ⏹ Stop dabao"); return null; }
  if (!window.Puna.mockAgent && !brainKind()) { var m0 = "Agent ke liye Settings mein Groq key daalo 🙏"; toast(m0); return { summary: m0, success: false }; }
  var st = agStatus() || {};
  AG.running = true; AG.stop = false; AG.voice = !!opt.voice; AG.steps = 0; AG.lastScreen = null; AG.log = []; AG.ctrl = new AbortController();
  $("agLog").innerHTML = ""; $("agLogCard").hidden = false; $("agState").textContent = "chal raha hai…"; $("agStop").hidden = false; $("agGo").disabled = true;
  if (N && N.agentBubble) N.agentBubble(task);
  agLog("fin", "🎯 " + task, (st.a11yLive ? "Accessibility ✓" : "Accessibility ✗ (sirf direct tools)") + (agRootOn() ? " · Root ✓" : ""));
  var tools = agToolDefs(agRootOn());
  var msgs = [{ role: "system", content: agSystem(st) }, { role: "user", content: task }];
  var result = { summary: "", success: false }, repeats = {}, MAXS = 22;
  try {
    while (!AG.stop && AG.steps < MAXS) {
      var m = await agBrain(agTrimHistory(msgs), tools, AG.ctrl.signal);
      if (AG.stop) break;
      var calls = m.tool_calls || [];
      msgs.push({ role: "assistant", content: m.content || "", tool_calls: calls.length ? calls : undefined });
      if (!calls.length) { result = { summary: m.content || "Ho gaya.", success: true }; break; }
      var finished = false;
      for (var ci = 0; ci < calls.length && !AG.stop; ci++) {
        var c = calls[ci], name = c.function.name, args = {};
        try { args = JSON.parse(c.function.arguments || "{}") || {}; } catch (e) { args = {}; }
        AG.steps++;
        var res;
        if (name === "finish") { result = { summary: args.summary || "Ho gaya.", success: args.success !== false }; finished = true; res = { ok: true }; }
        else if (name === "ask_user") { var ans = await agAskUser(args.question || "?"); res = { ok: true, answer: ans || "(no answer)" }; }
        else {
          var key = name + JSON.stringify(args); repeats[key] = (repeats[key] || 0) + 1;
          var risk = agRisk(name, args);
          if (risk && !(await agConfirm(risk))) { res = { ok: false, error: "USER DECLINED this action. Do not retry it; finish." }; agLog("bad", "✖ " + name + " — aapne mana kiya"); }
          else if (repeats[key] > 2 && !/^(read_screen|wait|scroll|press)$/.test(name)) { res = { ok: false, error: "Same action already tried twice — do something different or finish." }; agLog("bad", "↻ " + name + " baar baar — roka"); }
          else {
            if (N && N.agentBubble) N.agentBubble(name + " " + (args.text || args.name || args.contact || args.query || args.page || ""));
            res = await agTool(name, args);
            if (res.screen) AG.lastScreen = res.screen;
            agLog(res.ok ? "ok" : "bad", (res.ok ? "✓ " : "✗ ") + agDescribe(name, args, res), res.ok ? (res.screen ? "📱 " + (res.screen.app || "") + " · " + (res.screen.count || 0) + " items" : (res.note || "")) : res.error);
          }
        }
        msgs.push({ role: "tool", tool_call_id: c.id, name: name, content: JSON.stringify(res).slice(0, 7000) });
        if (finished) break;
      }
      if (finished) break;
    }
    if (AG.stop) result = { summary: "Theek hai, ruk gayi.", success: false };
    else if (!result.summary) result = { summary: "Bahut steps ho gaye, isliye ruk gayi. Thoda aasaan karke batao?", success: false };
  } catch (e) {
    result = { summary: AG.stop ? "Theek hai, ruk gayi." : friendlyErr(e), success: false };
  } finally {
    AG.running = false; $("agStop").hidden = true; $("agGo").disabled = false; $("agConfirm").hidden = true;
    if (N && N.agentBubble) N.agentBubble("");
    $("agState").textContent = (result.success ? "✓ ho gaya" : "• ruka") + " · " + AG.steps + " steps";
    agLog("fin", (result.success ? "✅ " : "⏹ ") + result.summary);
    AG.history.unshift({ task: task, at: Date.now(), steps: AG.steps, summary: result.summary, ok: result.success }); AG.history = AG.history.slice(0, 20);
    window.Puna.lastAgent = { task: task, result: result, log: AG.log.slice(), steps: AG.steps };
  }
  return result;
}
window.Puna.agentRun = agentRun;
function agDescribe(n, a, r) {
  switch (n) {
    case "open_app": return "App khola: " + (r.opened || a.name);
    case "tap": return "Tap: " + (r.tapped || a.text || (a.index != null ? "#" + a.index : a.id || (a.x + "," + a.y)));
    case "type_text": return "Likha: " + a.text;
    case "read_screen": return "Screen padhi";
    case "whatsapp_message": return "WhatsApp: " + (a.contact || a.number) + " — “" + a.text + "”";
    case "send_sms": return "SMS: " + (a.contact || a.number);
    case "call": return "Call: " + (r.number || a.contact || a.number) + (r.mode ? " (" + r.mode + ")" : "");
    case "set_alarm": return "Alarm: " + (r.alarm || (a.hour + ":" + a.minute));
    case "set_timer": return "Timer: " + a.seconds + " s";
    case "flashlight": return "Torch " + (a.on ? "ON" : "OFF");
    case "open_settings": return "Settings: " + a.page;
    default: return n + " " + JSON.stringify(a).slice(0, 80);
  }
}
function agentStop(why) { if (!AG.running) return; AG.stop = true; try { AG.ctrl.abort(); } catch (e) {} Object.keys(agConfirmWait).forEach(function (k) { agConfirmWait[k](false); }); if (N && N.bringToFront && why === "bubble") N.bringToFront(); }
window.__punaAgentStop = agentStop;
$("agStop").addEventListener("click", function () { agentStop("button"); });
$("agGo").addEventListener("click", function () { var t = $("agTask").value.trim(); if (!t) { toast("Pehle kaam likho ya 🎤 se bolo"); return; } agentRun(t, { voice: false }); });
document.querySelectorAll("#agQuick .pill").forEach(function (b) { b.addEventListener("click", function () { $("agTask").value = b.dataset.q; agentRun(b.dataset.q, {}); }); });
$("agMic").addEventListener("click", function () {
  var btn = this; if (btn.classList.contains("on")) { stopListening(false); return; }
  btn.classList.add("on");
  listen(S.srLang, { onPartial: function (t) { $("agTask").value = t; }, onFinal: function (t) { $("agTask").value = t; }, onError: function (e, m) { if (e !== "no-speech" && e !== "aborted") toast("Mic: " + (m || e)); },
    onEnd: function () { btn.classList.remove("on"); var t = $("agTask").value.trim(); if (t) agentRun(t, { voice: true }); } }, { silence: 1000 });
});
function renderAgent() {
  var st = agStatus(), el = $("agStat");
  if (!st) { el.innerHTML = "<span class='no'>Sirf Android app mein</span>"; return; }
  function chip(ok, a, b) { return "<span class='" + (ok ? "ok" : "no") + "'>" + (ok ? "✓ " + a : "• " + b) + "</span>"; }
  el.innerHTML = chip(st.a11yLive, "Accessibility ON", st.a11y ? "Accessibility ON hai par service connect nahi hui — toggle off/on karo" : "Accessibility OFF (screen padhna/tap band)") +
    chip(st.contacts, "Contacts", "Contacts permission nahi") + chip(st.callPhone, "Direct call", "Call = dialer khulega") +
    (st.rootAvailable ? chip(st.rootGranted && st.rootOn, "Root ON", "Root mila — chahiye to ON karo") : "<span class='no'>• Root nahi (normal phone — basic mode)</span>");
  $("agA11yBtn").hidden = st.a11yLive; $("agGuide").open = !st.a11yLive && !$("agGuide").dataset.seen;
  $("agContactsBtn").hidden = st.contacts; $("agCallBtn").hidden = st.callPhone;
  $("agVoice").checked = S.agentVoice;
  $("agRoot").checked = st.rootOn && st.rootGranted; $("agRoot").disabled = !st.rootAvailable;
}
window.Puna.renderAgent = renderAgent;
window.Puna.agTool = agTool;
$("agVoice").addEventListener("change", function () { S.agentVoice = this.checked; saveS(); });
$("agA11yBtn").addEventListener("click", function () { $("agGuide").open = true; $("agGuide").dataset.seen = "1"; if (N && N.openA11ySettings) N.openA11ySettings(); });
$("agContactsBtn").addEventListener("click", function () { if (N && N.agentPermission) N.agentPermission("contacts"); setTimeout(renderAgent, 3000); });
$("agCallBtn").addEventListener("click", function () { if (N && N.agentPermission) N.agentPermission("call"); setTimeout(renderAgent, 3000); });
$("agRoot").addEventListener("change", async function () {
  var on = this.checked, msg = $("agRootMsg");
  if (!on) { N.setRoot(false); msg.textContent = "Root band."; renderAgent(); return; }
  if (!confirm("Root se Puna phone ko poori tarah chala sakti hai (system commands). Sirf apne phone par ON karo. Continue?")) { this.checked = false; return; }
  msg.textContent = "Root permission maang rahi hoon — Magisk/KernelSU prompt mein Grant dabao…";
  try { var r = await nativeTask(function (id) { N.rootRequest(id); }, null, 40000); N.setRoot(!!r.granted); msg.textContent = r.granted ? "✅ Root mil gaya — full automation ON." : "❌ Root nahi mila: " + (r.error || r.out || ""); }
  catch (e) { N.setRoot(false); msg.textContent = "❌ " + e.message; }
  renderAgent();
});
document.addEventListener("visibilitychange", function () { if (!document.hidden && currentTab === "auto") renderAgent(); });
// Assistant -> Agent: phone commands said to the assistant run the agent and the result is spoken back
var AG_INTENT = /\b(kholo|khol do|kholna|open|launch|start karo|chalao|chala do|bajao|play karo|call karo|call lagao|phone karo|phone lagao|bhejo|bhej do|send karo|message karo|msg karo|alarm|timer|torch|flashlight|flash light|volume|wifi|wi-fi|bluetooth|screenshot|navigate|rasta dikhao|le chalo|on karo|off karo|tap karo|scroll karo|type karo)\b|खोलो|भेजो|कॉल करो|चलाओ|ଖୋଲ|ପଠାଅ/i;
var AG_NOT = /\b(kaise|kaisa|how (do|to|can)|what is|kya hota|kyun|why|matlab|explain|samjhao|batao ki|meaning)\b/i;
function isPhoneTask(t) { return AG_INTENT.test(t) && !AG_NOT.test(t); }
window.Puna.isPhoneTask = isPhoneTask;

/* ------------------------------------------------------------------ tabs + init */
var currentTab = "assistant";
function showTab(t) {
  currentTab = t;
  document.querySelectorAll(".view").forEach(function (v) { v.classList.toggle("active", v.dataset.tab === t); });
  document.querySelectorAll("#tabs button").forEach(function (b) { b.classList.toggle("on", b.dataset.tab === t); });
  $("cDrawer").hidden = true;
  if (t !== "assistant") { voiceLoop = false; if ($("orbWrap").dataset.state === "listening") { stopListening(true); setOrb("idle"); } }
  if (t === "chat") renderChat();
  if (t === "settings") { renderMem(); syncSettingsUI(); renderBg(); }
  if (t === "auto") renderAgent();
}
window.Puna.showTab = showTab;
$("tabs").addEventListener("click", function (e) { var b = e.target.closest("button"); if (b) showTab(b.dataset.tab); });
window.__punaBack = function () {
  if (!$("voiceSheet").hidden) { $("voiceSheet").hidden = true; return true; }
  if (!$("langSheet").hidden) { $("langSheet").hidden = true; return true; }
  if (!$("agConfirm").hidden) { window.__punaAgentConfirm && Object.keys(agConfirmWait).forEach(function (k) { agConfirmWait[k](false); }); return true; }
  if (AG.running) { agentStop("back"); return true; }
  if (!$("cDrawer").hidden) { $("cDrawer").hidden = true; return true; }
  if (currentTab !== "assistant") { showTab("assistant"); return true; }
  if (Speaker.busy() || active.busy || SRec.h) { stopAll(); return true; }
  return false;
};
function init() {
  fillSelect($("sReplyLang"), LANGS.map(function (l) { return [l.id, l.label]; }), S.replyLang);
  fillSelect($("sSrLang"), SR_LANGS, S.srLang);
  fillSelect($("bLang"), LANGS.filter(function (l) { return l.id !== "auto"; }).map(function (l) { return [l.id, l.label]; }), BC.lang);
  BC.speed = S.speed; $("bSpeed").value = S.speed; $("bSpeedVal").textContent = (+S.speed).toFixed(1) + "×";
  renderBVoices(); renderBHist(); syncSettingsUI(); renderMem(); renderChat();
  setStatus(idleStatus());
  var info = {}; try { info = N && N.info ? JSON.parse(N.info()) : {}; } catch (e) {}
  DEBUG = !!info.debug;
  $("sAbout").textContent = "Puna AI v" + (info.version || "web") + " · Android " + (info.sdk || "-") + " · WebView " + (info.webview || "-") + " · Phase 1 + Fast voice + Phase 3 Agent";
  $("sVoiceMap").innerHTML = VOICES.map(function (v) { return "<div><b>" + v.name + "</b> (" + v.g + ") → " + v.edge + "</div>"; }).join("") + "<div><b>Odia</b> → phone Odia voice, ya Bengali-lipi neural (Archita/Anup), ya Meta MMS Odia</div>";
  if (S.groqKey && !(MODELS.groq || []).length) fetchModels("groq").catch(function () {});
  window.Puna.ready = true;
  var pendingAct = N && N.consumeIntent ? N.consumeIntent() : "";
  if (pendingAct) window.__punaIntent(pendingAct);
}
window.Puna.S = S;
window.Puna.VOICES = VOICES;
window.Puna.save = function () { saveS(); syncSettingsUI(); };
init();
})();
