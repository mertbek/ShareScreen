# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately with the "Report a vulnerability" button on the
Security tab of this repository, not in a public issue.

## Scope

Only the latest release is supported.

Known limits by design:

- On the local network a viewer needs only your approval. Anyone on the same network can ask,
  one request per device at a time, and a device you turn down waits a minute before asking
  again. "Ask nearby viewers for the PIN" in the settings adds the PIN for networks you
  share with others.
- A device you remember joins on the local network without the PIN or your approval. It proves
  itself with a secret handed over the encrypted media connection and a counter that only grows,
  so a pass copied off the network does not work again. Forget a device in the settings to stop
  that.
- Signaling on the local network is not encrypted, so the PIN can be read by someone else on
  that network. Use it on networks you trust.
- A viewer you allow to control a device controls all of it. On a computer that includes
  running programs.
