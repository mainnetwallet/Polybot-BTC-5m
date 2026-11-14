# Polybot Android app

Native Android client (Kotlin, Jetpack Compose, Material 3) for the Polybot-BTC-5m backend.
It has its own UI (no WebView) and talks to the backend REST API and WebSocket.

## Build checklist

- [x] Phase 1: project scaffold, theme, Gradle wrapper, shared debug keystore, secure token store
- [ ] Phase 2: connection layer (PC URL / `gist:<id>` resolution, REST, WebSocket, reconnect)
- [ ] Phase 3: screens (Overview, Trading, Strategy, Analytics, Logs, Settings)
- [ ] Phase 4: unit tests
- [ ] Phase 5: tunnel + Gist publisher (`scripts/start.ps1`, `scripts/stop.ps1`) and docs
- [ ] Phase 6: GitHub Actions workflow `.github/workflows/android-apk.yml`
