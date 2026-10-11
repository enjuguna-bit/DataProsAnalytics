# DataPros Analytics

DataPros Analytics is an Android app for loan and collections reporting. It combines a Kotlin UI with Python data processors (via Chaquopy) to turn CSV/Excel inputs into structured analytics and exportable reports.

## Features

- Import CSV/Excel files from device storage
- Run analytics modules for:
  - Arrears
  - Loan dues
  - Collections (SOD vs current)
  - Dormant arrangements
  - Branch comparison
  - MTD analytics (CR portfolio, income analysis, pivot, unpaid dues)
  - RO performance
  - SOD CR portfolio
- Generate report outputs and share/export from the app
- Optional OCR-assisted ingestion for image based table workflows

## Tech Stack

- Android (Kotlin)
- Android SDK 34, min SDK 26
- Chaquopy Python runtime (Python 3.11 in current Gradle config)
- Libraries:
  - AndroidX, Material Components
  - Navigation component
  - Coroutines
  - MPAndroidChart
  - Google ML Kit text recognition
  - OkHttp
  - pandas, numpy, openpyxl, xlsxwriter, matplotlib, Pillow

## Project Layout

```text
DataPros/
|-- app/
|   |-- src/main/java/com/datapros/app/        # Kotlin app code
|   |-- src/main/python/                       # Python processors
|   |-- src/main/res/                          # Android resources
|   `-- build.gradle                           # App module config
|-- build.gradle                               # Root Gradle config
|-- settings.gradle
`-- SETUP.md                                   # Detailed setup guide
```

## Getting Started

1. Open the project in Android Studio.
2. Ensure `local.properties` points to your Android SDK path.
3. Optionally set `DATAPROS_BUILD_PYTHON` in `local.properties` (or environment) to your Python 3.11 executable path.
4. Sync Gradle and wait for first time dependency downloads.
5. Build from Android Studio (`Build APK`) or your preferred Gradle workflow.

For detailed machine setup and troubleshooting, see `SETUP.md`.

## Release Signing (Secure Setup)

Release signing values must not be committed to source control.

1. Add signing values to your local `local.properties` (or pass as environment/project properties in CI):

```properties
MYAPP_UPLOAD_STORE_FILE=C\:\\path\\to\\your-release-keystore.jks
MYAPP_UPLOAD_KEY_ALIAS=your_key_alias
MYAPP_UPLOAD_STORE_PASSWORD=your_store_password
MYAPP_UPLOAD_KEY_PASSWORD=your_key_password
```

2. Build release artifacts:

- Android Studio: **Build > Generate Signed Bundle / APK**
- Gradle: `:app:assembleRelease`

If these properties are not set, release signing is skipped by design in `app/build.gradle`.

## Notes

- Keep generated folders out of source control (`.gradle/`, `build/`, `app/build/`).
- If you change Python package versions, re-sync and rebuild to refresh Chaquopy assets.
