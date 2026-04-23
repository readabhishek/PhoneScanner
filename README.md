# PhoneScanner

An Android app that turns your phone into a document scanner. Point the camera at a page, let ML Kit auto-detect the edges and correct perspective, capture multiple pages if you want, then save the result as a PDF into your phone's public `Documents/PhoneScanner` folder.

## How it works

- UI: **Kotlin + Jetpack Compose** (Material 3).
- Scanning: **Google ML Kit Document Scanner** (`play-services-mlkit-document-scanner`). Google Play services hosts the secure camera/scanner UI, handles edge detection, perspective correction, cropping, filters, and multi-page capture, and hands back a ready-made PDF. Because Google's scanner UI is used, the app itself does **not** need the `CAMERA` permission.
- Storage: on Android 10+ the PDF is written via `MediaStore` into `Documents/PhoneScanner/` (no runtime permission needed). On Android 9 and below it is written directly using the legacy `WRITE_EXTERNAL_STORAGE` permission declared with `maxSdkVersion="28"`.

## Project layout

```
PhoneScanner/
├── build.gradle.kts               # Top-level Gradle config
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts           # App-level Gradle config + dependencies
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/phonescanner/
        │   ├── MainActivity.kt    # Compose UI + scanner wiring
        │   ├── PdfStorage.kt      # Saves PDF into Documents/PhoneScanner
        │   └── ui/theme/Theme.kt  # Material 3 theme
        └── res/                   # Strings, colors, launcher icons, etc.
```

## Requirements

- Android Studio Hedgehog (2023.1) or newer (Koala is ideal).
- Android Gradle Plugin 8.5.x, Gradle 8.7, Kotlin 1.9.24 (all pinned in the build files).
- A physical Android device running **Android 7.0 (API 24)** or higher, with Google Play services installed. The ML Kit scanner does not run on emulators without Play services.

## Build & run

1. Open the `PhoneScanner/` folder in Android Studio (`File → Open…`).
2. Let Gradle sync. Accept any SDK/dependency downloads it requests.
3. If you want a Gradle wrapper `.jar` / `gradlew` script, generate it from inside Android Studio via `File → New → Wrapper` or by running `gradle wrapper` from a shell — the repo ships with `gradle-wrapper.properties` but not the binary jar.
4. Plug in a physical Android phone with USB debugging enabled, or start a Play-enabled emulator.
5. Select the `app` run configuration and press **Run**.

## Using the app

1. Tap **Scan Document** on the home screen.
2. Google's scanner UI opens. Frame the document — it will highlight detected edges automatically. Tap the capture button.
3. Adjust corners, apply filters, or tap **+** to add more pages.
4. Tap **Save**. The app writes `Scan_<yyyyMMdd_HHmmss>.pdf` into `Documents/PhoneScanner/` and lists it under **Recent scans**.
5. Tap **Open** on a recent scan to view it in any installed PDF viewer.

## Permissions

No runtime permissions are requested on Android 10+. The legacy `WRITE_EXTERNAL_STORAGE` permission is declared only for API ≤ 28. The ML Kit Document Scanner handles the camera itself via Play services, so no `CAMERA` permission is needed.

## Notes

- First launch may take a few seconds the first time as Play services downloads the scanner module.
- Max page count per scan is set to 25 in `MainActivity.kt` — change `setPageLimit(...)` on `GmsDocumentScannerOptions.Builder` to adjust.
- To also keep the JPEG pages alongside the PDF, extend `PdfStorage.kt` to iterate `scanResult.pages`.
