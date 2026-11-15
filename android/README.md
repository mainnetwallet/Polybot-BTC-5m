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
   workflow (Actions tab), unzip it and install `polybot-<version>-release.apk` (or `-debug.apk`) on the phone.
   Allow "install unknown apps" for the app you open it with. Builds are signed with the same key, so a newer
   build installs over an older one.
3. **Connect.** In the app enter the PC URL (`gist:<id>`, a `gist.github.com` URL, `https://...` or a LAN
   `http://192.168.x.x:8000`) and the API token, tap *Test connection* (it shows Gist / tunnel / token step by
   step), then *Save*. *Log out* in Settings clears the stored token.

The PC URL field accepts:

| Input | Meaning |
| --- | --- |
| `gist:<id>` or `https://gist.github.com/<user>/<id>` | Read the current tunnel URL from file `pc-url.txt` of that Gist |
| `https://host` | Used as is |
| `http://192.168.1.20:8000` | LAN use; a banner warns when plain `http` is used outside private ranges |
| `host` | `https://` is added (`http://` for private IPs) |

The Gist is only re-read when a request or the WebSocket fails (at most once per 15 s, GitHub allows 60
unauthenticated calls per hour per IP); the failed call is then retried once. After a PC restart the tunnel URL
changes and the app recovers by itself.

## Build locally

Requires JDK 17 and the Android SDK (platform 37 = Android 17, build-tools 36.0.0). Toolchain: AGP 9.4, Gradle 9.6 (wrapper), Kotlin 2.2 with AGP built-in Kotlin.

```
cd android
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
```

APKs end up in `app/build/outputs/apk/{debug,release}/`. Optional properties: `-PversionCode=N -PversionName=X.Y.Z`.
The unit tests are plain JUnit (no emulator): URL normalisation, Gist parsing, retry-on-failure with a fake HTTP
layer, JSON fixtures from real backend output, tick merge and settings validation.

## GitHub Actions: `.github/workflows/android-apk.yml`

| Trigger | What happens |
| --- | --- |
| push to `main` touching `android/**` or the workflow | lint, unit tests, debug + release APK, upload artifact |
| pull request touching `android/**` | same |
| manual (`workflow_dispatch`) | same |
| tag `app-v*` (for example `app-v1.0.0`) | same, then a GitHub Release with the APKs attached |

* Version code = `github.run_number`; version name = tag without `app-v`, otherwise `0.1.<run_number>`.
* Artifact `polybot-apk` (30 days) holds `polybot-<version>-debug.apk` and `-release.apk`; the lint and test
  reports are in `android-reports`.
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
  (default `GO LIVE`), manual sells in Live are confirmed, and every destructive action asks first.

## Limitations

* Foreground only: the WebSocket stops when the app goes to the background and resumes on return. There are no
  push notifications.
* Cloudflare quick tunnels have no uptime guarantee and a new URL on every start; a Named Tunnel with a custom
  domain is an optional upgrade (the app already accepts a plain `https://` URL).
* The app mirrors the backend contract of this repository version; endpoints are not versioned, so a backend
  change may need an app update (unknown JSON fields are ignored, missing ones fall back to defaults).
