# Shizuku integration into OpenLock

## Repository structure

The Shizuku fork lives at <https://github.com/702fbtngus/openlock-shizuku>, on its
`openlock-integration` branch. OpenLock includes it at `third_party/shizuku` as a
Git submodule. Shizuku's own `api` repository remains a nested submodule at the
revision selected by upstream.

The parent repository records an exact fork commit. The `.gitmodules` branch
entry is an update hint, not a request to fetch the latest branch on every build.

```text
OpenLock
  android/app                  Existing Flutter host and protection code
  lib                          Existing Flutter UI
  third_party/shizuku           Separate Shizuku fork (Git submodule)
    manager                    Upstream native Android UI and pairing
    server / starter           Upstream privileged server and startup
    api                        Upstream nested Git submodule
```

## Current phase

Phase 1 established the source dependency and standalone compilation. Phase 2
adds the embedded library and the Settings > Advanced > Shizuku entry. The menu
uses existing PIN/biometric reauthentication before opening the upstream native
Activity. The fork is consumed as a Gradle composite build, with its own plugin
and version-catalog configuration. All OpenLock APK workflows now check out
recursive submodules and use Java 21, NDK 29 and CMake 3.31.

Pushing code or workflow changes to the fork's `openlock-integration` branch
starts **Shizuku standalone baseline** in Actions. The job compiles the original
manager and server using `:manager:assembleDebug`. It does not need OpenLock
signing secrets and uploads diagnostics rather than an APK. The fork's default
branch remains upstream `master`; GitHub's manual-run UI requires the dispatch
definition on the default branch, so use the integration branch's push trigger
in this phase. Do not treat compilation as an installation or device test.

Verified on 2026-11-07: fork revision
`7d14f543712182cf355450be087469fd860e2451` passed the standalone workflow,
including recursive checkout, tool installation, `:manager:assembleDebug` and
the APK-output check. [Successful Actions run](https://github.com/702fbtngus/openlock-shizuku/actions/runs/42339827503).
The first run exposed the setup action's retired `tools` SDK-package default;
the workflow now requests `platform-tools` explicitly. No original manager,
pairing, starter or server application source was changed in this phase.

The application target is one OpenLock APK with an entry under
**Settings > Advanced > Shizuku** opening the upstream native manager UI. There
will not be a separately installed Shizuku app. Integration-specific source
changes belong in the fork; the parent keeps the launch bridge and OpenLock
permission-monitoring logic.

The original screens, pairing and startup code execute in `:shizuku`, separate
from the protection monitor's process/main thread. Host-specific package and
permission identifiers, server process/config names, native extraction and
initialization are adapted in the fork. Embedded retries do not force-stop the
OpenLock host. The official API submodule itself is unchanged. Legacy external
rish entry points are disabled, and compatibility with other apps' official
Shizuku clients is not provided in this phase.

The fork workflow also compiles an independent composite consumer in Debug and
Release and inspects the APK manifest/native payload. This is a fixture for
library/packaging verification, not the full Flutter app or an installable test
release. The parent CI/Preview build must pass after the parent source and
updated submodule pointer are committed. Additional permission recovery remains
disconnected; existing WSS accessibility recovery continues independently.

Phase 2 fork verification passed on 2026-11-07 at
`82a574ae679f94d499ce8d675ec6d20c55950cdb`: the standalone manager, both
composite host variants and the actual APK structure/native-payload checks all
passed, including R8/resource shrinking for the composite Release host.
[Successful phase 2 Actions run](https://github.com/702fbtngus/openlock-shizuku/actions/runs/42351363241).
OpenLock's local Dart analysis (`--fatal-infos`, lib/test), 126 Flutter tests,
127 Kotlin/JVM tests (`:app:testDebugUnitTest`) and the store-build-mode test
also passed. The new PIN tests exposed premature
controller disposal in the existing Settings PIN prompt; the prompt now owns
its controller until the closing dialog route is unmounted.

The full OpenLock ARM64 Release APK also compiled locally with Flutter 3.41.3.
Inspection of the APK confirmed the OpenLock identity, one launcher, existing
protection components, embedded manager process, scoped permissions, native
executables, rish asset and license files. Host and fixture provide the original
compile-only hidden-API signatures to R8, with targeted rules for APIs outside
those stubs. An initial local `--no-pub` build reused a test-generated plugin
registrant and failed; the normal Flutter build regenerated its Release tooling.

The local APK uses the local test signing identity. Preview Actions supplies the
existing fixed signing identity after the parent changes are committed. Pairing
and protection behavior still require Samsung/Xiaomi device checks. No physical
device was connected or modified during these compiler/test checks.

## Clone and update

```sh
git clone --recurse-submodules https://github.com/702fbtngus/openlock.git
```

For an existing checkout:

```sh
git submodule update --init --recursive
```

The nested upstream API URL currently uses GitHub SSH. GitHub Actions checkout
with `submodules: recursive` converts GitHub SSH URLs to HTTPS when no SSH key is
provided. For a local machine without GitHub SSH configured, use this one-command
rewrite (no global Git configuration change):

```sh
git -c url.https://github.com/.insteadOf=git@github.com: submodule update --init --recursive
```

When working on the fork, commit/push its changes first. Then update and commit
the parent gitlink. A parent pointer must never reference an unpublished local
fork commit. A normal submodule update restores the parent-selected revision;
updating to a newer fork revision is deliberate work, followed by verification.

Commit the parent source/workflow changes and updated `third_party/shizuku`
gitlink together. This phase selects the verified fork revision above, which
is already pushed. A push to parent `main` starts CI and Build preview APK.

## Acceptance order

1. Recursive source checkout and standalone compiler baseline pass in Actions.
2. Preserve the original UI in an embedded library, sharing the OpenLock APK.
   Resolve initialization, native libraries, resources and package/permission
   identifiers. Preserve the existing OpenLock signing identity.
3. Add the native launch bridge and the Settings > Advanced entry. Verify Back
   navigation and authentication around manager access and maintenance actions.
4. Test pairing/server startup and reconnection after reboot, Wi-Fi changes,
   screen off and app updates. Record Samsung and Xiaomi results separately.
5. Connect recovery one permission at a time, rechecking actual permission state
   and service connectivity after each attempt.

The app depends on the fork's `embedded` library, rather than its standalone
`manager` application module. The fork retains both standalone and composite
consumer build checks. Keep their toolchain changes aligned with the three
OpenLock APK workflows.

## Real-device phase 2 checklist

- Update using the existing OpenLock signing identity; verify no extra launcher
  icon or separately installed Shizuku package is added.
- Open Settings > Advanced > Shizuku. Cancel/wrong PIN must not open the manager;
  correct PIN/biometrics must open the original UI. Back must return to OpenLock.
- Pair the embedded manager independently. Existing official Shizuku pairing
  keys/data are not imported. Verify server startup and its actual shell UID.
- Confirm native starter commands target OpenLock, not the installed official
  Shizuku. Existing official Shizuku's process/config must remain separate.
- Turn wireless debugging off after startup; check actual backend responses.
  Repeat screen off/on, Wi-Fi loss/reconnection, reboot then Wi-Fi connection,
  and app update. Check backend absence, not just the UI running label.
- Repeat existing settings guards, scheduled app/full-phone locking, admin
  protection and WSS restoration tests. Record Samsung and Xiaomi separately.
- Assess manager access after leaving/returning via Recents and maintenance stop
  behavior before enabling automatic repairs. The menu gate is not a claim that
  every upstream system/authorization entry path requires a fresh PIN.

## Scope and attribution

Retain upstream source history, license and attribution notices. Review the
upstream README's icon/name/application-ID/permission-name restrictions before
distributing an embedded build; review nested and native dependencies separately.
The fork baseline does not distribute the original-branded APK.

Existing WSS accessibility recovery remains independent. Embedding the server
does not grant root, Device Owner or a way to ignore an OEM debugging restriction.
The user reported successful Shizuku restart after reboot and Wi-Fi connection
on the Samsung test phone; that observation does not validate the future embedded
build or the final Xiaomi device.

See [the permission recovery research](PERMISSION_RECOVERY_RESEARCH.md) for the
permission-specific mechanisms and limitations, and the fork's
`OPENLOCK_INTEGRATION.md` for standalone build requirements.
