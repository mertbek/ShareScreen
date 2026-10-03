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
| Web | yes (`getDisplayMedia`, desktop browsers only) | yes | no (browsers cannot inject input) | yes | no | no |
| iOS | later | later | no | later | later | later |

The web app only uses the internet signaling server: a page served over https cannot open
plain `ws://` connections to a LAN address.

## Modules

| Module | Targets | Contents |
|---|---|---|
| `signaling` | android, jvm, wasmJs | wire protocol, room manager, ICE configuration |
| `core` | android, jvm, wasmJs | links, control protocol, RTC and platform interfaces, signaling client, host and viewer sessions, settings |
| `shared` | android, desktop (jvm), wasmJs | Compose UI and the RTC engine, screen source and video view of each platform |
| `server` | jvm | signaling route, embedded LAN server |
| `lan` | jvm | LAN server adapter, mDNS discovery |
| `androidApp` | android | activity, foreground service, accessibility service, NSD, `full` and `lite` editions |
| `desktopApp` | jvm | window, wiring |
| `webApp` | wasmJs | entry point, host page |

`signaling` and `core` have no UI or platform dependencies, so their tests run on the JVM and
under Node for wasm. Platform code sits behind interfaces declared in `core` and is provided by
each app at start-up through `AppServices`: RTC engine, screen source, input injector, LAN
server, address provider, LAN discovery, and `PlatformUi` for the video view, back handling,
QR scanning, sharing and clipboard.

## Technology choices

| Area | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4.20 | |
| UI | Compose Multiplatform 1.11.1 | web target is beta; 1.12 needs AGP 9.1, the Android Studio in use stops at AGP 8.13 |
| Networking | Ktor 3.6 client and server | client runs on all targets, server on the JVM |
| Serialization | kotlinx.serialization JSON | same wire format as the existing Android app and Cloudflare worker |
| WebRTC, Android | `stream-webrtc-android` | reuses the capture and audio work of the first app |
| WebRTC, desktop | `webrtc-java` 0.19 | native builds for Windows, macOS, Linux; screen and window capturer |
| WebRTC, web | browser WebRTC through Wasm interop | `RTCPeerConnection`, `getDisplayMedia`, `<video>` in `HtmlElementView` |
| Settings | multiplatform-settings | |
| QR codes | qrcode-kotlin | scanning stays Android only |
| Navigation | a small back stack in `App` | |

`webrtc-kmp` was considered and rejected: no desktop target, no screen capture on the web,
and its last release (WebRTC M125) is a year old.

## Compatibility

The signaling protocol (v1, with resume tokens) and the JSON control channel keep their wire
format, so the new apps talk to the existing worker, to the embedded LAN server and to the
first Android app. New control messages are additive and ignored by older peers:

- `pointer`: mouse move, button, wheel with coordinates normalised to the shared frame
- `keyboard`: key down and up with a platform independent key code

A viewer turns fingers on a computer's picture into its mouse: a tap clicks, a drag drags with the
left button held, a finger held still for a second right clicks and two fingers moving up and down
turn the wheel. The button goes down only once it is clear which of these a touch is, so a right
click or a scroll never clicks first. A desktop host still maps `touch` messages to the left
button.

An Android host gets a mouse's buttons as touches and turns the wheel into swipes, each held still
at its end so the content does not fling on. A viewer sends a right click to it as going back, as a
mouse plugged into a phone does. It takes no key presses, so a viewer turns the keys pressed for it
into `type` edits, Enter into `key`, Escape into going back and Ctrl+V into a paste.

A host on the local network announces `pin=0` or `pin=1` next to the protocol version in its
mDNS record. Viewers treat a record without it as an older host that wants the PIN, and a viewer
that joins without a PIN is asked for one when the host answers `invalid_pin`. Older viewers
always send a PIN, which a room without one ignores.

The host also announces `id`, which stays the same between sessions, and puts it in local network
links as `i`. A viewer the host remembers gets a key and a secret in a `remember` control message,
so the secret only travels over the encrypted data channel. Coming back, the viewer sends a `pass`
in its hello: the key, a counter that only grows and an HMAC-SHA256 of both with the secret. The
LAN server lets a pass skip the PIN and passes it on in the join request; the host lets the viewer
in when the pass checks out and the counter is new, and otherwise turns it away without asking, after
which the viewer forgets the host and asks the usual way.

## Media

- Codec: H.264 where both sides offer it, VP8 otherwise.
- Android renders with `SurfaceViewRenderer`, desktop converts I420 frames to a Skia bitmap,
  the web embeds a `<video>` element.
- Desktop system audio: on Windows the host records the default output device through WASAPI
  loopback (JNA calls into COM) and feeds a `CustomAudioSource` in 10 ms frames, sending
  silence while nothing plays so the stream keeps its timing. macOS and Linux share the
  picture only for now. The browser shares tab or system audio through `getDisplayMedia`.

## Web notes

- An HTML element is always drawn above the Compose canvas. Screens that would put controls or
  dialogs over the video use `PlatformUi.canOverlayVideo`: on the web the viewer keeps its
  controls in a bar above the picture and the host hides the preview while a dialog is open.
  The video and the holder Compose puts it in let pointer events through, so clicks and touches
  on the picture reach the canvas for remote control and zoom.
- Screens that assume a LAN (nearby devices, manual address, Wi-Fi hints) check for the
  matching service in `AppServices` and are left out of the web build.
- The wasm bundle is about 37 MB uncompressed (Skia and the app), so serve it compressed.

## iOS

Kotlin/Native iOS targets and the simulator need macOS and Xcode, so nothing iOS specific is
built on Windows. Shared code stays free of JVM-only APIs so iOS can be added later; screen
sharing there needs a ReplayKit broadcast extension and a physical device.
