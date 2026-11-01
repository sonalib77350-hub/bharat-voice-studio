// Browser stand-in for the Android PunaNative bridge (tests only). Edge TTS is real (server runs edge-tts).
(function () {
  var calls = window.__mockCalls = [];
  function xhr(method, url, body) { var x = new XMLHttpRequest(); x.open(method, url, false); x.setRequestHeader("Content-Type", "application/json"); x.send(body ? JSON.stringify(body) : null); return JSON.parse(x.responseText || "{}"); }
  function later(id, res) { setTimeout(function () { window.__punaCb(id, res); }, 10); }
  window.PunaNative = {
    info: function () { return JSON.stringify({ version: "mock", sdk: 0, webview: navigator.userAgent.match(/Chrome\/[\d.]+/)[0] }); },
    edgeSynth: function (id, text, voice, rate, pitch) {
      calls.push({ m: "edgeSynth", text: text, voice: voice, rate: rate, pitch: pitch });
      fetch("/api/synth", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id: id, text: text, voice: voice, rate: rate, pitch: pitch }) })
        .then(function (r) { return r.json(); }).then(function (j) { window.__punaCb(id, j); }, function (e) { window.__punaCb(id, { ok: false, error: String(e) }); });
    },
    ttsSpeak: function (id, text, lang, rate) { calls.push({ m: "ttsSpeak", text: text, lang: lang }); setTimeout(function () { window.__punaTTS(id, "end"); }, 50); },
    ttsCancel: function () {}, ttsHasLang: function () { return false; },
    srStart: function (id, lang) { calls.push({ m: "srStart", lang: lang }); var t = window.__mockSpeech || ""; setTimeout(function () { window.__punaSR(id, "start"); if (t) { window.__punaSR(id, "result", { final: false, text: t.slice(0, 5) }); window.__punaSR(id, "result", { final: true, text: t }); } else window.__punaSR(id, "error", { error: "no-speech" }); window.__punaSR(id, "end"); window.__mockSpeech = ""; }, 100); },
    srStop: function () {}, srAbort: function () {}, srAvailable: function () { return true; },
    writeBegin: function (n) { xhr("POST", "/api/wbegin", { name: n }); return n; },
    writeChunk: function (n, b64) { return xhr("POST", "/api/wchunk", { name: n, b64: b64 }).ok; },
    writeEnd: function (n) { return "/media/" + n; },
    makeM4a: function (id, wav, out) { calls.push({ m: "makeM4a", wav: wav }); window.__punaProg(id, 50, "audio"); later(id, xhr("POST", "/api/m4a", { wav: wav, out: out })); },
    makeVideo: function (id, spec, wav, out) { calls.push({ m: "makeVideo", spec: JSON.parse(spec), wav: wav }); later(id, { ok: false, error: "video only on Android" }); },
    saveToDownloads: function (id, name, display) { calls.push({ m: "save", name: name, display: display }); later(id, { ok: true, name: display, uri: "mock" }); },
    share: function (name, text, target) { calls.push({ m: "share", name: name, target: target }); return target ? "app-missing" : "ok"; },
    shareText: function (t) { calls.push({ m: "shareText" }); }, listMedia: function () { return "[]"; },
    deleteMedia: function (n) { calls.push({ m: "deleteMedia", name: n }); }, openUrl: function (u) { calls.push({ m: "openUrl", url: u }); },
    agentStatus: function () { return JSON.stringify({ a11y: true, a11yLive: true, rootAvailable: false, rootGranted: false, rootOn: false, sdk: 34, contacts: true, callPhone: false }); },
    agentTool: function (id, name, args) { calls.push({ m: "agentTool", name: name, args: JSON.parse(args) });
      later(id, { ok: true, opened: name === "open_app" ? "Settings" : undefined, screen: { app: "com.android.settings", count: 3, nodes: [{ i: 0, t: "Network & internet", k: 1, b: "200,300" }, { i: 1, t: "Send", k: 1, b: "900,1800" }, { i: 2, t: "Search settings", k: 1, b: "500,200" }] } }); },
    agentBubble: function () {}, agentConfirmHide: function () {}, edgeWarm: function () { calls.push({ m: "edgeWarm" }); },
    toast: function () {}, keepAwake: function () {}, copy: function (t) { calls.push({ m: "copy", text: t }); }
  };
})();
