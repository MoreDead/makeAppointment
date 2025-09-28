# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

Project overview
- Android app (single module: app/) that scans a document, lets the user draw a selection box, runs OCR on the selected region, and generates an iCalendar (.ics) file to export.
- Tooling: Gradle (Kotlin DSL), Android Gradle Plugin 8.5.2, Kotlin 2.0.20, compileSdk 34, minSdk 24, Java 17.
- Key libraries: CameraX (preview/capture), ML Kit Text Recognition (on-device), Material Components.

Common commands
Note: Use ./gradlew if the Gradle wrapper exists locally; otherwise use gradle.
- Build debug APK:
  - ./gradlew :app:assembleDebug
- Install debug to a connected device/emulator:
  - ./gradlew :app:installDebug
- Run unit tests (JVM):
  - ./gradlew :app:testDebugUnitTest
- Run instrumentation tests (on device/emulator):
  - ./gradlew :app:connectedDebugAndroidTest
- Lint checks:
  - ./gradlew :app:lint
  - Output: app/build/reports/lint/ (HTML/XML)
- Kotlin style checks (ktlint):
  - ./gradlew :app:ktlintCheck
  - Auto-format: ./gradlew :app:ktlintFormat
- Clean build outputs:
  - ./gradlew clean
- Run a single unit test by class/method:
  - ./gradlew :app:testDebugUnitTest --tests com.example.docscanics.IcsWriterTest
  - ./gradlew :app:testDebugUnitTest --tests com.example.docscanics.IcsWriterTest.buildsBasicEvent
- Discover tasks:
  - ./gradlew :app:tasks --all

Architecture and code structure (big picture)
- Module: app/
  - Entry point: MainActivity
    - Responsibilities:
      - Requests CAMERA permission and starts CameraX preview (Preview + ImageCapture) bound to lifecycle.
      - Hosts a custom SelectionOverlayView layered over the camera preview to capture a drag-defined RectF.
      - captureAndRecognize() captures a frame (ImageCapture.takePicture). Current implementation stubs OCR and demonstrates building an ICS event; the integration to run ML Kit and crop to the selection is a planned step.
      - Builds .ics content from recognized/parsed text using IcsWriter and exports via Storage Access Framework (ActivityResultContracts.CreateDocument).
  - SelectionOverlayView (custom View)
    - Tracks pointer down/move/up to produce a rectangular selection; renders semi-transparent fill and border; exposes getSelection() to return RectF of current box.
  - OcrProcessor (utility)
    - Wraps ML Kit TextRecognition client; exposes a suspend recognize(imageProxy) that converts ImageProxy → InputImage and returns Text.
    - Intended to be called on capture result (crop to selected region before recognition in future wiring).
  - IcsWriter (utility)
    - Produces an RFC 5545-compatible VCALENDAR with a single VEVENT; ensures CRLF line endings; escapes special characters as needed.

Important notes from README
- Features: Camera preview/capture, box selection overlay, ML Kit OCR (stubbed), generate and export .ics.
- Getting started: Open in Android Studio (Giraffe+), let Gradle sync, run the "app" configuration on a device.
- Next steps (roadmap): Wire ImageCapture to OcrProcessor with cropping to the selected rectangle; parse recognized text into event fields (summary/start/end/location); refine selection UX; add tests and error handling.

Environment prerequisites
- Android SDK (compileSdk 34), a connected emulator or device for install/instrumentation tests.
- JDK 17 (project config sets Java/Kotlin to 17).

Outputs and paths
- Debug APK: app/build/outputs/apk/debug/app-debug.apk
- Lint reports: app/build/reports/lint/
- Test reports: app/build/reports/tests/ (unit tests), app/build/reports/androidTests/ (instrumentation)
