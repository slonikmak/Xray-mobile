# Start on Android with Kotlin Multiplatform

Use Kotlin Multiplatform for the client, with Android as the first implementation and iOS deferred. The user selected Android first so the initial real VPN can be exercised in an Android emulator, while retaining shared application logic for iOS; Flutter was discussed but did not replace that choice. Each platform still needs its own system VPN integration, and successful Android tests do not establish iOS compatibility.
