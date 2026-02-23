# DataPros Analytics – Android APK Setup Guide

## Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Android Studio | Hedgehog 2023.1.1+ | https://developer.android.com/studio |
| JDK | 17 (bundled with Android Studio) | — |
| Python | 3.8 exactly | https://www.python.org/downloads/release/python-3810/ |
| Android SDK | API 34 | Via Android Studio SDK Manager |

> **Important:** Chaquopy requires CPython 3.8. Install it separately even if you have 3.x already.

---

## Step 1 – Install Python 3.8

1. Download Python 3.8 from https://www.python.org/downloads/release/python-3810/
2. Run installer – check **"Add Python to PATH"**
3. Verify: open cmd → `py -3.8 --version` → should print `Python 3.8.x`

---

## Step 2 – Open the project in Android Studio

1. Open Android Studio → **File → Open** → select `C:\DataPros`
2. Wait for Gradle sync to finish (first sync downloads Chaquopy + Python packages – can take 10-15 min)
3. If prompted about JDK, use the bundled JDK 17

---

## Step 3 – Configure local.properties

Edit `local.properties` (already in the project root):
```
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```
Replace `YOUR_NAME` with your Windows username.

---

## Step 4 – Tell Chaquopy where Python 3.8 is

In `app/build.gradle`, find:
```gradle
buildPython "python3"
```
Change to the exact path of your Python 3.8 executable, e.g.:
```gradle
buildPython "C:/Users/USER/AppData/Local/Programs/Python/Python38/python.exe"
```

---

## Step 5 – Build the APK

### Debug APK (for testing):
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (for distribution):
1. **Build → Generate Signed Bundle / APK → APK**
2. Create a keystore (first time) or use existing
3. Select release build type
4. Output: `app/build/outputs/apk/release/app-release.apk`

### Universal APK (works on all devices):
The build is configured to produce a universal APK automatically alongside the ABI splits.
Look for `app-universal-debug.apk` in the outputs folder.

---

## Step 6 – Install on Android device

Option A – USB:
```
adb install app/build/outputs/apk/debug/app-debug.apk
```

Option B – direct transfer:
Copy the APK to the phone via USB/WhatsApp/email → tap to install
(ensure "Install from unknown sources" is enabled in phone settings)

---

## App Modules & Required File Columns

### Arrears Dashboard
Input: CSV/Excel  
Required columns: `FullNames`, `PhoneNumber`, `Arrears Amount`, `DaysInArrears`, `LoanBalance`, `SalesRep`

### Loan Dues Report
Input: CSV/Excel  
Key columns: `LoanId`, `ClientName`, `Branch`, `SalesRep`, `AmountDue`, `Arrears`  
(column names are auto-detected from common variants)

### Arrears Collected (Collections)
Two files required:  
- **SOD file**: `LoanId`, `SalesRep`, `CustomerName`, `ArrearsAmount`, `DaysInArrears`  
- **Current file**: `LoanId`, `ArrearsAmount`

### Dormant Arrangements
Input: CSV/Excel  
Key columns: `Branch`, `ClientName`, `LoanId`, `LoanBalance`, `DaysDormant`, `SalesRep`

### Branch Comparison (MTD)
Three files required: **Income**, **CR**, **Disbursement**  
Each needs: `Branch` column + one amount/value column

### MTD Unpaid Dues
Input: CSV/Excel  
Key columns: `Branch`, `SalesRep`, `LoanId`, `AmountDue`, `AmountPaid`

### RO Performance
Input: CSV/Excel  
Key columns: `SalesRep`/`RO`, `Branch`, `LoanBalance`, `Arrears`, `DaysInArrears`, `DisbursedAmount`

---

## Output Files

All generated Excel reports are saved to:
```
Android/data/com.datapros.app/files/reports/
```
Use the **Export** button in the app to open with WPS/Microsoft Excel,  
or **Share** to send via WhatsApp/email.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Gradle sync fails on Chaquopy | Ensure Python 3.8 path in `build.gradle` is correct |
| `No module named pandas` | Chaquopy pip install failed; check internet during first build |
| APK installs but crashes on launch | Check `logcat` in Android Studio for PyException |
| File picker shows nothing | Grant storage permission in phone Settings → Apps → DataPros |
| Chart blank | File may lack the expected columns; check column names in your CSV |

---

## Project Structure

```
DataPros/
├── app/
│   ├── build.gradle                  ← Chaquopy config & dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── python/
│   │   │   ├── main.py               ← Python ↔ Kotlin bridge
│   │   │   └── processors/
│   │   │       ├── arrears_processor.py
│   │   │       ├── dues_processor.py
│   │   │       ├── collections_processor.py
│   │   │       ├── dormant_processor.py
│   │   │       ├── branch_comparison.py
│   │   │       ├── mtd_unpaid_dues.py
│   │   │       └── ro_performance.py
│   │   ├── java/com/datapros/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── utils/
│   │   │   │   ├── PythonRunner.kt   ← Chaquopy wrapper
│   │   │   │   └── FileUtils.kt      ← File pick / share helpers
│   │   │   └── modules/
│   │   │       ├── BaseModuleFragment.kt
│   │   │       ├── ArrearsFragment.kt
│   │   │       ├── DuesFragment.kt
│   │   │       ├── CollectionsFragment.kt
│   │   │       ├── DormantFragment.kt
│   │   │       ├── BranchComparisonFragment.kt
│   │   │       ├── MtdFragment.kt
│   │   │       └── RoPerformanceFragment.kt
│   │   └── res/
│   │       ├── layout/
│   │       ├── menu/
│   │       ├── navigation/
│   │       ├── values/
│   │       └── xml/
├── build.gradle
├── settings.gradle
└── gradle.properties
```
