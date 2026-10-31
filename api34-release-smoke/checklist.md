| # | Area | Function | Result | Evidence | Details |
|---|---|---|---|---|---|
| 1 | Install | Fresh install on Android 14 / API 34 | PASS |  | Performing Streamed Install Success |
| 2 | App | Launch: app alive 25 s after start, no crash | PASS | 01_launch_25s.png | pid=8497 top=topResumedActivity=ActivityRecord{7ebc42a u0 com.sonali.bharatvoicestudio/.MainActivity t12}  |
| 3 | App | App still running at the end of the test | PASS | 02_final.png | topResumedActivity=ActivityRecord{7ebc42a u0 com.sonali.bharatvoicestudio/.MainActivity t12} |
