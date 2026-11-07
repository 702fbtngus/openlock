# Automatic accessibility recovery

OpenLock can restore its own uninstall-guard accessibility setting when you
explicitly enable **Settings → Security → Restore accessibility automatically**.
The switch appears when Prevent uninstall is enabled (and remains available to
turn off if automatic restore was previously enabled). It defaults to off.

Install the new APK first. Its manifest declares the optional permission needed
for this feature. Grant it from a computer if it has not already been granted:

```sh
adb shell pm grant dev.abdullah.openlock android.permission.WRITE_SECURE_SETTINGS
```

This is not a runtime permission dialog. OpenLock checks the actual grant before
enabling the option and before writing settings. Android's normal accessibility
setup remains available without this optional permission.

Recovery requires all of these: a PIN, **Prevent uninstall**, the automatic
restore option, and the running monitor with app protection enabled. To stop
automatic restore, turn off the option in OpenLock and authenticate with the
existing PIN/biometric flow. Turning off app protection or Prevent uninstall also
pauses recovery. PIN authentication for a system Settings screen alone does not
pause this explicitly enabled feature: switch it off first if you want to leave
the accessibility service disabled.

The monitor owns a separate recovery worker, independent of the accessibility
service and of main-thread inspection timers. It checks at startup, screen on /
unlock, on changes to both accessibility secure settings, and every **5 seconds**.
Failed repairs are limited to one attempt per 5 seconds. Neither this interval
nor ContentObserver delivery is a guaranteed repair deadline on a sleeping,
frozen or busy device. There is no wakelock or new foreground service.

When the own component is missing from `enabled_accessibility_services`, recovery
reads the latest list and appends only OpenLock. Both full and abbreviated
component names are recognized, so it does not duplicate an existing entry.
Other entries are preserved. When necessary it writes `accessibility_enabled=1`.
It never writes the master switch to 0 or clears the list. Android offers no
public compare-and-swap for this list; a different writer changing it between
the final read and write remains a possible race.

This implementation was informed by
[hyperos-accessibility-guard](https://github.com/haku4130/hyperos-accessibility-guard),
but does not copy its global disable / clear / rebind repair cycle. That cycle
would temporarily disable other accessibility services too. No root, Shizuku,
shell process, DUMP permission, overlay repair or external-app restart is added.

The diagnostic report includes the `writeSecureSettings` permission and an
`Accessibility recovery` line with attempts, settings restores, last repair and
`connectionConfirmed`. A successful Settings-provider write is **not** proof the
service reconnected. Connection confirmation comes from OpenLock's own service
callback, and is cleared on unbind/destroy. If the keys are already enabled but
the service is crashed or unable to bind, this version reports the unconfirmed
connection; it does not repeatedly reset a configured service or diagnose a
manufacturer's internal state. Force-stop/process death can stop the monitor as
well, and no in-process watchdog can run while its whole process is stopped.
Existing boot/update monitor restoration is reused, subject to Android/OEM limits.

## Device checks

1. Enable app protection, Prevent uninstall and the new option with the ADB
   grant present. With the grant absent, the switch should show instructions
   instead of enabling itself; normal guard functionality should still work.
2. After authenticating for Settings, disable **System Service**. Confirm that
   OpenLock restores its setting and actually resumes guarding protected screens.
   Inspect `connectionConfirmed` as well as the system toggle.
3. Also test master accessibility off while OpenLock remains in the enabled
   services list. Confirm recovery without removing other entries. Keep another
   accessibility service enabled to check its setting is retained.
4. Revoke WRITE_SECURE_SETTINGS: attempts must stop writing, and diagnostics /
   the Settings row must report the missing grant. Regrant, then confirm retry.
5. Turn the option off with a PIN, then disable the service: it must stay off.
   Repeat with app protection stopped and with Prevent uninstall off. Re-enable
   the required options and verify the next periodic check resumes recovery.
6. Reboot and unlock: confirm the monitor starts through the existing recovery
   path and accessibility is restored if necessary. Test screen off/on and the
   original app-info, special-access, device-admin and uninstall PIN screens.
7. On Samsung and Xiaomi, separately verify timing and actual service binding.
   An APK build or synthetic unit test cannot establish OEM behavior.

`AccessibilityRestoreLogicTest` covers preservation of other components,
abbreviated names, no writes when disabled, cancellation between writes,
re-reading a changed list, idempotence and rejected/partial writes. Existing
config serialization tests cover the new opt-in flag and native projection.
