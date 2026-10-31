| # | Area | Function | Result | Evidence | Details |
|---|---|---|---|---|---|
| 1 | Install | Install currently published APK (previous-latest.apk) | PASS |  | Performing Streamed Install Success |
| 2 | Install | Update new APK over the published one without uninstalling | PASS |  | Performing Streamed Install Success |
| 3 | App | Launch: app alive 25 s after start, no crash | PASS | 01_launch_25s.png | pid=8556 top=mResumedActivity: ActivityRecord{140faa u0 com.sonali.bharatvoicestudio/.MainActivity t11}  |
| 4 | App | App still running at the end of the test | PASS | 02_final.png | mResumedActivity: ActivityRecord{140faa u0 com.sonali.bharatvoicestudio/.MainActivity t11} |
