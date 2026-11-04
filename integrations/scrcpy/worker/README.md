# KigEyes scrcpy phone worker

A scrcpy server extension for phone-to-KigEyes screen sharing and remote input.
ADB starts a shell-privileged worker once; it then reconnects over certificate-pinned
TLS without keeping an ADB transport open. The Android companion controls consent
and can supply a secondary Wi-Fi route.

This export contains the **Android worker, patches and build tests**. It does not
contain a desktop viewer, the KigEyes receiver or Visor. Building this directory
produces a worker artifact, not a complete mirroring setup. A compatible receiver
and authorized provisioning are required. The companion app is published
separately in the repository's `companion/` folder.

## Upstream and current version

- Upstream: [scrcpy 5.0.1](https://github.com/Genymobile/scrcpy/tree/v5.0.1)
- Pinned commit: `a60891aea193d92e7e5c3942700eca63f9d19a5f`
- Worker version: **5.0.1-kigeyes.11**
- Minimum Android API: **31** (Android 12)

Upstream scrcpy owns screen capture, encoding and Android input injection.
KigEyes adds connection supervision, companion access and panel safeguards.
It is a separate process from the ordinary Android app; installing an APK cannot
grant it shell privileges. No root or Shizuku is required.

## Extensions

| Feature | Source |
| --- | --- |
| Outgoing TLS channels and reconnect lifetime | `DirectAgent.java`, `PinnedTrust.java` |
| Fixed operations and companion UID checks | `DirectActions.java`, `WorkerAccess.java`, `WorkerLocalControl.java` |
| Binder handoff and leased Network selection | `WorkerProvider.java`, `CompanionNetwork.java`, `NetworkRouteLease.java` |
| Demand-driven wake hints | `AwakePolicy.java` |
| Restore a blanked panel after heartbeat loss | `panel-watchdog.patch`, `PanelPowerLease.java` |
| Request a keyframe after congestion | `keyframe-request.patch` |
| Restrict local encoder sockets to shell UID | `direct-peer.patch` |
| Guarded blanking with lock-before-restore | `guarded-panel.patch`, `GuardedPanel.java`, `GuardPlatform.java` |
| Complete control-packet relay | `ControlRelay.java` |
| Ordinary Power escape and confirmed panel state | `panel-state.patch` |

## Build

The supplied builder targets **Windows**. Install Python 3.12+, Git, Android
Studio's JBR, SDK platform **37.0** and build-tools **36.0.0**. It uses Android
Studio's default Windows paths unless overridden by `ANDROID_HOME` and
`SCRCPY_JAVA_HOME`.

From the repository root:

```powershell
python integrations/scrcpy/worker/build.py
```

Or run `python build.py` from this directory. Compilation uses a temporary local
directory under `~/.cache/kigeyes-scrcpy`, so NAS checkouts are supported. The
builder verifies the upstream tag's commit, extracts that revision, applies all
five patches, compiles the additions, runs tests and creates:

```text
~/.cache/kigeyes-scrcpy/scrcpy-server-v5.0.1-kigeyes.11
~/.cache/kigeyes-scrcpy/build-manifest.json
```

The manifest records the upstream revision, source/patch hashes, SDK versions
and output hash. The build does not contact or update a device. Outputs and
provisioning must stay outside the source export.

Reference artifact SHA-256 for the current pinned build:

```text
ceacd8bacd3ba4fc7db8412374c3c6bd5ef9eb506d64129c0e9927407b9afb4f
```

A matching receiver must pin the reviewed artifact and protocol version. Do not
replace a running worker or mix arbitrary client/server versions. Source changes
or a different compiler may change the hash; review them before updating a pin.

## Tests and limits

The build runs Java checks for TLS pinning, restricted actions, caller UID,
Network lease expiry, wake policy, panel leases, keyframe requests, guarded lock
ordering and control-packet framing. These tests do not contact a phone or receiver.

Android hidden APIs and physical panel behavior remain device-dependent. A phone
reboot, explicit worker stop or OS process kill requires another authorized ADB
start. App force-stop can release secondary Wi-Fi even when the worker survives.
A frozen OS or hardware failure cannot be repaired by an in-process watchdog.
See [transport and security](DIRECT_TRANSPORT.md) for protocol and limitations.

## Licensing

Upstream scrcpy is copyright Genymobile and contributors under Apache-2.0; its
license is retained in [LICENSE](LICENSE). KigEyes additions follow the existing
GPL-3.0-or-later software notice at the repository root; see [NOTICE.md](NOTICE.md).
No license is changed by this source export.
