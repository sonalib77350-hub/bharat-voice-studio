/* Bharat Voice Studio - Android WebView shim.
 * Injected at the very top of <head> (before the page's own scripts) so that
 * `window.speechSynthesis`, `SpeechRecognition` and blob downloads work inside
 * Android WebView, backed by the native AndroidBridge (MainActivity.java). */
(function () {
  if (window.__bvsShim || !window.AndroidBridge) return;
  window.__bvsShim = true;
  var B = window.AndroidBridge;

  function target(o) {
    o._l = {};
    o.addEventListener = function (t, f) { if (f) (o._l[t] = o._l[t] || []).push(f); };
    o.removeEventListener = function (t, f) { var a = o._l[t]; if (a) { var i = a.indexOf(f); if (i >= 0) a.splice(i, 1); } };
    o._fire = function (t, e) {
      e = e || {}; e.type = t; e.target = o; e.currentTarget = o; e.srcElement = o;
      e.preventDefault = e.preventDefault || function () {}; e.stopPropagation = e.stopPropagation || function () {};
      try { if (typeof o["on" + t] === "function") o["on" + t].call(o, e); } catch (x) { console.error(x); }
      (o._l[t] || []).slice().forEach(function (f) { try { f.call(o, e); } catch (x) { console.error(x); } });
    };
    o.dispatchEvent = function (e) { o._fire(e.type, e); return true; };
    return o;
  }
  function def(name, val) {
    try { Object.defineProperty(window, name, { value: val, configurable: true, writable: true }); }
    catch (x) { try { window[name] = val; } catch (y) {} }
  }

  /* ---------------- SpeechRecognition (Android SpeechRecognizer) ---------------- */
  var recs = {}, seq = 0;
  function SpeechRecognition() {
    target(this);
    this.lang = ""; this.continuous = false; this.interimResults = false; this.maxAlternatives = 1;
    this.grammars = null; this._id = ++seq; this._active = false; this._results = [];
    this.onresult = this.onerror = this.onend = this.onstart = this.onaudiostart = this.onaudioend =
      this.onspeechstart = this.onspeechend = this.onsoundstart = this.onsoundend = this.onnomatch = null;
    recs[this._id] = this;
  }
  SpeechRecognition.prototype.start = function () {
    if (this._active) { var err = new Error("recognition has already started"); err.name = "InvalidStateError"; throw err; }
    this._active = true; this._results = [];
    B.srStart(this._id, String(this.lang || document.documentElement.lang || navigator.language || "en-US"),
      !!this.interimResults, +this.maxAlternatives || 1);
  };
  SpeechRecognition.prototype.stop = function () { if (this._active) B.srStop(this._id); };
  SpeechRecognition.prototype.abort = function () { if (this._active) B.srAbort(this._id); };

  function makeResult(p) {
    var alts = (p.alts && p.alts.length ? p.alts : [{ t: "", c: 0 }]).map(function (a) { return { transcript: a.t, confidence: a.c }; });
    var r = alts.slice();
    r.isFinal = !!p.final;
    r.item = function (i) { return r[i]; };
    return r;
  }
  function resultList(arr) {
    var l = arr.slice();
    l.item = function (i) { return l[i]; };
    return l;
  }
  // Native -> JS
  window.__bvsSR = function (id, type, p) {
    var r = recs[id]; if (!r) return;
    p = p || {};
    if (type === "start") { r._fire("start"); r._fire("audiostart"); r._fire("soundstart"); }
    else if (type === "speechstart") { r._fire("speechstart"); }
    else if (type === "speechend") { r._fire("speechend"); }
    else if (type === "result") {
      if (!p.final && !r.interimResults) return;
      var res = makeResult(p), n = r._results.length;
      var idx = (n && !r._results[n - 1].isFinal) ? n - 1 : n;
      r._results[idx] = res;
      r._fire("result", { resultIndex: idx, results: resultList(r._results), interpretation: null, emma: null });
    }
    else if (type === "nomatch") { r._fire("nomatch", { resultIndex: 0, results: resultList([]) }); }
    else if (type === "error") { r._fire("error", { error: p.error || "unknown", message: p.message || "" }); }
    else if (type === "end") {
      r._active = false;
      r._fire("audioend"); r._fire("end");
    }
  };
  def("SpeechRecognition", SpeechRecognition);
  def("webkitSpeechRecognition", SpeechRecognition);
  if (!window.SpeechGrammarList) {
    var SGL = function () { this.length = 0; };
    SGL.prototype.addFromString = SGL.prototype.addFromURI = function () {};
    def("SpeechGrammarList", SGL); def("webkitSpeechGrammarList", SGL);
  }

  /* ---------------- speechSynthesis (Android TextToSpeech) ---------------- */
  function SpeechSynthesisUtterance(text) {
    target(this);
    this.text = text == null ? "" : String(text);
    this.lang = ""; this.voice = null; this.rate = 1; this.pitch = 1; this.volume = 1;
    this.onstart = this.onend = this.onerror = this.onpause = this.onresume = this.onboundary = this.onmark = null;
  }
  var utts = {}, useq = 0, voices = [];
  var synth = target({ speaking: false, pending: false, paused: false, onvoiceschanged: null });
  function setVoices(list, fire) {
    voices = (list || []).map(function (v) {
      return { name: v.name, lang: v.lang, voiceURI: v.name, localService: !!v.local, "default": !!v.def };
    });
    if (fire) synth._fire("voiceschanged");
  }
  function refreshFlags() {
    var n = Object.keys(utts).length;
    synth.speaking = n > 0; synth.pending = n > 1;
  }
  synth.getVoices = function () {
    if (!voices.length) { try { setVoices(JSON.parse(B.ttsVoices() || "[]"), false); } catch (x) {} }
    return voices.slice();
  };
  synth.speak = function (u) {
    if (!u) return;
    var id = "u" + (++useq);
    utts[id] = u; refreshFlags();
    B.ttsSpeak(id, String(u.text || ""), String(u.lang || ""), u.voice && u.voice.name ? String(u.voice.name) : "",
      u.rate == null ? 1 : +u.rate, u.pitch == null ? 1 : +u.pitch, u.volume == null ? 1 : +u.volume);
  };
  synth.cancel = function () {
    var pending = utts; utts = {}; refreshFlags();
    B.ttsCancel();
    Object.keys(pending).forEach(function (k) { pending[k]._fire("error", { error: "canceled", utterance: pending[k], charIndex: 0 }); });
  };
  synth.pause = function () { synth.paused = true; };
  synth.resume = function () { synth.paused = false; };
  window.__bvsTTS = function (id, type, p) {
    if (type === "voices") { setVoices(p, true); return; }
    var u = utts[id]; if (!u) return;
    if (type === "start") { synth.speaking = true; u._fire("start", { utterance: u, charIndex: 0, elapsedTime: 0, name: "" }); }
    else if (type === "end" || type === "error") {
      delete utts[id]; refreshFlags();
      if (type === "end") u._fire("end", { utterance: u, charIndex: u.text.length, elapsedTime: 0, name: "" });
      else u._fire("error", { utterance: u, error: (p && p.error) || "synthesis-failed", charIndex: 0 });
    }
  };
  def("SpeechSynthesisUtterance", SpeechSynthesisUtterance);
  def("speechSynthesis", synth);

  /* ---------------- downloads of blob: / data: links ---------------- */
  function saveBlob(blob, name) {
    var id = "d" + Date.now() + Math.random().toString(36).slice(2), CH = 384 * 1024, off = 0;
    B.dlBegin(id, String(name || ""), String(blob.type || ""));
    (function next() {
      if (off >= blob.size) { B.dlEnd(id); return; }
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result); B.dlChunk(id, s.substring(s.indexOf(",") + 1)); off += CH; next(); };
      fr.onerror = function () { B.dlFail(id, "read error"); };
      fr.readAsDataURL(blob.slice(off, off + CH));
    })();
  }
  function saveUrl(url, name) {
    fetch(url).then(function (r) { return r.blob(); })
      .then(function (b) { saveBlob(b, name); })
      .catch(function (e) { B.toast("Download nahi hua: " + ((e && e.message) || e)); });
  }
  window.__bvsSaveUrl = saveUrl;
  document.addEventListener("click", function (e) {
    var el = e.target;
    while (el && el.nodeName !== "A") el = el.parentNode;
    if (!el || !el.hasAttribute || !el.hasAttribute("download")) return;
    var href = el.href || "";
    if (!/^(blob:|data:)/i.test(href)) return;
    e.preventDefault();
    saveUrl(href, el.getAttribute("download") || "");
  }, true);
})();
