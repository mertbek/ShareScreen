# Releasing

1. Set `sharescreen.version` and `sharescreen.versionCode` in `gradle.properties` and commit.
2. Tag the commit with `v` followed by the version, for example `v0.1.0`, and push the tag.
3. The Release workflow builds the signed Android APKs (`full` and `lite`), the Windows MSI and
   portable zip, the macOS DMGs (Apple silicon and Intel) and the Linux DEB, then creates a
   draft release with a `SHA256SUMS.txt`. Review the draft and publish it.

The workflow stops early when the tag does not match the version.

## Repository settings

Secrets, under Settings > Secrets and variables > Actions:

| Secret | Contents |
|---|---|
| `SHARESCREEN_SERVER` | the internet server, for example `wss://signal.example.com` |
| `ANDROID_KEYSTORE_BASE64` | the release keystore, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | the key alias |
| `ANDROID_KEY_PASSWORD` | the key password |

Encode the keystore on Windows with
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`, or on Linux and macOS
with `base64 -w0 release.jks`.

Other settings:

- Pages: Settings > Pages > Source: GitHub Actions. The Web workflow then publishes every push
  to `main` that touches the web app.
- Security: turn on private vulnerability reporting, Dependabot alerts and Dependabot security
  updates.

## Notes

- The Windows and macOS packages are not code signed, so SmartScreen and Gatekeeper warn on
  first start.
- Desktop packages are built on the operating system they are for, because the WebRTC native
  libraries are platform specific.
- Runner labels change over time. If `macos-15-intel` is retired, replace it in
  `.github/workflows/release.yml`.
