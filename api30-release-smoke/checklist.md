| # | Area | Function | Result | Evidence | Details |
|---|---|---|---|---|---|
| 1 | Install | Fresh install on Android 11 / API 30 | PASS |  | Performing Streamed Install Success |
| 2 | App | Launch: app alive 25 s after start, no crash | PASS | 01_launch_25s.png | pid=8839 top=mResumedActivity: ActivityRecord{52b1dc8 u0 com.sonali.bharatvoicestudio/.MainActivity t12}  |
| 3 | App | App still running at the end of the test | PASS | 02_final.png | mResumedActivity: ActivityRecord{52b1dc8 u0 com.sonali.bharatvoicestudio/.MainActivity t12} |
