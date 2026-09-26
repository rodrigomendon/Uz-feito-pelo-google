# UltraZoom 12.4.0 — build

1. Put `UltraZoom-00-v12.4.0-AUTODIAGNOSTIC.zip` in the repository root.
2. GitHub Actions unpacks the archive as an isolated Android project.
3. It runs unit tests (`gradle clean test`), validates static regression checks, and runs `gradle assembleDebug`.
4. The debug artifact is published as `UltraZoom-v12.4-debug-apk`.
