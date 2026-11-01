| Area | Function | Status | Detail | Evidence |
|---|---|---|---|---|
| Install | adb install debug.apk | PASS | Performing Streamed Install Success |  |
| Launch | app process alive after start | PASS | pid=3127 | 01_launch.png |
| Launch | UI ready, native bridge info | PASS | {"version":"1.1.7","sdk":34,"webview":"113.0.5672.136","debug":true,"model":"sdk_gphone64_x86_64"} |  |
| Defaults | Sonali is the default voice, speed 1.3 | PASS | sonali 1.3 |  |
| Assistant | typed message (real taps/keyboard) without key -> 'Settings mein Groq key daalo' | PASS | Pehle Settings mein Groq key daalo 🙏 (Settings → AI Brain → Groq API key). Key free milti hai: console.groq.com/keys | 02_nokey_reply.png |
| Voice | native Edge neural TTS synthesized the reply (Sonali, en-US-AvaMultilingualNeural) | PASS | [{"ok":true,"name":"tts-tmvxkyzoc9ilh5.mp3","url":"/media/tts-tmvxkyzoc9ilh5.mp3","bytes":25056,"ms":1997,"first":1220,"warm":false},{"ok":true,"name":"tts-tmvxkyzobf2gua.mp3","url":"/media/tts-tmvxkyzobf2gua.mp3","bytes":24048,"ms":2007,"first":1163,"warm":false}] |  |
| Voice | reply audio actually started playing in the app | PASS | playing events=1 |  |
| Voice | 'Rohan se baat karni hai' switches to Rohan | PASS | Hello! Ab se main Rohan ki awaaz mein baat karunga. Batao, ye awaaz kaisi lagi? | 03_rohan.png |
| Voice | 'Sonali se baat karni hai' switches back to Sonali | PASS | sonali |  |
| Voices | Edge voice Sonali (native) | PASS | 1.87 sec |  |
| Voices | Edge voice Emma (native) | PASS | 1.92 sec |  |
| Voices | Edge voice Swara (native) | PASS | 2.66 sec |  |
| Voices | Edge voice Anaya (native) | PASS | 1.99 sec |  |
| Voices | Edge voice Christina (native) | PASS | 1.87 sec |  |
| Voices | Edge voice Priyanshi (native) | PASS | 2.47 sec |  |
| Voices | Edge voice Archita (native) | PASS | 2.02 sec |  |
| Voices | Edge voice Sagarika (native) | PASS | 2.04 sec |  |
| Voices | Edge voice Aarav (native) | PASS | 1.9 sec |  |
| Voices | Edge voice Rohan (native) | PASS | 2.04 sec |  |
| Voices | Edge voice Anup (native) | PASS | 1.87 sec |  |
| Voices | Edge voice Prince (native) | PASS | 1.87 sec |  |
| Voices | Edge voice Primosh (native) | PASS | 2.35 sec |  |
| Voices | Edge voice Odia(Archita-Bengali) (native) | PASS | 4.68 sec |  |
| Fast mode | reply-start -> first audio playing, sockets pre-warmed at mic start (median of 4; LLM mocked ttft 350 ms) | PASS | median ms: {"firstToken": 352, "firstSentence": 476, "tts": 208, "ttsFirstAudio": 78, "play": 777} / each play: [667, 756, 777, 791] | 04_latency_readout.png |
| Fast mode | same without pre-warm (sockets expired; warm-up starts only when the question is sent) | INFO | median ms: {"firstToken": 351, "firstSentence": 518, "tts": 212, "ttsFirstAudio": 74, "play": 981} / each play: [981, 625] |  |
| Fast mode | fast model chosen for voice | PASS | ['mock:openai/gpt-oss-20b', 'mock:openai/gpt-oss-20b', 'mock:openai/gpt-oss-20b', 'mock:openai/gpt-oss-20b'] |  |
| Mic | SpeechRecognizer service available on this image | PASS | emulator google_apis image |  |
| Broadcast | custom script -> 5 voiced lines stitched + M4A + Short MP4 video | PASS |  lines=5 dur=15.0s audio=bc-mvxl117u6j086.m4a video=bc-mvxl117u6j086-short.mp4 in 18s | 06_broadcast_result.png |
| Broadcast | pulled bc-mvxl117u6j086.m4a is a valid media file (ffprobe) | PASS | 182433 bytes { "programs": [ ], "streams": [ { "codec_name": "aac", "codec_type": "audio" } ], "format": { "duration": "14.933333" } }  |  |
| Broadcast | pulled bc-mvxl117u6j086-short.mp4 is a valid media file (ffprobe) | PASS | 5703915 bytes { "programs": [ ], "streams": [ { "codec_name": "h264", "codec_type": "video", "width": 720, "height": 1280 }, { "codec_name": "aac", "codec_type": "audio" } ], "format": { "duration": "15.000000" } }  |  |
| Broadcast | Save button -> file in /sdcard/Download/PunaAI | PASS | total 5576 -rwxrwx--- 1 u0_a182 media_rw 5703915 2026-11-01 08:43 Puna-Namaste-dosto-Aaj-hum-aloo-paratha-banan-2026-11-01.mp4  |  |
| Broadcast | Share button opens Android share sheet | PASS | topResumedActivity=ActivityRecord{2bfcdd1 u0 com.android.intentresolver/.ChooserActivity t8} | 07_share_sheet.png |
| Memory | add memory in Settings (real typing) shows with timestamp | PASS | Mujhe Odia gaane pasand hain 1 Nov 2026, 8:43 am · manual 🗑 | 08_settings_memory.png |
| Memory | delete memory | PASS |  |  |
| Voice | picker shows 13 voices and selecting Sagarika works | PASS | 13 |  |
| Tabs | Builder shows Coming soon; Agent tab is live | PASS |  |  |
| Agent | status before enabling: accessibility off, guided setup shown | PASS | {"a11y": false, "a11yLive": false, "rootAvailable": false, "rootGranted": false, "rootOn": false, "sdk": 34, "contacts": true, "callPhone": false} |  |
| Agent | 'Accessibility ON karo' button opens Android Accessibility settings | PASS | ActivityRecord{4056535 u0 com.android.settings/.Settings$AccessibilitySettingsActivity t8} | 15_a11y_settings.png |
| Agent | accessibility service enabled via adb settings -> service connected | PASS | {"a11y": true, "a11yLive": true, "rootAvailable": false, "rootGranted": false, "rootOn": false, "sdk": 34, "contacts": true, "callPhone": false} |  |
| Agent tools | open_app 'Settings' -> Settings in front + screen read back | PASS | Settings / front=ActivityRecord{9a33c05 u0 com.android.settings/.Settings t9} /  / Profile picture, double tap to open Google Account / Settings /  / Search settings /  / Network & internet / Mobile, Wi‑Fi, hotspot / Connected devices / Bluetooth, pairing / Apps / Assistant, rece | 17_tool_open_settings.png |
| Agent tools | read_screen returns UI tree (text, clickable, bounds) | PASS |  / Profile picture, double tap to open Google Account / Settings /  / Search settings /  / Network & internet / Mobile, Wi‑Fi, hotspot / Connected devices / Bluetooth, pairing / Apps / Assistant, recent apps, default apps / Notifications / Notification history, conversations |  |
| Agent tools | tap by text 'Network & internet' opens that page | PASS | tapped=Network & internet /  / Network & internet / Navigate up /  / Internet / AndroidWifi / Calls & SMS / T-Mobile / SIMs / T-Mobile / Hotspot & tethering / Off / Data Saver | 18_tool_tap_text.png |
| Agent tools | press back -> Settings home again | PASS |  / Profile picture, double tap to open Google Account / Settings /  / Search settings /  / Network & internet / Mobile, Wi‑Fi, hotspot / Connected devices / Bluetooth, pairing / Apps / Assistant, rece |  |
| Agent tools | scroll down / up | PASS | ok / ok |  |
| Agent tools | tap search + type_text 'display' fills the search box | PASS | ok / Back / display / Clear text /  /  / Display / Display / Display size / Display > Display size and text / Display size and text / Display / Display over other apps / Apps > Special app access | 19_tool_type.png |
| Agent tools | press home | PASS | 3c u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7} |  |
| Agent tools | open notification shade (global action) | PASS | ok |  |
| Agent tools | recents (global action) | PASS | ok google.android.apps.nexuslauncher/.NexusLauncherActivity t7} |  |
| Agent tools | screenshot (accessibility global action) saves a new image | PASS | ok=True files 0 -> 1 |  |
| Agent tools | open_app by name 'clock' (alias -> deskclock) | PASS | Clock oogle.android.deskclock/com.android.deskclock.DeskClock t10} |  |
| Agent tools | set_alarm 06:30 intent accepted by Clock | PASS | 06:30 / front=oogle.android.deskclock/com.android.deskclock.DeskClock t10} / deskclock alarms=11 | 21_tool_alarm.png |
| Agent tools | set_timer 90 s intent accepted by Clock | PASS | 90 oogle.android.deskclock/com.android.deskclock.DeskClock t10} | 22_tool_timer.png |
| Agent tools | flashlight on | NOT TESTABLE | is phone mein flashlight nahi mili |  |
| Agent tools | volume set 40% | PASS | {'ok': True, 'level_percent': 40} |  |
| Agent tools | open Wi-Fi settings (panel) | PASS | wifi cord{4a968d3 u0 com.android.settings/.panel.SettingsPanelActivity t13} | 23_tool_wifi.png |
| Agent tools | open Bluetooth settings | PASS | bluetooth 0 com.android.settings/.Settings$ConnectedDeviceDashboardActivity t14} |  |
| Agent tools | call (no CALL_PHONE permission) -> dialer opens with the number, nothing dialled | PASS | dialer opened (tap call) / m.google.android.dialer/com.android.dialer.main.impl.MainActivity t15} | 24_tool_dialer.png |
| Agent tools | send_sms opens Messages with text prefilled (not sent) | PASS | message box mein text bhar diya; Send dabana baaki / d{4731a65 u0 com.google.android.apps.messaging/.main.MainActivity t16} | 25_tool_sms.png |
| Agent tools | whatsapp_message when WhatsApp is not installed -> clear error | PASS | WhatsApp install nahi hai |  |
| Agent tools | youtube search (no YouTube app on this image -> browser fallback) | PASS | lofi songs / droid.permissioncontroller.permission.ui.GrantPermissionsActivity t17} | 26_tool_youtube.png |
| Agent tools | Maps navigation (no Maps app on this image -> geo/browser fallback) | PASS | Lingaraj Temple Bhubaneswar / com.google.android.apps.maps/com.google.android.maps.MapsActivity t18} |  |
| Agent tools | list_apps | PASS | ['Camera', 'Chrome', 'Settings', 'Drive', 'Maps', 'Messages', 'Photos', 'YT Music', 'Calendar', 'Contacts', 'Clock', 'Phone'] |  |
| Agent loop | mock planner: open Settings -> read -> tap 'Network & internet' -> back -> home -> finish (real actions, live step log) | PASS | {'summary': 'Settings khol kar Network & internet dekha.', 'success': True} / ['✓ App khola: Settings', '✓ Screen padhi', '✓ Tap: Network & internet', '✓ press {"key":"back"}', '✓ press {"key":"home"}'] |  |
| Agent safety | 'Send' tap needs confirmation: Yes/No card shown over Settings, real tap on 'Nahi' -> not executed | FAIL | tapped=None / [] |  |
| Agent safety | call asks first (in-app sheet), real tap 'Haan' -> dialer opens | PASS | sheet=True front=ndroid.dialer/com.android.dialer.main.impl.MainActivity t15} ['🎯 call test', '🛡️ 📞 +911234567890 ko call karun?', '✅ Haan: 📞 +911234567890 ko call karun?', '✓ Call: +911234567890 (dialer opened (tap call))', '✅ Dialer khola.'] |  |
| Agent safety | ⏹ Stop button visible while running; real tap stops the loop | PASS | {'summary': 'Theek hai, ruk gayi.', 'success': False} steps=2 |  |
| Agent safety | floating '⏹ Stop Puna' bubble over other apps (real tap) stops the agent | FAIL | tapped=None None |  |
| Agent loop | assistant command 'Settings kholo' (voice mode) runs the agent and speaks the result | PASS | Settings khol di. | 31_assistant_agent.png |
| Agent root | su detection + grant request on emulator | NOT TESTABLE | rootAvailable=False result={"ok": true, "error": "su nahi chala: Cannot run program \"su\": error=13, Permission denied", "granted": false} |  |
| 24/7 | Settings toggle (real tap) starts foreground service + persistent notification | PASS | {"alwaysOn": true, "wake": false, "running": true, "battery": false, "overlay": true, "notif": true, "wakePending": false, "lastHeard": "", "wakeCycles": 0} / notif has Talk/Stop: True/True | 32_settings_247.png |
| 24/7 | service is foreground (type specialUse) | PASS | isForeground=true foregroundId=7 types=40000000 foregroundNoti=Notification(channel=puna_always shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0x00000000 category |  |
| 24/7 | notification 🎙️ Talk button (real tap) opens Puna and starts listening | FAIL | tapped=None front=cord{699b93c u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7} orb=idle | 34_after_notif_talk.png |
| 24/7 | app shortcut intent (TALK) opens and starts listening | PASS | orb=listening listens=1 |  |
| 24/7 | launcher shortcuts registered (talk, broadcast) [release package] | PASS | Package: com.sonali.punaai.debug  UID: 10192           ShortcutInfo {id=talk, flags=0x1a4 [ImManIc-rStr]           ShortcutInfo {id=broadcast, flags=0x1a4 [ImManIc-rStr] |  |
| 24/7 | Quick Settings tile 'Puna Talk' click opens Puna listening | PASS | front={8865bde u0 com.sonali.punaai.debug/com.sonali.punaai.MainActivity t8} listens=2  | 35_after_tile.png |
| 24/7 | BOOT_COMPLETED restarts the 24/7 service (after force-stop) | PASS | before=False after=True ent { act=android.intent.action.BOOT_COMPLETED flg=0x1400000 pkg=com.sonali.punaai.debug } Broadcast completed: result=0 |  |
| Hey Puna | wake mode: foreground service with microphone type, recognizer loop cycling in background | PASS | cycles=4 fg=isForeground=true foregroundId=7 types=40000080 foregroundNoti=Notification(channel=puna_always shortcut=null contentView=null vibrate=null sound=null defaults= |  |
| Hey Puna | phrase 'hey puna' heard in background -> app opens itself and listens (overlay allowed; simulated phrase, no real mic audio on emulator) | PASS | front=32737b4 u0 com.sonali.punaai.debug/com.sonali.punaai.MainActivity t19} listens=1 | 36_after_wake.png |
| 24/7 | notification ⏹ Stop (real tap) stops the service and turns 24/7 off | FAIL | tapped=None running=True {"alwaysOn": true, "wake": true, "running": true, "battery": false, "overlay": true, "notif": true, "wakePending": false |  |
| Stability | no FATAL EXCEPTION, no JS exceptions | PASS | ([], []) |  |
