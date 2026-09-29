# ShareScreen

Watch and control screens across Android, desktop and the browser. Kotlin Multiplatform with
Compose Multiplatform; picture, sound and input travel peer to peer over WebRTC.

See [docs/architecture.md](docs/architecture.md) for the design.

## Build

Requires JDK 17 or newer and, for the Android app, the Android SDK (`local.properties` with `sdk.dir`).

```
./gradlew :desktopApp:run
./gradlew :webApp:wasmJsBrowserDevelopmentRun
./gradlew :androidApp:assembleFullDebug
./gradlew :signaling:allTests :core:allTests :server:test :lan:test :shared:desktopTest :desktopApp:jvmTest
```

The web build for hosting comes from `./gradlew :webApp:wasmJsBrowserDistribution`.

## Internet server

The apps do not ship a server address. Give it at build time as `sharescreen.server`, for
example `wss://signal.example.com`, in one of these places:

- a Gradle property: `-Psharescreen.server=wss://signal.example.com`
- the environment variable `SHARESCREEN_SERVER`
- a line in `local.properties`

Without it, internet sharing stays off until a server is entered in Settings.

## Android editions

- `full` can be controlled from another device through an accessibility service.
- `lite` leaves the service out. It shares, watches and controls other devices, and installs
  from a download link on phones where Play Protect blocks accessibility apps.

Release builds are signed with the key named in `~/.sharescreen/keystore.properties` (or the
file in `SHARESCREEN_KEYSTORE_PROPERTIES`), and fall back to the debug key without it.
`-Pabis=arm64-v8a` limits the native libraries for a smaller APK, and `-PappIdSuffix=.test`
appends a suffix to the application id so a build installs next to another one.

## Platforms

| | Share | Watch | Be controlled |
|---|---|---|---|
| Android | yes | yes | yes (`full`) |
| Desktop | yes | yes | yes |
| Web | yes, over the internet | yes, over the internet | no |

iOS needs macOS and Xcode and is not part of this repository yet.

## License

MIT, see [LICENSE](LICENSE).
