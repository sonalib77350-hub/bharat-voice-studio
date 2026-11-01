import sys, os, json, base64, asyncio, subprocess, threading, http.server, socketserver
sys.path.insert(0, '/tmp/et')
try:
    import fixenv  # sandbox-only proxy fix
except Exception:
    pass
import edge_tts
WWW = sys.argv[1]; MEDIA = sys.argv[2]; PORT = int(sys.argv[3]); os.makedirs(MEDIA, exist_ok=True)
loop = asyncio.new_event_loop(); threading.Thread(target=loop.run_forever, daemon=True).start()
async def synth(text, voice, rate, pitch):
    b = b""
    async for c in edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).stream():
        if c["type"] == "audio": b += c["data"]
    return b
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def _json(self, o, code=200):
        d = json.dumps(o).encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(d))); self.end_headers(); self.wfile.write(d)
    def do_GET(self):
        p = self.path.split("?")[0]
        if p in ("/", "/index.html"):
            h = open(os.path.join(WWW, "index.html"), encoding="utf-8").read().replace("<script src=\"app.js\">", "<script src=\"/mock_native.js\"></script><script src=\"app.js\">")
            d = h.encode(); self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Content-Length", str(len(d))); self.end_headers(); self.wfile.write(d); return
        if p == "/mock_native.js": f = os.path.join(os.path.dirname(__file__), "mock_native.js")
        elif p.startswith("/media/"): f = os.path.join(MEDIA, os.path.basename(p))
        else: f = os.path.join(WWW, p.lstrip("/"))
        if not os.path.exists(f): self.send_response(404); self.end_headers(); return
        d = open(f, "rb").read(); ext = f.rsplit(".", 1)[-1]
        ct = {"js": "text/javascript", "css": "text/css", "mp3": "audio/mpeg", "wav": "audio/wav", "m4a": "audio/mp4", "html": "text/html"}.get(ext, "application/octet-stream")
        self.send_response(200); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(d))); self.end_headers(); self.wfile.write(d)
    def do_POST(self):
        j = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        p = self.path
        try:
            if p == "/api/synth":
                b = asyncio.run_coroutine_threadsafe(synth(j["text"], j["voice"], j.get("rate") or "+0%", j.get("pitch") or "+0Hz"), loop).result(60)
                if not b: return self._json({"ok": False, "error": "no audio"})
                n = "tts-%s.mp3" % j["id"]; open(os.path.join(MEDIA, n), "wb").write(b)
                return self._json({"ok": True, "name": n, "url": "/media/" + n, "bytes": len(b)})
            if p == "/api/wbegin": open(os.path.join(MEDIA, j["name"]), "wb").close(); return self._json({"ok": True})
            if p == "/api/wchunk": open(os.path.join(MEDIA, j["name"]), "ab").write(base64.b64decode(j["b64"])); return self._json({"ok": True})
            if p == "/api/m4a":
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", os.path.join(MEDIA, j["wav"]), "-c:a", "aac", "-b:a", "96k", os.path.join(MEDIA, j["out"])], check=True)
                return self._json({"ok": True, "name": j["out"], "url": "/media/" + j["out"], "bytes": os.path.getsize(os.path.join(MEDIA, j["out"]))})
        except Exception as e:
            return self._json({"ok": False, "error": repr(e)})
        self._json({"ok": False, "error": "unknown"}, 404)
class T(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
T(("127.0.0.1", PORT), H).serve_forever()
