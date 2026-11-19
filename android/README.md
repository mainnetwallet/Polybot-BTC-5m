# Polybot Android app

Native Android client (Kotlin, Jetpack Compose, Material 3) for the Polybot-BTC-5m backend.
It has its own UI (no WebView) and talks to the backend REST API and WebSocket. It does not change any
trading logic or backend behaviour.

## Build checklist

- [x] Phase 1: project scaffold, theme, Gradle wrapper, shared debug keystore, secure token store
- [x] Phase 2: connection layer (PC URL / `gist:<id>` resolution, REST, WebSocket, reconnect)
- [x] Phase 3: screens (Overview, Trading, Strategy, Analytics, Logs, Settings)
- [x] Phase 4: unit tests
- [x] Phase 5: tunnel + Gist publisher (`scripts/start.ps1`, `scripts/stop.ps1`) and docs
- [x] Phase 6: GitHub Actions workflow `.github/workflows/android-apk.yml`
- [x] Phase 7: compileSdk/targetSdk 37 (Android 17), AGP 9.4 + Gradle 9.6, local network permission for LAN addresses

## Set up end to end

1. **PC side.** In the repo `.env` set a long random `API_TOKEN`, keep `HOST=127.0.0.1`, and for access away from
   home add `QUICK_TUNNEL=1` plus `GIST_TOKEN=<classic token with ONLY the gist scope>`. Run `start.bat`; it prints
   `In the app enter: gist:<id>`. Details: "Android app and remote access" in the root [README](../README.md).
2. **Install the app.** Download the `polybot-apk` artifact of the latest green run of the *Android APK*
   workflow (Actions tab), unzip it and install `polybot-<version>-debug.apk` on the phone (the minified
   `-release.apk` is in artifact `polybot-apk-release`).
   Allow "install unknown apps" for the app you open it with. Builds are signed with the same key, so a newer
   build installs over an older one.
3. **Connect.** In the app enter the PC URL (`gist:<id>`, a `gist.github.com` URL, `https://...` or a LAN
   `http://192.168.x.x:8000`) and the API token, tap *Test connection* (it shows Gist / tunnel / token step by
   step), then *Save*. *Log out* (Settings, under *Safety and account*) clears the stored token.
   Settings opens as a hub: the PC connection, one card per stage (0 to 3, each with its own on/off switch and a
   page holding that stage's timing, entry rules, take-profit and stop-loss), Risk and Paper pages, app preferences
   (theme, haptics) and the danger zone. Edits stay in place while you move between pages and are sent together
   when you tap *Save*.

The PC URL field accepts:

| Input | Meaning |
| --- | --- |
| `gist:<id>` or `https://gist.github.com/<user>/<id>` | Read the current tunnel URL from file `pc-url.txt` of that Gist |
| `https://host` | Used as is |
| `http://192.168.1.20:8000` | LAN use; a banner warns when plain `http` is used outside private ranges |
| `203.0.113.10` (a public IP only, no port) | VPS shortcut: becomes `http://203.0.113.10:5173`, the dashboard port that `start.sh` opens (open it in the firewall). Plain http, so use a long `API_TOKEN`; the Gist/tunnel is encrypted |
| `host` | `https://` is added (`http://` for private IPs) |

The Gist is only re-read when a request or the WebSocket fails (at most once per 15 s, GitHub allows 60
unauthenticated calls per hour per IP); the failed call is then retried once. After a PC restart the tunnel URL
changes and the app recovers by itself.

## Build locally

Requires JDK 17 and the Android SDK (platform 37 = Android 17, build-tools 36.0.0). Toolchain: AGP 9.4, Gradle 9.6 (wrapper), Kotlin 2.2 with AGP built-in Kotlin.

```
cd android
./gradlew assembleDebug                                   # quick: just the installable APK
./gradlew lintDebug testDebugUnitTest assembleRelease     # full checks + minified release APK
```

APKs end up in `app/build/outputs/apk/{debug,release}/`. Optional properties: `-PversionCode=N -PversionName=X.Y.Z`.
The unit tests are plain JUnit (no emulator): URL normalisation, Gist parsing, retry-on-failure with a fake HTTP
layer, JSON fixtures from real backend output, tick merge and settings validation.

## GitHub Actions: `.github/workflows/android-apk.yml`

| Trigger | What happens |
| --- | --- |
| push to `main` touching `android/**` or the workflow | fast debug APK, plus lint, unit tests and release APK in parallel |
| pull request touching `android/**` | same |
| manual (`workflow_dispatch`) | same |
| tag `app-v*` (for example `app-v1.0.0`) | same, then a GitHub Release with the APKs attached |

* Version code = `github.run_number`; version name = tag without `app-v`, otherwise `0.1.<run_number>`.
* Two jobs run in parallel. **Build APK (fast)** builds only the debug APK (no lint, tests or R8) and uploads it as
  artifact `polybot-apk` (`polybot-<version>-debug.apk`); with a warm Gradle cache it is ready in about a minute.
  **Lint, tests and release APK** runs `lintDebug testDebugUnitTest assembleRelease` and uploads
  `polybot-apk-release` (`polybot-<version>-release.apk`) plus the reports in `android-reports`. Install the debug APK
  for day-to-day use; both are signed with the same shared key unless the release secrets are set.
* The Gradle cache is written by runs on `main` and read by pull requests, so the very first run after a cache
  reset (or after a Gradle/AGP version change) is slower.
* To publish a release: `git tag app-v1.0.0 && git push origin app-v1.0.0`.

### Signing

* Debug and CI builds are signed with the committed, **non-secret** `android/keystore/debug.keystore`, so every
  build has the same signature and updates install over each other.
* Optional release key: add the repository secrets `ANDROID_KEYSTORE_BASE64` (the keystore file, base64),
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The workflow then decodes the
  keystore to a temporary file and signs the release APK with it (secrets are never echoed). Note that Android
  refuses to update an app signed with a different key, so uninstall the old build once when switching keys.
* Never commit a real keystore or its passwords (`*.jks` and everything in `android/keystore/` except the debug
  key are git-ignored).

## Security notes

* The API token is stored encrypted with an Android Keystore AES-GCM key; preferences use DataStore.
* On Android 17+ (targetSdk 37) a LAN address such as `http://192.168.x.x:8000` needs the `ACCESS_LOCAL_NETWORK`
  runtime permission ("Nearby devices"); the app asks for it when you save or test such an address. A Gist link or
  https tunnel needs nothing.
* The network security config allows cleartext only because LAN use needs it; plain `http` outside private
  ranges shows a warning banner.
* LIVE mode is always marked in red; starting the bot or switching to Live needs the typed confirmation phrase
  (default `GO LIVE`), manual sells ask for confirmation unless you turn that off in Settings (default off, applies to paper and live), and every destructive action asks first.

## Limitations

* Foreground only: the WebSocket stops when the app goes to the background and resumes on return. There are no
  push notifications.
* Cloudflare quick tunnels have no uptime guarantee and a new URL on every start; a Named Tunnel with a custom
  domain is an optional upgrade (the app already accepts a plain `https://` URL).
* The app mirrors the backend contract of this repository version; endpoints are not versioned, so a backend
  change may need an app update (unknown JSON fields are ignored, missing ones fall back to defaults).
