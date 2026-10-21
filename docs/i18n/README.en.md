# PeerNetVPN (PeerNet) for Android

Encrypted internet connection sharing between Android devices. Android 10 or later;
root is not required. The app supports eight interface languages.

**0.5.0 is an experimental debug build.**

## Install and connect

1. Download the 0.5.0 debug APK from this repository's [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases).
2. Install it on both Android devices; allow installation from your chosen source.
3. Start sharing on both devices and wait for upload measurement to finish.
4. Open Map, choose a region and an available node. The discovery directory is
   configured by default and can be changed in Settings.
5. On the receiving device, select apps and approve Android's VPN connection.
   Only selected apps use the tunnel. Reconnect after changing the selection.

Sharing must remain enabled and reachable to use another peer's connection.
Having clients or a minimum amount of uploaded traffic is not required.
Each device serves up to three devices; their combined limit is one third of
the measured upload speed. There are no credits, wallets or purchases.

## Limitations and privacy

- Discovery does not guarantee connectivity. Some NAT/CGNAT combinations need
  a reachable participant relay; manual ICE/WebRTC pairing has no TURN server.
- The directory exchanges discovery and pairing data, not VPN traffic. Manual
  invitations and pairing remain available as fallbacks; keep invitations private.
- Encryption protects transport between devices. The exit peer sees destinations
  and unencrypted application traffic. PeerNet does not promise anonymity.
- Speed measurement sends up to 24 MiB of test data per cycle to Selectel or
  fallback provider 59telecom. It runs at sharing startup, on network changes and
  every 15 minutes. Measurement disconnects sessions; reconnect afterwards.
  Sharing internet traffic requires a successful measurement.
- Country lookup contacts api.country.is. Advertised peer countries are approximate
  and are not proof of physical location. Public mode exposes address and key.
- Android allows one system VPN per profile. Sharing uses a foreground notification
  and keeps the CPU awake. TCP/UDP are supported; ICMP, multicast and access to the
  exit owner's private network are not routed. DNS goes through the exit.

## Build locally on Windows

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The script prepares Java 17, Gradle 8.13 and Android SDK 35 in `.tools`, verifies
downloads and accepts SDK licences. APK: `app/build/outputs/apk/debug/app-debug.apk`.
Physical-device checks require a connected device with USB debugging enabled.
See the [main README](../../README.md), [protocol](../protocol.md) and
[third-party notices](../../THIRD_PARTY_NOTICES.txt).

## Languages

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
