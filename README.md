# Xray Android MVP

The Android app imports one HTTPS subscription, lets you select a profile and runs an Android VPN through Xray. Android is the first target; the domain, importer and Compose UI live in `commonMain` for future platforms.

## Build and run

Install JetBrains Runtime JDK 21 and Android SDK Platform 37 and Build Tools 36.0.0. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the Android SDK, then run:

```sh
./gradlew build
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.xray.mobile/.MainActivity
```

The wrapper pins Gradle 9.6.0 and verifies its distribution checksum. The daemon JVM criteria select JetBrains JDK 21 for builds in Android Studio and the terminal; Gradle can download it if necessary. The shared module runs the behavior tests on both the desktop JVM and Android host; the desktop target is not a separate desktop application. `:app:assembleDebug` builds the Android app.

Import accepts a Base64 subscription containing VLESS XHTTP/TLS or TCP/REALITY with `xtls-rprx-vision`. XHTTP `extra.xmux` parameters are supported; other `extra` fields and unfamiliar fingerprint values are skipped rather than silently changed. It caps the HTTP response at 1 MiB and the full request at 30 seconds; individual connection and socket timeouts are shorter. Unsupported or malformed entries are skipped, and a response with no usable profiles is rejected. HTTPS certificate verification stays enabled, and redirects are checked before following them. The app stores the subscription and selected profile in Android's private, no-backup storage. Do not commit real subscription URLs or profiles. See [Android VPN integration](docs/vpn-android.md) for its routing policy and validation steps.

The interface uses Inter (OFL) and line icons based on Lucide (ISC); license texts are in `docs/licenses/`.
