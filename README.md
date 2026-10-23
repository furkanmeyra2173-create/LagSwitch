# ANONH4X LagSwitch (Android)

Rootless Android VPN app for a controlled network-delay experiment.

## Implemented path

- Android VpnService TUN interface with IPv4 and IPv6 routes.
- A userspace TCP/IP stack based on gVisor is built into an Android AAR during GitHub Actions.
- TCP and UDP flows are relayed to their original destination using upstream sockets protected with VpnService.protect().
- Bounded FIFO queues add a 1000 ms release delay in both directions while the switch is ON.
- The ON/OFF switch changes the delay policy while keeping the VPN tunnel running.
- Only Oxide: Survival Island and the app's own HTTPS connectivity test are included in the VPN; other apps remain outside it.
- DNS ports 53 and 853 bypass artificial delay.
- Unit tests cover configured delay, zero-delay pass-through, bounded queue backpressure and ordering.

## Important limitations

This is an experimental latency test, not a guarantee that an online game will stay connected. Delay is applied to queued TCP byte chunks and UDP datagrams, not physical radio frames. Delaying upload and download can add around two seconds or more to request/response interactions; the game server may time out. The game's own traffic is delayed too, so it cannot selectively affect only opponents.

The built-in HTTPS test verifies this app's route through the local TUN, not every Oxide server or gameplay protocol. First test with VPN on and lag delay OFF, then test with delay ON. Use VPN'İ KAPAT to restore normal Android routing. Android only permits one active VPN at a time.

## Build from an Android phone

1. Open https://github.com/furkanmeyra2173-create/LagSwitch/actions
2. Open the latest Build Lag Switch APK run.
3. Verify both the gVisor AAR build and unit-test/APK-build steps passed.
4. Download the LagSwitch-debug-apk artifact, extract it, and install app-debug.apk.

A green APK step alone is not enough; the earlier gVisor/AAR build and queue tests must also pass. Device-level connectivity still needs to be tested on the phone.
