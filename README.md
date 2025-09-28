# MakeAppointment (Android)

Build an Android app that scans a document, lets the user draw a box around text, extracts the text, and creates an appointment saved to an .ics file.

Features
- Camera preview and capture (CameraX)
- Box selection overlay to target a region
- OCR (ML Kit Text Recognition) – stubbed in code; connect capture pipeline to OCR
- Generate iCalendar (.ics) file and export via Storage Access Framework

Project structure
- Root Gradle with Android and Kotlin plugins
- app/ module with minimal Activity, layout, and utilities

Getting started
1) Open this folder in Android Studio (Giraffe+ recommended).
2) Let Gradle sync download dependencies.
3) Run the "app" configuration on a device.

Permissions
- CAMERA is requested at runtime for preview and capture.

Next steps
- Wire ImageCapture result into OcrProcessor and crop to the selected rectangle.
- Parse recognized text into fields (summary, start, end, location).
- Improve UI/UX for box selection and confirmation.
- Add tests and error handling.
# makeAppointment
