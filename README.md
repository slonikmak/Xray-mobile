# Xray Android MVP

This first slice imports one HTTPS subscription and lets you select a profile. It does not start a VPN. Android is the first target; the domain, importer and Compose UI live in `commonMain` for future platforms.

## Build and run

Install JDK 17 and Android SDK Platform 36 and Build Tools 36.0.0. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the Android SDK, then run:

```sh
./gradlew :app:desktopTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.xray.mobile/.MainActivity
```

The wrapper pins Gradle 8.13 and verifies its distribution checksum. The desktop target runs the shared behavior tests; it is not a separate desktop application. `:app:assembleDebug` builds the Android app. Android Studio can also open the project using JDK 17.

Import accepts a Base64 subscription containing VLESS XHTTP/TLS or TCP/REALITY with `xtls-rprx-vision`. Non-empty XHTTP `extra` objects and unfamiliar fingerprint values are not supported yet and are skipped rather than silently changed. It caps the HTTP response at 1 MiB and the full request at 30 seconds; individual connection and socket timeouts are shorter. Unsupported or malformed entries are skipped, and a response with no usable profiles is rejected. HTTPS certificate verification stays enabled, and redirects are checked before following them. The app stores the subscription and selected profile in Android's private, no-backup storage. Do not commit real subscription URLs or profiles.

The interface uses Inter (OFL) and line icons based on Lucide (ISC); license texts are in `docs/licenses/`.
