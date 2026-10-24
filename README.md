# ANONH4X LagSwitch (Android)

## 0.3.2 stability update
- Runs the VPN engine in a dedicated Android process so a native network-engine failure does not directly terminate the main UI process.
- Uses sticky foreground-service recovery; an automatic service restart begins with Fake Lag disabled.
- Caps concurrent relay flows and reuses UDP receive buffers to reduce memory pressure.
- Pushes live delay/loss/bandwidth slider changes to the VPN service explicitly across processes.
- Persists uncaught Java VPN-process traces for diagnostics.

Rootless Android VPN app for a controlled network-delay experiment.

## Implemented path

- Android VpnService TUN interface with IPv4 and IPv6 routes.
- A userspace TCP/IP stack based on gVisor is built into an Android AAR during GitHub Actions.
- TCP and UDP flows are relayed to their original destination using upstream sockets protected with VpnService.protect().
- Bounded FIFO queues apply configurable latency in both directions while the switch is ON.
- Configurable UDP datagram loss (0–50%) is simulated by safely dropping whole UDP datagrams; TCP byte streams are never corrupted by deleting bytes.
- Configurable bandwidth caps (unlimited or 50–10,000 kbit/s) pace traffic per direction across all selected-app flows.
- The floating ON/OFF switch changes the delay/loss/bandwidth policy in place without rebuilding the VPN interface.
- App-list changes are saved immediately but require a manual VPN disconnect/reconnect to update Android's per-app VPN allow-list.
- Only Oxide: Survival Island and the app's own HTTPS connectivity test are included in the VPN; other apps remain outside it.
- DNS ports 53 and 853 bypass artificial delay.
- Unit tests cover configured delay, zero-delay pass-through, bounded queue backpressure and ordering.

## Important limitations

This is an experimental network-condition test, not a guarantee that an online game will stay connected. Delay is applied to queued TCP byte chunks and UDP datagrams, not physical radio frames. UDP packet loss is simulated at the datagram relay layer; this is not a raw-IP packet dropper, and TCP retransmission loss is not simulated by deleting TCP stream bytes. Bandwidth is capped independently per direction across selected-app flows. Delaying upload and download can add around two seconds or more to request/response interactions; the game server may time out. The game's own traffic is delayed too, so it cannot selectively affect only opponents.

The built-in HTTPS test verifies this app's route through the local TUN, not every Oxide server or gameplay protocol. First test with VPN on and Fake Lag OFF, then test with it ON. The floating switch intentionally keeps the same TUN/netstack instance alive. If you change the selected-app list while connected, disconnect and reconnect VPN once to apply the new allow-list. Use VPN'İ KAPAT to restore normal Android routing. Android only permits one active VPN at a time.

## Build from an Android phone

1. Open https://github.com/furkanmeyra2173-create/LagSwitch/actions
2. Open the latest Build Lag Switch APK run.
3. Verify both the gVisor AAR build and unit-test/APK-build steps passed.
4. Download the LagSwitch-debug-apk artifact, extract it, and install app-debug.apk.

A green APK step alone is not enough; the earlier gVisor/AAR build and queue tests must also pass. Device-level connectivity still needs to be tested on the phone.
