# Lag Switch (Android prototype)

Rootless Android VPN-based network latency experiment.

## Current state
This is a **starter scaffold, not yet a working network lag switch**. It contains the app UI, ON/OFF button, VPN permission request, VPN service lifecycle, and GitHub Actions APK workflow. It does not yet include a packet-forwarding engine, so it does not delay or forward game traffic.

This is deliberate: creating a VPN TUN interface without a complete forwarding engine would break connectivity instead of creating a controlled 1000 ms delay.

## Build from your phone
1. Open the Actions tab and enable workflows if prompted.
2. Run **Build Lag Switch APK**, or push a commit.
3. Open the completed workflow run and download the `LagSwitch-debug-apk` artifact.
4. Extract the artifact ZIP and install `app-debug.apk`.

## Next step
Add and test an Android-compatible TUN-to-socket forwarding engine, then implement bounded packet/flow queues and a 1000 ms delay policy. Delaying TCP/UDP can cause timeouts and cannot guarantee that only remote players in a game are affected.
