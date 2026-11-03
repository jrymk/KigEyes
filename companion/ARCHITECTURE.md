# Companion architecture

The app separates presentation, connection ownership, Camera2 capture and the
privileged phone worker. Screen sharing does not require Camera2 capture, and
camera recording does not depend on a viewer or a working network.

## Owners

| Component | Responsibility |
| --- | --- |
| `MainActivity`, `CompanionHome`, `SettingsPage`, `CameraScreen` | Navigation, immutable UI state and user actions; attach/detach previews |
| `CameraService` | Foreground lifecycle host for capture and the connection owner |
| `CompanionConnection` | Bluetooth provisioning, discovery and Wi-Fi lifetime, independently of camera capture |
| `WifiLink`, `HotspotStore` | Selected Android Network and private hotspot preferences |
| `CompanionLink` | Discovery, wearer-approved camera slots, current-address registration, telemetry and commands |
| `MirrorStatusService` | Worker status/notification and a binding that retains the connection owner |
| `WorkerProvider`, `MirrorWorkerClient`, `WorkerNetworkBridge` | Permission-checked Binder status/control and selected-Network handoff |
| `CameraPipeline`, `StreamRenderer`, `SurfaceEncoder` | Camera session, stream texture transforms and independent hardware encoders |
| `LocalRecorder`, `PhotoStore` | MediaStore output and recording/photo finalization |
| `RtspServer` | Tokenized camera stream over interleaved RTP/TCP |

Activities do not own the camera session. Leaving the camera page detaches its
preview rather than stopping capture. Connection and status services do not
create a second camera, encoder or Wi-Fi request.

## Discovery and networking

BLE setup uses Android numeric-comparison bonding and encrypted GATT, with
wearer approval on KigEyes. Service discovery, MTU negotiation and the initial
registration write complete before credentials are exchanged. No unauthenticated
password-transfer fallback is provided.

The Pi setup advertisement explicitly sets BLE discoverability independently of
the adapter's classic-Bluetooth discovery setting. Pairing-stage diagnostics omit
peer addresses, comparison codes and credentials. An old bond failing encryption
is distinct from scanning failure; do not reset the radio or erase controller
bonds to diagnose either. New pairing still requires the app's initial setup
request and explicit numeric comparison on both devices.

`CompanionLink` discovers `_kigeyes._tcp.` services on the selected network and
also considers the gateway and saved address. Discovery alone grants no access.
Camera registration uses an approved credential and reports the phone's current
address; commands carry IDs, acknowledgement and expiry so reconnection does not
blindly replay captures. Camera slot changes require wearer approval.

`WifiLink` requests a local-only network with `WifiNetworkSpecifier`. It tracks
both network handle and IP: a replacement Network with the same IP still requires
rebinding. Callbacks run on the main handler and stale generations cannot replace
a newer request. Requests time out after 45 seconds. Lost leases are released;
rejection/unavailability requires explicit retry instead of repeating prompts.
Sockets and HTTP requests use the selected Network rather than changing the
process-wide default route.

`BlePresence` uses a low-power filtered scan for the bonded device identity saved
only after secure provisioning. `AutoConnectPolicy` permits one request on presence
when automatic connection is enabled, the UI or background owner is active, and
STA/STA local-only concurrency is supported. It stops scanning while connected or
provisioning. Cancellation, manual disconnect and choosing current Wi-Fi suspend
automatic joins; an explicit Connect or enabling auto-connect resumes them. Failed
scans back off. Presence is not authentication: WPA and the worker's pinned TLS
remain the security boundaries. Older setups without a saved BLE identity must
pair once again to opt into presence-based connection.

`SettingsPage` owns presentation only; activity callbacks route operations to the
existing owners. `ConnectionDialog` wraps the shared Material 3 `SetupSheet` task surface, not a new network
owner, and does not interrupt camera capture. In-progress tasks require explicit close;
Connected and Ready to share permit outside-tap, swipe-down and Back dismissal. Background ownership defaults on
for new setups, preserves an existing explicit off preference, and starts with
connection setup or a saved connection. No background process resurrection after
force-stop is promised. Turning it off does not stop local recording or the shell
worker; it releases this service's connection ownership when the UI exits.

On phones with STA/STA local-only concurrency, normal Wi-Fi can retain internet
while the app maintains the KigEyes hotspot connection. This is a capability,
not an Android-wide guarantee. Other phones can explicitly request the hotspot
with the same saved credentials, replacing their primary Wi-Fi; automatic joins
are disabled on those phones. The current secondary-network worker route assumes
the KigEyes product hotspot at `10.42.0.1:8561`; arbitrary subnets are not yet
fully generalized. An app update or force-stop can release the network request;
opening the app and connecting again reacquires it.

## Screen-sharing worker

The worker is a separately bootstrapped, shell-privileged scrcpy derivative.
Authorized ADB installs/starts it; routine video and input use outgoing pinned-TLS
connections to the provisioned KigEyes receiver. The APK alone cannot grant shell
privileges or restart a stopped worker after reboot.

The app's provider requires Android's DUMP permission and shell UID 2000. The
worker checks the provisioned app UID on each local transaction. Fixed operations
cover status, permission to share and stopping the worker; this is not a generic
shell-command interface. The network handoff provides a Network handle, not TLS
credentials or a video proxy. The worker binds its own sockets to that Network. Since worker .10, the app pushes
the handle with each status/control request; a 15-second worker lease replaces
synchronous callbacks into the app, which caused Android cached-freezer kills.

`MirrorStatusService` polls at a bounded rate and presents actual worker state.
Allowing sharing is distinct from active streaming. A foreground notification
retains the app's background network owner; hiding it does not itself stop the
shell worker but may release the network once no other owner remains.

Package and worker must match. The current app ID is `com.jerrymk.kigeyes`; the
worker must support the app-side install, sharing-consent and Network-handle
protocols. Worker .10 or later supports the pushed Network lease. Migrating from an older app ID requires
new permissions/pairing and worker bootstrap with the new UID. Existing app data
must be preserved separately; Android does not transfer it through `install -r`.

## Camera and recording

The pipeline uses a camera stream/preview surface and a separate high-resolution
recording surface. The GPU fits the stream texture, compensates for sensor
orientation, and encodes the stream at the configured lower resolution. Local
1080p/4K recording is not an upscale of that stream. Supported output combinations,
frame durations and hardware encoders are checked before starting.

Photo capture uses a separate JPEG output when the simultaneous configuration is
supported. Camera2 processing requests use advertised capabilities and preserve
template defaults when unsupported. They do not reproduce proprietary stock-app
processing. The optional vendor Night extension temporarily changes the session,
pauses streaming, and restores the ordinary pipeline afterward; it is blocked
while recording. It shares the existing camera owner and recovery path.

Local H.264 recording policy:

| Mode | Capture rate | Playback | 1080p target bitrate |
| --- | --- | --- | --- |
| High detail | 30 fps | Real time | 16 Mbps |
| Balanced | 30 fps | Real time | 8 Mbps |
| Small file | 30 fps | Real time | 4 Mbps |
| Low fps | 5 fps | Real time | 1.6 Mbps |
| Time-lapse | 2 fps | 15x, nominal 30 fps | 0.96 Mbps |

4K uses the same ratios against a 40 Mbps High-detail target. Targets are not
measured file-size guarantees. Frame limiting happens before encoding; compressed
reference frames are not discarded to implement low-fps recording. Camera/ISP
cadence remains unchanged, so storage savings are not proportional power savings.

`LocalRecorder` writes through a bounded disk queue, starts on a keyframe, checks
monotonic timestamps, and reports low storage/overload rather than silently
losing frames. Network stalls do not stop local recording. Normal stop finalizes
the MP4; an abrupt kill may leave a retained, unfinalized pending file.

Camera interruption recovery retains recording/encoder state and retries for a
bounded period. Timestamps preserve any real interruption as a gap. Photos use
capture-time orientation; video orientation is fixed at recording start. Capture
date metadata includes a local timestamp with an explicit timezone offset.
Video audio and manual exposure/focus controls are not currently implemented.

`RtspServer` has bounded per-viewer queues. Overflow requests a keyframe, blocked
writers are disconnected, and new readers start on a fresh keyframe. This camera
path is tokenized plaintext RTSP/HTTP for trusted networks; it is separate from
the encrypted full-phone mirror transport.

## Development boundaries

Debug-only activities require DUMP permission; the public launcher does not accept
developer actions. Release builds omit the developer entry point. JVM tests cover
pure state, frame parsing, geometry, recording policy and metadata; physical
camera, radio, Android lifecycle and thermal behavior still need device testing.

Canonical icons live in the main KigEyes source tree. Generated Android vectors
and their notices are included here, so ordinary app builds do not require icon
regeneration. The optional combined APK/worker packaging script needs the full
source checkout; see [DISTRIBUTION.md](DISTRIBUTION.md).

## Guided USB setup

Screen sharing defaults off in both the new worker and app consent policy. A
successful Bluetooth/Wi-Fi connection never enables it. Existing app installs
without an explicit sharing-consent preference pause an older permissive worker.
New guided installs write `allowed=false`; the final Start sharing action is
separate from installation and connection.

The pairing and worker setup sheets block the app beneath them. In-progress
tasks require explicit cancellation; completed tasks allow outside-tap, swipe-down
and Back dismissal. Opening Android settings does
not cancel a pending install; a phone-issued setup intent expires after five
minutes or when the user closes setup. Worker installation needs USB debugging
and Android's USB approval, but neither Wi-Fi nor wearing the glasses.

`WorkerInstallRequest` holds a random, short-lived intent in app memory.
`WorkerProvider` exposes only its value/status to UID 2000 behind DUMP permission.
The existing Pi mirror `Installer` checks authorized USB devices every five
seconds and starts its one bounded job only when such an intent exists. Bootstrap
rechecks the same intent, rejects wireless/emulator paths and ambiguous matches,
then provisions the existing pinned-TLS worker. It cannot run arbitrary client
commands. No privileged HTTP endpoint or Bluetooth security downgrade was added.
The OSD install action remains available. Monitor and install workers stop with
the mirror service. No radio/display reset or new service is involved.

Connection status names the discovered mask, distinguishes a Bluetooth bond from
a live GATT connection, and identifies the Wi-Fi route. Settings show STA/STA
local-only capability; single-STA phones get a red replacement warning and retain
explicit connection rather than automatic joins.

## Wearer viewer

A small eye icon opens the Material 3 `WearerViewerActivity`. A retained
`WearerSession` owns the Media3 player and selected-Network service binding across
rotation. Camera and viewer pages use sensor rotation; home and settings follow
Android rotation settings. Landscape viewer video fills the page with auto-hiding
controls, preserving aspect ratio. Backgrounding or closing releases its reader.

Media3 receives the existing trusted-LAN wearer RTSP stream over TCP with sockets
bound to the app's selected Network, including secondary Wi-Fi. It does not change
process routing, bypass stream permissions or silently enable capture mode.
`WearerLatencyPolicy` uses an 80 ms initial / 100 ms rebuffer threshold and
bounded temporary playback acceleration to drain RTSP backlog. Media3 RTSP does
not expose a moving live timeline and its sample loader does not enforce the
usual maximum-buffer limit; merely setting LiveConfiguration/maxBuffer is not
sufficient. Catch-up uses received samples, never RTSP seeks/reconnects. It returns
to normal speed below 180 ms queued video. This queue estimate excludes upstream
capture/encode/network and codec/display delay; it is not glass-to-phone latency.
Debug builds log only queue duration/state/speed, never URLs or scene contents.


`WearerRenderersFactory` additionally requests Android's low-latency decoder mode
only when the chosen codec advertises it, with a normal-mode retry if configuration
fails. Queue duration and decoder-ready state are not end-to-end latency
measurements; physical camera-to-phone latency needs device testing.

## Guarded screen-off shooting

Settings and the ongoing sharing notification expose **Screen off** while sharing.
The shell worker owns display power and the Android lock boundary; the app sends
only a fixed UID-authorized local command. A PIN/password is required. Power
returns to Android's locked screen; Volume remains available for camera controls.
The mode temporarily sets immediate screen-off locking and journals/restores the
old timeout. Closing the mirror or losing its heartbeat also locks before restoring
the panel. See the worker's README for crash limits and protocol details.

This is an opt-in convenience, not a replacement for locking an unattended phone:
the phone stays logically unlocked while shooting and stock-camera recording may
stop when Android locks. Worker .9 introduced guarded blanking; .11 also releases
ordinary Visor blanking on Android sleep and reports confirmed panel operations
back to Visor. Ordinary blanking follows Android's existing lock policy, while
guarded mode explicitly locks and verifies before restoring the panel. Neither
mode unlocks the phone. Pairing alone never enables blanking.
