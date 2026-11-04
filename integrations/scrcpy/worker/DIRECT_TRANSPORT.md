# Direct worker transport and security

This describes the phone worker and its expected receiver. The receiver and Visor
are not included in this source export. This is not a stock scrcpy desktop
protocol and is not compatible with an unmodified scrcpy client.

## Owners and lifetime

| Owner | Responsibility |
| --- | --- |
| Authorized installer | Verify the artifact, provision private identity and start the worker through ADB |
| `DirectAgent` (Android shell UID 2000) | Outgoing TLS connections, reconnect policy, one encoder child and wake policy |
| Patched scrcpy Controller | Input injection, display power and panel watchdog |
| Android companion | Sharing consent, status notification and app-owned Wi-Fi request |
| Compatible receiver | Authenticate the worker, request streams, consume H.264, send input and renew panel heartbeats |

ADB is used for bootstrap. Routine video, input and status use separate outgoing
TLS sockets. The worker listens on no TCP port. One private file lock prevents
duplicate supervisors. Network failure closes the encoder session and reconnects
with bounded 1–5 second retry delay; it does not relaunch the camera app.

Phone-local permission overrides receiver demand. New workers start with sharing
disallowed unless an existing private preference explicitly allows it. Pausing
stops encoding and releases wake hints. Receiver demand off stops encoding while
leaving the lightweight worker connected. Connected does not itself mean sharing.

The worker can survive ADB disconnection and receiver/network restarts. Android
can still kill it; it is not a boot service. Phone reboot or worker termination
requires another authorized bootstrap. Reconnection never silently reapplies
panel-off. Wake hints run while streaming, or while connected with an explicit
standby override. They do not unlock Android.

## Provisioning contract

`DirectAgent` accepts one argument: a private JSON configuration path. It refuses
to run as an ordinary application UID. The installer provides:

| Field | Meaning |
| --- | --- |
| `host`, `port` | Provisioned receiver destination |
| `certificate_sha256` | Exact lowercase SHA-256 fingerprint of the receiver certificate |
| `token` | Independent random 256-bit credential, encoded as 64 lowercase hex characters |
| `app_uid` | UID of the installed `com.jerrymk.kigeyes` companion |

Use private directories (0700) and files (0600). Keep credentials out of source,
examples, process arguments and logs. The worker stores its PID/lock, consent and
guarded-lock journal beside the configuration. Reinstalling the app with a changed
UID requires reprovisioning. Retain user data and identity during ordinary updates.

A compatible installer must verify the expected hash before upload, avoid replacing
a live jar, and retain a rollback artifact. Installation requires deliberate user
authorization. Merely attaching a phone is insufficient. This export does not
provide an end-user standalone installer.

## TLS and channel binding

All channels use TLS 1.2. The phone checks the provisioned certificate fingerprint
and validity before sending its token. The receiver must compare the token safely
and reject unauthenticated requests. The hotspot password is a separate network
boundary, not the screen-control credential.

Each channel starts with bounded newline-delimited JSON containing protocol
version, credential, role (`management`, `video` or `control`), worker version and
policy version. An accepted management connection receives a fresh generation.
Video/control greetings must match that generation and a requested session ID.
A receiver must reject stale, duplicate or unsolicited channels and bound
handshake concurrency, queues and message sizes.

Management commands are bounded JSON lines. `ping` carries demand and standby
policy; its response includes consent, streaming, route and panel state. `start`
supplies a 32-character lowercase hex session ID; `stop` names that session.
`shutdown` terminates the worker. Other named operations use `DirectActions`' fixed
allowlist. That interface accepts no arbitrary executable, shell fragment,
package name or intent.

Video is scrcpy's configured H.264 stream with frame metadata. Control uses the
pinned scrcpy binary protocol and extensions below. The authenticated receiver
has full screen/input privileges and is a trusted endpoint. Direct encoder
abstract sockets additionally check shell UID to exclude ordinary local apps.

TLS encrypts and authenticates stream contents, not just login. Traffic size and
timing remain observable. Compromised endpoints or stolen credentials are outside
this boundary. Revocation requires credential rotation and reprovisioning;
certificate replacement also requires updating the phone's pin. Do not expose the
receiver directly to the internet. These controls have targeted tests, not an
independent security audit.

## Companion and secondary Wi-Fi

The companion provider requires Android DUMP permission and shell UID. The worker
offers a narrow Binder capability and checks the provisioned app UID on each
transaction. Fixed app operations are status, sharing permission, stop and guarded
screen-off. The app does not receive the TLS credential or proxy video.

On capable phones, the app owns a local-only STA/STA Wi-Fi request and pushes its
selected Network handle with status/control requests. The worker uses a 15-second
monotonic lease, clears it on Binder death and reconnects on route changes. It
avoids synchronous route queries into a cached app, which can trigger Android's
cached-process freezer protections.

Only the product hotspot destination `10.42.0.1:8561` currently uses that Network;
other destinations use the default route. Sockets are individually bound without
changing the process-wide route. Failed binding/connection may fall back to the
default network; TLS checks are never bypassed. Arbitrary hotspot subnets are not
yet generalized.

App force-stop/update can release secondary Wi-Fi. Reopen the companion and
reconnect through its Wi-Fi owner; a surviving worker does not need reinstalling.
The app owns its optional notification and background Wi-Fi retention. The shell
worker does not post notifications or start Camera2.

## Panel controls

| Extension | Purpose |
| --- | --- |
| Control message 126 | Renew an already-armed panel-off lease |
| Control message 127 | Request a keyframe without restarting capture |
| Control message 128 | Arm guarded panel-off from the paired companion |
| Private acknowledgement base `0x4b47475000000000` | States 1 armed, 2 guard finished, 3 guard failed, 4 panel restored, 5 panel blanked |

Private acknowledgements are consumed inside the worker. Ordinary clipboard
acknowledgements continue to the receiver. Complete input packets are serialized
so a local app command cannot split a remote input packet.

A panel-off lease expires ten seconds after its last renewal. The receiver sends
message 126 every two seconds during a healthy control session. Renewals cannot
arm an idle lease. The Controller checks every 100 ms; expiry, controller stop or
Android sleep restores the panel. Delayed forced-off work checks the lease to
avoid darkening a panel after restoration.

Ordinary blanking follows Android's normal lock policy. Guarded mode requires an
unlocked primary user with secure keyguard, temporarily requests immediate lock
on sleep, and locks/verifies keyguard before restoring the panel. The previous
lock delay is journaled privately and restored only if the setting is still owned
by this mode. Volume remains available. No PIN is created, retained or bypassed.

Failed lock verification leaves the guarded panel dark. A hard kill/reboot can
leave the stricter timeout in place; the next successful guarded preparation
recovers the journal. This is not a secure kiosk: the phone remains logically
unlocked while shooting, and locking may interrupt camera recording.

Nullable `panel_off` status reflects successful worker-controlled panel operations,
including automatic restores. Null means unknown/no active child. It is distinct
from Android's independent sleep state and keyguard state.
