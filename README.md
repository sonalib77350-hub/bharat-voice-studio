# Puna AI — Android AI assistant (Phase 1)

Branch `puna-ai` of this repo holds Sonali's **Puna AI** app (separate from Bharat Voice Studio on `main`).

- **Download:** Releases → `puna-ai-latest` → `PunaAI.apk` (package `com.sonali.punaai`, fixed signing key).
- **Voice assistant:** big mic, continuous talk, Android speech recognition (Hindi/English/Odia/Bengali/…), streaming replies spoken by Microsoft Edge neural voices (free) with Android TTS fallback, speed 0.8–1.6 (default 1.3), switch voice by saying e.g. “Rohan se baat karni hai”.
- **Brain:** your own Groq key (Settings, stored only on the phone); `openai/gpt-oss-120b/20b`, with Groq `browser_search` for news/current questions (sources shown). Optional Gemini key (Google Search grounding).
- **Memory:** facts auto-extracted from chat, listed in Settings with delete/clear.
- **Broadcast:** topic → web research → multi-voice script → stitched audio (M4A) → optional Short/Landscape MP4 with animated text → save to Download/PunaAI or share to WhatsApp/Telegram. “Apna script” mode works without any key.
- **Chat:** multiple conversations, markdown, copy, regenerate, share.
- **24/7:** Settings → “Hamesha chalu” runs a foreground service with a persistent notification (🎙️ Talk / ⏹ Stop), restarts after reboot, asks to lift battery optimization. Optional “Hey Puna” hands-free mode (beta, on-device SpeechRecognizer loop — uses noticeable battery). Also a Quick Settings tile “Puna Talk” and launcher shortcuts (long-press icon).
- Builder/IDE (Phase 2) and Automation (Phase 3): coming soon.

| Voice | Gender | Edge neural voice |
|---|---|---|
| Sonali (default) | F | en-US-AvaMultilingualNeural |
| Emma | F | en-US-EmmaMultilingualNeural |
| Swara | F | hi-IN-SwaraNeural |
| Anaya | F | en-IN-NeerjaExpressiveNeural |
| Christina | F | en-GB-SoniaNeural |
| Priyanshi | F | mr-IN-AarohiNeural |
| Archita | F | bn-IN-TanishaaNeural |
| Sagarika | F | ne-NP-HemkalaNeural |
| Aarav | M | en-IN-PrabhatNeural |
| Rohan | M | hi-IN-MadhurNeural |
| Anup | M | bn-IN-BashkarNeural |
| Prince | M | en-US-AndrewMultilingualNeural |
| Primosh | M | mr-IN-ManoharNeural |

Odia: the free Edge service has no Odia voice, so Odia text is spoken by the phone's Odia TTS if installed, otherwise by a Bengali neural voice reading the Odia text converted to Bengali script (approximate), or Meta MMS Odia on-device (Settings).

Build/CI: `.github/workflows/puna-ai.yml` (only on this branch) — release APK, headless browser test (`tests/`), Android 14 emulator test with real adb taps (`ci/`), results on branch `puna-emu-results`.
