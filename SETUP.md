# DataPros Analytics – Android APK Setup Guide

## Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Android Studio | Hedgehog 2023.1.1+ | https://developer.android.com/studio |
| JDK | 17 (bundled with Android Studio) | — |
| Python | 3.11.x | https://www.python.org/downloads/release/python-3119/ |
| Android SDK | API 34 | Via Android Studio SDK Manager |

> **Important:** This project is currently configured for CPython 3.11 in `app/build.gradle`.

---

## Step 1 – Install Python 3.11

1. Download Python 3.11 from https://www.python.org/downloads/release/python-3119/
2. Run installer – check **"Add Python to PATH"**
3. Verify: open cmd → `py -3.11 --version` → should print `Python 3.11.x`

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

## Step 4 – Tell Chaquopy where Python 3.11 is

Add this to `local.properties` (recommended) or set it as an environment variable:
```properties
DATAPROS_BUILD_PYTHON=C\:\\Users\\USER\\AppData\\Local\\Programs\\Python\\Python311\\python.exe
```

If not set, Gradle will fall back to `python` on your PATH.

---

## Step 5 – Configure release signing securely

Do **not** store signing secrets in source-controlled files.

Add these values to your local `local.properties` (or inject them in CI as project/environment properties):

```properties
MYAPP_UPLOAD_STORE_FILE=C\:\\path\\to\\your-release-keystore.jks
MYAPP_UPLOAD_KEY_ALIAS=your_key_alias
MYAPP_UPLOAD_STORE_PASSWORD=your_store_password
MYAPP_UPLOAD_KEY_PASSWORD=your_key_password
```

If these values are missing, release signing is skipped by design in `app/build.gradle`.

---

## Step 6 – Build the APK

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

## Step 7 – Install on Android device

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
| Gradle sync fails on Chaquopy | Ensure Python 3.11 path in `build.gradle` is correct |
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
