# ShareScreen signaling server (Cloudflare Workers)

The internet side of ShareScreen. It introduces devices to each other (rooms, PINs, the WebRTC
handshake); picture and sound never pass through it. It also serves the pages people land on: the
invite page (`/join`), the download page, the privacy policy and the Android App Links file. The
APKs are not served from here (a Worker asset may not exceed 25 MiB, an APK is bigger):
`/ShareScreen.apk` and `/ShareScreen-lite.apk` send the visitor to the files of the newest GitHub
release, so a new release reaches the download page without a deploy. The tag comes from the
redirect of `releases/latest` (the GitHub API would limit a shared Cloudflare address) and the file
name is the one the release workflow gives, `ShareScreen-<tag>.apk` and `ShareScreen-<tag>-lite.apk`.

It runs on Cloudflare's free plan, no card needed: a Worker plus one Durable Object that holds all
rooms (WebSocket hibernation, so an idle room costs nothing). The protocol is the one in
[`signaling/Protocol.kt`](../signaling/src/commonMain/kotlin/com/mertbek/sharescreen/signaling/Protocol.kt);
`src/room-manager.ts` is a port of the Kotlin `RoomManager`, kept in step with it. The Kotlin
[`:server`](../server) module does the same job as a JVM program, for self-hosting with Docker.

What the port leaves out on purpose: the device pass of remembered devices. A pass only works on
the local network, where the host checks it, so this server always asks for the PIN. A `pass` in a
hello is dropped when the message is read.

Connections are told apart by a one-way fingerprint of the address Cloudflare reports, never the
address itself, so a device that guesses PINs is locked out without locking out everyone else.

## Develop

```bash
cd worker
npm install
npm run dev            # http://127.0.0.1:8787 (an emulator reaches it at ws://10.0.2.2:8787)
npm test               # unit tests of the room logic
npm run typecheck
node test/e2e.mjs ws://127.0.0.1:8787/ws --idle   # end to end, including eviction from memory
```

The end to end test needs `npm run dev` running in another terminal and takes about half a minute
because it waits for the object to be evicted from memory.

## Deploy

```powershell
npx wrangler login     # once
.\worker\scripts\deploy.ps1
```

The script runs the type check and the tests and calls `wrangler deploy`. The address of the server shows at the end. Give it to the
apps as `sharescreen.server=wss://<that address>`, see the main README.

## TURN (optional)

Most connections are direct. Some networks (strict NATs, some mobile carriers) need a relay. It is
not set up here: Cloudflare's TURN includes 1,000 GB a month, then bills $0.05/GB, and creating a
key asks for a payment method. Without it only STUN is offered, and when two devices cannot reach
each other directly they can join the same Wi-Fi or a hotspot and use nearby mode. To enable it
anyway:

1. Dashboard, **Realtime**, **TURN Server**, create a key.
2. `npx wrangler secret put TURN_KEY_ID` and `npx wrangler secret put TURN_KEY_API_TOKEN`
   (paste the key's id and API token when asked).

Clients then get short-lived relay credentials in their welcome message.

## App Links

`/.well-known/assetlinks.json` (in `src/index.ts`) lists the SHA-256 fingerprints of the release
key and of the debug key, which are public values. If the release key ever changes, update it there
and redeploy; `adb shell pm verify-app-links --re-verify com.mertbek.sharescreen` re-checks on a
device.

## Limits

The free plan allows 100,000 Durable Object requests a day (a WebSocket message counts as 1/20 of
one), plenty for personal use. Deploying disconnects all sockets; clients resume automatically.
