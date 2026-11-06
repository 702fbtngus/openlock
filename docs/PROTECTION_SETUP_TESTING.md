# Protection setup checklist

The setup screen has six items: **Device administrator**, followed by the original
five permissions. Restricted settings is a help button below the device-admin
description, not a separate permission item.

## Restricted settings

- Try device administrator **Grant** first. If Android blocks the action with a
  restricted settings warning, acknowledge/dismiss the system warning.
- Tap **Blocked by restricted settings?** under the description. The English
  popup must explain that the system warning may be needed before the App info
  three-dot menu appears.
- **Cancel** must stay in OpenLock. **Open App info** must open OpenLock's own
  App info. Choose three-dot menu > **Allow restricted settings**, confirm with
  the device's authentication, return and tap **Grant** again.
- If Android does not require this step, activate device admin normally.
- Opening help or App info must not request/mark device admin as granted.

## Device administrator

- **Grant** must open Android's activation screen for OpenLock.
- Do not use `FLAG_ACTIVITY_NEW_TASK`: Android's DeviceAdminAdd finishes
  immediately when this flag is present. This was the cause of the previous
  launch path closing without showing registration. The launch now uses the
  current Activity and reports thrown errors through the platform channel.
- Grant must launch before loading/saving the Flutter protection config. Resume
  refresh applies Android's actual decision, rather than enabling the guard
  before its own activation screen opens.
- Activate, then return. The item must show a check and the existing Settings >
  Prevent uninstall option must reflect the active administrator.
- Repeat with activation cancelled. The item must retain **Grant**, and the
  prevent-uninstall config must match the actual inactive state.
- When a launch throws, a message and **Help** action must appear; Grant remains
  available to retry. A system restriction dialog is still handled by Android.
- With an already active administrator, setup must show a check immediately.
- Repeat on Samsung One UI and the target Xiaomi/HyperOS device. Existing PIN
  protection can require authentication when opening protected settings.
- Confirm the other five permission actions and protection start still work.

## Automated validation

`flutter test test/widget/onboarding_permissions_test.dart` covers the six-item
checklist, inline help, the App info explanation/confirmation, launch failure
feedback, and activation/cancellation state synchronization. The existing CI runs this file with the full Flutter test
suite, analyzer, Android build, and Kotlin tests.

Reference: [Android Help: restricted settings](https://support.google.com/android/answer/12623953?hl=en).
The new-task rejection is explicit in [AOSP DeviceAdminAdd](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/master/src/com/android/settings/applications/specialaccess/deviceadmin/DeviceAdminAdd.java).
The newer enhanced-confirmation manager is a privileged System API, not a public
API available to OpenLock: [AOSP source](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-xr-arcore-release/android-35/android/app/ecm/EnhancedConfirmationManager.java).
