# UltraZoom 12.4.0 — Auto Diagnostic

Build identifier: `UZ-124-AUTODIAGNOSTIC`.

This package contains the 12.4.0 Auto Diagnostic source, `DiagnosticSessionStore`, and unit tests.

The version separates zoom diagnosis into three layers:

- **A — Declarado:** Camera2 `CaptureResult`, zoom ratio and crop geometry.
- **B — Observado:** comparison of preview frames captured at consecutive zoom levels when sufficient overlap/features exist, persisted per level and per pair (`z1 -> z2`).
- **C — Inferido:** detail and acutance evidence compared across JPEGs the user voluntarily captures; no hidden still capture is performed and crop+upscale is rejected.

`UNKNOWN` means evidence is not available. It is not a failure. `FAIL` is contradictory evidence and is absorbing: any FAIL revokes the corresponding confirmation and requires recheck.

## Confidence rule

- Any `FAIL` → `REVOKED`.
- Otherwise, missing evidence (`UNKNOWN`) is not failure and never revokes a prior `CONFIRMED`.
- `CONFIRMED + CONFIRMED + UNKNOWN` → `PARTIAL`.
- All three confirmed → `CONFIRMED`.
