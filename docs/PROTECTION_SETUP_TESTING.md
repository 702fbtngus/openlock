# Protection setup checklist

The setup screen has seven items. The first two are **Allow restricted settings**
and **Device administrator**, followed by the original five permissions.

## Restricted settings

- Tap **Grant**. The English instructions must appear before leaving OpenLock.
- **Cancel** must stay in OpenLock. **Got it** must open OpenLock's own App info.
- Where available, use the three-dot menu > **Allow restricted settings**, then
  confirm using the device's authentication and return to OpenLock.
- If the menu is absent or permission is already allowed, try granting Device
  administrator. Some Android versions only offer the menu after a restricted
  action has first been attempted.
- Opening App info must not mark the step complete. Android 13+ does not expose
  a public status API for ordinary apps, so this manual step retains **Grant**.
  Android versions before 13 show a check because the step is not applicable.

## Device administrator

- **Grant** must open Android's activation screen for OpenLock.
- Activate, then return. The item must show a check and the existing Settings >
  Prevent uninstall option must reflect the active administrator.
- Repeat with activation cancelled. The item must retain **Grant**, and the
  prevent-uninstall config must match the actual inactive state.
- With an already active administrator, setup must show a check immediately.
- Repeat on Samsung One UI and the target Xiaomi/HyperOS device. Existing PIN
  protection can require authentication when opening protected settings.
- Confirm the other five permission actions and protection start still work.

## Automated validation

`flutter test test/widget/onboarding_permissions_test.dart` covers the seven-item
checklist, the App info explanation/confirmation, and activation/cancellation
state synchronization. The existing CI runs this file with the full Flutter test
suite, analyzer, Android build, and Kotlin tests.

Reference: [Android Help: restricted settings](https://support.google.com/android/answer/12623953?hl=en).
The newer enhanced-confirmation manager is a privileged System API, not a public
API available to OpenLock: [AOSP source](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-xr-arcore-release/android-35/android/app/ecm/EnhancedConfirmationManager.java).
