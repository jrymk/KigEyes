# Building and distributing KigEyes for Android

## App source

This directory is a self-contained Android Studio project. It includes the
Gradle wrapper, generated icons and icon notices. See [README.md](README.md) for
build commands. Build outputs, local SDK paths, device state and signing keys
are excluded from the source export.

The app connects to an existing KigEyes installation. The Pi services and
shell-privileged phone worker are separate components; building the APK does
not install them or grant shell privileges. Worker setup requires authorized
USB debugging and the matching KigEyes receiver.

An update requires the same application ID and signing key. Preserve the original
key privately for future releases. The current ID is `com.jerrymk.kigeyes`;
Android does not migrate an older package's private data through `adb install -r`.
Release signing is not configured in this source tree.

## Optional developer bundle

`package-companion.py` requires the full KigEyes source checkout, including
`integrations/scrcpy/` and `visor/`. These sibling components may not be included
in an app-only public export. Normal APK builds do not require them.

From a full checkout, run `python companion/package-companion.py`. The command
builds/tests the app and pinned worker, then creates a developer ZIP with the
debug APK, worker, corresponding sources/patches, licences and SHA-256 inventory.
It does not publish, deploy or collect device credentials. Upstream scrcpy is
fetched at a pinned revision. Never include runtime provisioning, phone backups,
private keys or signing keystores when distributing a bundle.

## Licensing

KigEyes software follows the repository's GPL-3.0-or-later notice in `LICENSE`,
with the license text in `licenses/GPL-3.txt`. Upstream scrcpy is Apache-2.0;
its notice is not a blanket relicense of KigEyes additions. AndroidX, Material,
Kotlin and Gradle retain their own licenses. Lucide/Feather icon notices are
included under `app/src/main/assets/licenses/`.

Review dependency notices and establish a release-signing/update policy before
distributing a production APK. The developer bundle is not a production release.

## Security boundaries

BLE setup uses numeric comparison and authenticated, encrypted GATT. Users compare
codes and approve both devices. Full-phone mirroring uses certificate-pinned TLS
and an independent token; the paired KigEyes receiver can view and control the
phone. The companion communicates with the worker through UID-checked local Binder
operations. The worker does not accept arbitrary shell commands from the app.

Optional camera streaming and commands use tokenized RTSP/HTTP on a trusted
local network. They do not have the full-phone mirror's transport encryption.
Debug-only automation requires Android's DUMP permission and is absent from the
release variant. These boundaries have targeted tests, not an independent audit.
