# KigEyes for Android

Connect your phone to KigEyes, share and control its screen from the glasses,
or use it as an external camera with local photos and video recording.

- **Screen sharing:** use phone apps, including the stock camera, through KigEyes.
- **Easy connection:** Bluetooth pairing provisions the hotspot connection.
  Supported phones can keep their normal Wi-Fi internet connection alongside it.
- **Optional camera:** stream a preview while recording independently at 1080p
  or 4K, with remote shutter, recording and zoom controls.

Built with Kotlin and Material 3. Application ID: `com.jerrymk.kigeyes`.

## Requirements

Android 12 or later and a KigEyes device with its hotspot, Bluetooth setup and
companion services configured. Screen sharing also needs the matching phone
worker and receiver services, initially set up through authorized USB debugging. No root or Shizuku
is required. Camera modes and simultaneous Wi-Fi support depend on the phone.

## Get connected

1. Tap **Pair KigEyes** on the home screen for first-time setup.
2. Compare the Bluetooth pairing codes, approve on the phone and briefly press the KigEyes power button, then
   accept Android's Wi-Fi connection prompt.
3. On phones with WiFi STA concurrency, the app can reconnect when the paired KigEyes is nearby.
   **Settings → Connection** contains automatic connection and background options;
   both default on for new setups. Existing background preferences are preserved.

Bottom sheets guide setup. Close an in-progress sheet to cancel; completed
connection and worker setup sheets can also be dismissed by tapping outside.
Phones without WiFi STA concurrency join when you tap Connect, using the provisioned password;
this replaces their current Wi-Fi connection. No password typing is required.
Same-network discovery and manual address entry remain under **Settings → Advanced**.
Automatic discovery needs Bluetooth and Nearby devices permission, and runs while
the app or its background connection service is active. Force-stopping the app
requires reopening it.

## Share the screen

Tap **Set up screen sharing** in the app. Enable USB debugging, connect the phone
to KigEyes by USB, and approve Android's debugging prompt. KigEyes detects the
phone's setup request and installs the worker; no glasses menu is required.
Then connect to KigEyes Wi-Fi and choose **Start sharing**. Sharing stays off
through installation and pairing. Once connected, USB can be unplugged.

Use **Pause sharing / Resume sharing** on the app home screen. Worker details
and Stop are under **Settings → Screen sharing**. Rebooting the phone
or stopping/losing the worker requires another ADB start; ordinary Wi-Fi
reconnections do not. Screen sharing does not start the app's camera.

## View the wearer stream

Tap the small eye icon beside Settings. The viewer uses the current KigEyes
connection, including secondary Wi-Fi, and stops when closed. A wearer capture
mode must be enabled on KigEyes; the app does not change it automatically.

## Use the camera

Open **Phone camera**, choose recording resolution and storage mode, then tap
**Start camera**. Use the photo/video buttons, pinch zoom or available zoom presets.
Stop the camera before changing resolution or storage mode.

The preview targets 540p30 with a 720p fallback; local recording can use 1080p or
4K independently. Balanced, Small file, Low fps and Time-lapse modes reduce storage
use. Photos save to `Pictures/KigEyes`, videos to `Movies/KigEyes`. Recording does
not need a viewer or network connection. **Video currently has no audio.**

Camera 1/2 assignment enables remote capture and zoom from KigEyes. Photo quality
uses Camera2 processing and may differ from the stock camera app. Supported phones
also offer an optional Night photo mode that pauses streaming.

## Build

Open this directory in Android Studio. The current toolchain uses JDK 17 or newer,
Android SDK platform 37.0, build-tools 36.0.0, Gradle 9.3.1 and AGP 9.1.1.

From this directory on Windows:

```powershell
./gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Linux/macOS, use `./gradlew` instead. Add `assembleRelease lintRelease` to check
the release variant; release signing is not configured. For Windows network-drive
checkouts, `python build-windows.py --check-release` stages the build on local disk.

Updating an existing installation requires the same app ID and signing key.
Older camera-named builds have a different identity: preserve their data and
reapprove the new app/worker rather than assuming `install -r` migrates them.

This folder builds the Android app; it does not include the Pi services or phone
worker. Ordinary app builds are self-contained. Optional icon regeneration and combined
APK/worker packaging need sibling sources from the full KigEyes checkout; see
[distribution notes](DISTRIBUTION.md).

## Security and current limits

Bluetooth setup requires code comparison and approval. Full-phone mirroring uses
certificate-pinned TLS and an authentication token; the paired KigEyes device can
view and control your phone. Credentials are stored privately on the devices.

Optional camera streaming/control uses tokenized RTSP and HTTP for trusted local
networks; it does **not** have the mirror transport's encryption.

Android may interrupt capture or stop background processes, and thermal limits
vary by phone. Stop recording normally to finalize the MP4; abrupt power loss or
process termination can leave an unfinished file.

See [architecture](ARCHITECTURE.md) for component ownership, camera modes and
connection behavior, or [distribution notes](DISTRIBUTION.md) for licensing.
