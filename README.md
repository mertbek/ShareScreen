# ShareScreen

Watch and control screens across Android, desktop and the browser. Kotlin Multiplatform with
Compose Multiplatform; picture, sound and input travel peer to peer over WebRTC.

See [docs/architecture.md](docs/architecture.md) for the design.

## Build

Requires JDK 17 or newer and, for the Android app, the Android SDK (`local.properties` with `sdk.dir`).

```
./gradlew :desktopApp:run
./gradlew :webApp:wasmJsBrowserDevelopmentRun
./gradlew :androidApp:assembleDebug
./gradlew :shared:desktopTest :server:test
```
