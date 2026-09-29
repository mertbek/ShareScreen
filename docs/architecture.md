# Architecture

## Goals

Watch and control another device's screen from Android, desktop and the browser, on the same
network or over the internet. Picture, sound and input go peer to peer over WebRTC; a small
signaling service only introduces the devices.

## Platform matrix

| | Share screen | Watch | Be controlled | Control others | LAN discovery | LAN host |
|---|---|---|---|---|---|---|
| Android | yes (MediaProjection) | yes | yes (accessibility service) | yes | yes (NSD) | yes |
| Desktop | yes (screen capturer) | yes | yes (`java.awt.Robot`) | yes | yes (mDNS) | yes |
| Web | yes (`getDisplayMedia`) | yes | no (browsers cannot inject input) | yes | no | no |
| iOS | later | later | no | later | later | later |

The web app only uses the internet signaling server: a page served over https cannot open
plain `ws://` connections to a LAN address.

## Modules

| Module | Targets | Contents |
|---|---|---|
| `shared` | android, desktop (jvm), wasmJs | protocol, signaling client, session logic, UI, platform interfaces |
| `server` | jvm | rooms, signaling route, standalone server, embedded LAN server |
| `androidApp` | android | activity, foreground service, accessibility service |
| `desktopApp` | jvm | window, packaging |
| `webApp` | wasmJs | entry point, host page |

Platform code sits behind interfaces declared in `shared/commonMain` and is provided by each
app at start-up: RTC engine, screen source, video view, input injector, LAN discovery,
embedded server.

## Technology choices

| Area | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4.20 | |
| UI | Compose Multiplatform 1.11.1 | web target is beta; 1.12 needs AGP 9.1, the Android Studio in use stops at AGP 8.13 |
| Networking | Ktor 3.6 client and server | client runs on all targets, server on the JVM |
| Serialization | kotlinx.serialization JSON | same wire format as the existing Android app and Cloudflare worker |
| WebRTC, Android | `stream-webrtc-android` | reuses the capture and audio work of the first app |
| WebRTC, desktop | `webrtc-java` 0.19 | native builds for Windows, macOS, Linux; screen and window capturer |
| WebRTC, web | browser WebRTC through Wasm interop | `RTCPeerConnection`, `getDisplayMedia`, `<video>` in `WebElementView` |
| Settings | multiplatform-settings | |
| QR codes | qrcode-kotlin | scanning stays Android only |
| Navigation, view models | JetBrains navigation-compose, lifecycle-viewmodel | |

`webrtc-kmp` was considered and rejected: no desktop target, no screen capture on the web,
and its last release (WebRTC M125) is a year old.

## Compatibility

The signaling protocol (v1, with resume tokens) and the JSON control channel keep their wire
format, so the new apps talk to the existing worker, to the embedded LAN server and to the
first Android app. New control messages are additive and ignored by older peers:

- `pointer`: mouse move, button, wheel with coordinates normalised to the shared frame
- `keyboard`: key down and up with a platform independent key code

An Android host maps pointer input to gestures; a desktop host maps touches to mouse input.

## Media

- Codec: H.264 where both sides offer it, VP8 otherwise.
- Android renders with `SurfaceViewRenderer`, desktop converts I420 frames to a Skia bitmap,
  the web embeds a `<video>` element.
- Desktop system audio capture is not part of the first version; the browser can share tab or
  system audio through `getDisplayMedia`.

## iOS

Kotlin/Native iOS targets and the simulator need macOS and Xcode, so nothing iOS specific is
built on Windows. Shared code stays free of JVM-only APIs so iOS can be added later; screen
sharing there needs a ReplayKit broadcast extension and a physical device.
