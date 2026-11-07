import 'package:core_lock/core_lock.dart' show RecoveryKeyView;
import 'package:core_theme/core_theme.dart';
import 'package:core_ui/core_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/router/app_router.dart';
import 'package:openlock/src/core/store_build.dart';
import 'package:openlock/src/core/widgets/screen_header.dart';
import 'package:openlock/src/features/enforcement/models/lock_config.dart';
import 'package:openlock/src/features/enforcement/models/relock_policy.dart';
import 'package:openlock/src/features/enforcement/providers/config_providers.dart';
import 'package:openlock/src/features/onboarding/providers/permissions_providers.dart';
import 'package:openlock/src/features/settings/providers/settings_providers.dart';
import 'package:openlock/src/features/settings/screens/guard_diagnostics_dialog.dart';
import 'package:openlock/src/features/settings/screens/shizuku_terminal_screen.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-read live state after returning from a system screen: device-admin
    // activation, and notification-access / accessibility grants.
    if (state == AppLifecycleState.resumed) {
      ref.read(preventUninstallControllerProvider.notifier).refresh();
      ref.read(permissionsControllerProvider.notifier).refresh();
    }
  }

  String _relockLabel(RelockPolicy policy) {
    switch (policy.mode) {
      case RelockMode.immediately:
        return 'When you leave the app';
      case RelockMode.onScreenOff:
        return 'When the screen turns off';
      case RelockMode.afterTimeout:
        return 'After ${policy.timeout.inMinutes} min';
    }
  }

  /// In-app re-auth required before device admin can be deactivated. Tries
  /// biometrics first (if enabled), then falls back to a PIN prompt. Returns
  /// true only on a successful check.
  Future<bool> _authenticateForDisable() =>
      _reauthenticate('Enter your PIN to turn off uninstall protection.');

  Future<void> _openShizuku() async {
    if (!await _reauthenticate('Enter your PIN to manage Shizuku.')) return;
    if (!mounted) return;
    try {
      await ref.read(enforcementBridgeProvider).openShizuku();
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Could not open Shizuku. Please try again.')),
      );
    }
  }

  Future<void> _openTerminal() async {
    if (!await _reauthenticate(
        'Enter your PIN to open the Shizuku terminal.')) {
      return;
    }
    if (!mounted) return;
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
          builder: (_) => ShizukuTerminalScreen(
                reauthenticate: () => _reauthenticate(
                    'Enter your PIN to resume the Shizuku terminal.'),
              )),
    );
  }

  Future<void> _setAccessibilityRecovery(bool enabled) async {
    if (enabled) {
      final permissions =
          await ref.read(enforcementBridgeProvider).getPermissionStates();
      if (!mounted) return;
      if (!permissions.writeSecureSettings) {
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('ADB permission required'),
            content: const SelectableText(
              'Connect your phone to a computer with USB debugging enabled, '
              'then run:\n\n'
              'adb shell pm grant dev.abdullah.openlock '
              'android.permission.WRITE_SECURE_SETTINGS\n\n'
              'Return here and enable automatic restore again.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Got it'),
              ),
            ],
          ),
        );
        return;
      }
    } else if (!await _reauthenticate(
      'Enter your PIN to turn off automatic accessibility restore.',
    )) {
      return;
    }
    if (!mounted) return;
    await ref
        .read(configControllerProvider.notifier)
        .setAutoRestoreAccessibility(enabled);
    if (!mounted) return;
    await ref.read(permissionsControllerProvider.notifier).refresh();
  }

  /// Re-auth, confirm replacing any existing key, then show a new recovery
  /// key until the user confirms saving it.
  Future<void> _recoveryKey() async {
    if (!await _reauthenticate('Enter your PIN to create a recovery key.')) {
      return;
    }
    final auth = ref.read(pinAuthServiceProvider);
    if (await auth.hasRecoveryKey() && mounted) {
      final replace = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Replace your recovery key?'),
          content: const Text(
            'Your current recovery key will stop working. Recovery keys are '
            'never stored, so the current one cannot be shown again.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Create new key'),
            ),
          ],
        ),
      );
      if (replace != true) return;
    }
    final key = await auth.createRecoveryKey();
    await auth.markRecoveryOffered();
    if (!mounted) return;
    await Navigator.of(context, rootNavigator: true).push<void>(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (routeContext) => PopScope(
          canPop: false,
          child: RecoveryKeyView(
            recoveryKey: key,
            appName: 'OpenLock',
            onDone: () => Navigator.pop(routeContext),
          ),
        ),
      ),
    );
  }

  Future<bool> _reauthenticate(String pinPrompt) async {
    final biometric = ref.read(biometricServiceProvider);
    if (await biometric.isEnabled() && await biometric.authenticate()) {
      return true;
    }
    if (!mounted) return false;
    return _promptPin(pinPrompt);
  }

  Future<bool> _promptPin(String prompt) async =>
      await showDialog<bool>(
        context: context,
        builder: (_) => _PinConfirmationDialog(
          prompt: prompt,
          verifyPin: ref.read(pinAuthServiceProvider).verifyPin,
        ),
      ) ??
      false;

  @override
  Widget build(BuildContext context) {
    final config =
        ref.watch(configControllerProvider).valueOrNull ?? LockConfig.empty;
    final controller = ref.read(configControllerProvider.notifier);
    final biometricSupported =
        ref.watch(biometricSupportedProvider).valueOrNull ?? false;
    final biometricEnabled =
        ref.watch(biometricEnabledProvider).valueOrNull ?? false;
    final preventUninstall =
        ref.watch(preventUninstallControllerProvider).valueOrNull ?? false;
    final permissions = ref.watch(permissionsControllerProvider).valueOrNull;
    final notificationAccess = permissions?.notificationAccess ?? false;
    final screenGuardAccess = permissions?.screenGuardAccess ?? false;

    Widget toggle({
      required IconData icon,
      required String title,
      required String subtitle,
      required bool value,
      required ValueChanged<bool>? onChanged,
    }) =>
        SettingsTile(
          icon: icon,
          title: title,
          subtitle: subtitle,
          showChevron: false,
          trailing: Switch(value: value, onChanged: onChanged),
          onTap: onChanged == null ? null : () => onChanged(!value),
        );

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: PageList(
          children: [
            const ScreenHeader(eyebrow: 'OpenLock', title: 'Settings'),
            RowGroup(
              children: [
                AppearanceSetting(controller: ref.watch(appearanceProvider)),
              ],
            ),
            const SectionLead('Protection'),
            RowGroup(
              children: [
                SettingsTile(
                  icon: Icons.verified_user_outlined,
                  title: 'Permissions & status',
                  subtitle: 'Usage access, overlay, monitor service',
                  onTap: () => context.push(AppRoutes.permissions),
                ),
                SettingsTile(
                  icon: Icons.timer_outlined,
                  title: 'Relock behavior',
                  subtitle: _relockLabel(config.relock),
                  onTap: () => context.push(AppRoutes.relock),
                ),
              ],
            ),
            const SectionLead('Notifications'),
            RowGroup(
              children: [
                toggle(
                  icon: Icons.notifications_off_outlined,
                  title: 'Hide notification content',
                  subtitle: config.hideNotifications
                      ? 'Locked apps show a redacted notice'
                      : 'Replace locked apps\' notifications with a count',
                  value: config.hideNotifications,
                  onChanged: (value) async {
                    final messenger = ScaffoldMessenger.of(context);
                    await controller.setHideNotifications(value);
                    if (value && !notificationAccess) {
                      messenger.showSnackBar(
                        const SnackBar(
                          content: Text(
                            'Grant Notification access so OpenLock can hide '
                            'notification content.',
                          ),
                        ),
                      );
                    }
                  },
                ),
                SettingsTile(
                  icon: notificationAccess
                      ? Icons.check_circle_outline
                      : Icons.error_outline,
                  title: 'Notification access',
                  subtitle: notificationAccess
                      ? 'Granted'
                      : 'Required to hide notification content',
                  showChevron: false,
                  trailing: notificationAccess
                      ? null
                      : TextButton(
                          onPressed: () => ref
                              .read(permissionsControllerProvider.notifier)
                              .requestNotificationAccess(),
                          child: const Text('Grant'),
                        ),
                  onTap: notificationAccess
                      ? null
                      : () => ref
                          .read(permissionsControllerProvider.notifier)
                          .requestNotificationAccess(),
                ),
              ],
            ),
            const SectionLead('Security'),
            RowGroup(
              children: [
                SettingsTile(
                  icon: Icons.pin_outlined,
                  title: 'Change PIN',
                  onTap: () => context.push(AppRoutes.changePin),
                ),
                SettingsTile(
                  icon: Icons.key_outlined,
                  title: 'Recovery key',
                  subtitle: 'Resets your PIN if you forget it',
                  onTap: _recoveryKey,
                ),
                toggle(
                  icon: Icons.fingerprint,
                  title: 'Fingerprint unlock',
                  subtitle: biometricSupported
                      ? 'Unlock with your fingerprint'
                      : 'Not available on this device',
                  value: biometricEnabled,
                  onChanged: biometricSupported
                      ? (value) async {
                          final service = ref.read(biometricServiceProvider);
                          if (value) {
                            await service.enable();
                          } else {
                            await service.disable();
                          }
                          ref.invalidate(biometricEnabledProvider);
                          // Keep the native lock screen in sync with the choice.
                          await controller.pushToNative();
                        }
                      : null,
                ),
                toggle(
                  icon: Icons.app_blocking_outlined,
                  title: 'Prevent uninstall',
                  subtitle: preventUninstall
                      ? 'Uninstall needs your PIN'
                      : 'Require your PIN to uninstall',
                  value: preventUninstall,
                  onChanged: (value) async {
                    final security =
                        ref.read(preventUninstallControllerProvider.notifier);
                    if (value) {
                      await security.requestEnable();
                    } else {
                      final messenger = ScaffoldMessenger.of(context);
                      final lifted = await security
                          .disableWithAuth(_authenticateForDisable);
                      if (!lifted) {
                        messenger.showSnackBar(
                          const SnackBar(
                            content: Text(
                              'PIN or fingerprint required to turn off '
                              'uninstall protection.',
                            ),
                          ),
                        );
                      }
                    }
                  },
                ),
                if (preventUninstall)
                  SettingsTile(
                    icon: screenGuardAccess
                        ? Icons.check_circle_outline
                        : Icons.error_outline,
                    title: 'Uninstall screen guard',
                    subtitle: screenGuardAccess
                        ? 'PIN required on OpenLock\'s App info and uninstall '
                            'screens'
                        : 'Enable in Accessibility to guard OpenLock\'s App '
                            'info and uninstall screens',
                    showChevron: false,
                    trailing: screenGuardAccess
                        ? null
                        : TextButton(
                            onPressed: () => ref
                                .read(permissionsControllerProvider.notifier)
                                .requestScreenGuardAccess(),
                            child: const Text('Enable'),
                          ),
                    onTap: screenGuardAccess
                        ? null
                        : () => ref
                            .read(permissionsControllerProvider.notifier)
                            .requestScreenGuardAccess(),
                  ),
                if (preventUninstall || config.autoRestoreAccessibility)
                  toggle(
                    icon: Icons.restore_outlined,
                    title: 'Restore accessibility automatically',
                    subtitle: config.autoRestoreAccessibility &&
                            !(permissions?.writeSecureSettings ?? false)
                        ? 'ADB permission missing; automatic restore is paused'
                        : 'Restore the uninstall guard while protection and '
                            'Prevent uninstall are on. Turn this off before '
                            'disabling the service manually. Requires an ADB grant.',
                    value: config.autoRestoreAccessibility,
                    onChanged: _setAccessibilityRecovery,
                  ),
                toggle(
                  icon: Icons.shuffle,
                  title: 'Randomize keypad',
                  subtitle: 'Shuffle digits on the keypad',
                  value: config.randomizeKeypad,
                  onChanged: controller.setRandomizeKeypad,
                ),
              ],
            ),
            const SectionLead('Intruder detection'),
            RowGroup(
              children: [
                toggle(
                  icon: Icons.photo_camera_outlined,
                  title: 'Capture intruder photos',
                  subtitle:
                      'Photo after ${config.intruderThreshold} failed attempts',
                  value: config.intruderCaptureEnabled,
                  onChanged: (value) =>
                      controller.setIntruderCapture(enabled: value),
                ),
                SettingsTile(
                  icon: Icons.list_alt_outlined,
                  title: 'View intruder log',
                  onTap: () => context.go(AppRoutes.intruder),
                ),
              ],
            ),
            const SectionLead('Advanced'),
            RowGroup(
              children: [
                SettingsTile(
                  icon: Icons.developer_mode_outlined,
                  title: 'Shizuku',
                  subtitle: 'Manage the built-in Shizuku service',
                  onTap: _openShizuku,
                ),
                SettingsTile(
                  icon: Icons.terminal_outlined,
                  title: 'Shizuku terminal',
                  subtitle: 'Run device-shell commands and view their output',
                  onTap: _openTerminal,
                ),
                toggle(
                  icon: Icons.warning_amber_outlined,
                  title: 'Decoy cover',
                  subtitle: 'Fake "app stopped" dialog',
                  value: config.fakeCoverEnabled,
                  onChanged: controller.setFakeCover,
                ),
                SettingsTile(
                  icon: Icons.backup_outlined,
                  title: 'Backup & restore',
                  onTap: () => context.push(AppRoutes.backup),
                ),
                SettingsTile(
                  icon: Icons.bug_report_outlined,
                  title: '설정 보호 진단',
                  subtitle: '문제 화면을 재현하고 진단 보고서 복사',
                  onTap: () => showDialog<void>(
                    context: context,
                    builder: (_) => const GuardDiagnosticsDialog(),
                  ),
                ),
              ],
            ),
            const SectionLead('Updates'),
            RowGroup(
              children: [
                if (!kStoreBuild) const _UpdateTiles(),
                SettingsTile(
                  icon: Icons.info_outline,
                  title: 'About',
                  onTap: () => context.push(AppRoutes.about),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// "Check for updates on open" toggle + a manual "Check now", backed by
/// core_update. On by default; the only network call the app makes.
class _UpdateTiles extends ConsumerStatefulWidget {
  const _UpdateTiles();

  @override
  ConsumerState<_UpdateTiles> createState() => _UpdateTilesState();
}

class _UpdateTilesState extends ConsumerState<_UpdateTiles> {
  bool _checking = false;

  Future<void> _checkNow() async {
    setState(() => _checking = true);
    final info = await ref.read(updateServiceProvider).check();
    if (!mounted) return;
    setState(() => _checking = false);
    final messenger = ScaffoldMessenger.of(context);
    if (info == null) {
      messenger.showSnackBar(
        const SnackBar(content: Text("You're on the latest version.")),
      );
    } else {
      messenger.showSnackBar(
        SnackBar(
          content: Text('Update available · v${info.version}'),
          action: SnackBarAction(
            label: 'Update',
            onPressed: () => ref.read(updateServiceProvider).openDownload(info),
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final autoCheck = ref.watch(updateAutoCheckProvider).valueOrNull ?? true;
    Future<void> setAuto(bool v) async {
      await ref.read(secureStorageProvider).write(
            key: updateAutoCheckKey,
            value: v ? 'true' : 'false',
          );
      ref.invalidate(updateAutoCheckProvider);
      ref.invalidate(updateCheckProvider);
    }

    return Column(
      children: [
        SettingsTile(
          icon: Icons.system_update_alt,
          title: 'Auto-check for updates',
          subtitle: 'Checks GitHub, uploads nothing',
          showChevron: false,
          trailing: Switch(value: autoCheck, onChanged: setAuto),
          onTap: () => setAuto(!autoCheck),
        ),
        const Divider(height: 1, indent: 68),
        SettingsTile(
          icon: Icons.refresh,
          title: 'Check now',
          showChevron: false,
          trailing: _checking
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : null,
          onTap: _checking ? null : _checkNow,
        ),
      ],
    );
  }
}

/// Owns its controller until the closing route animation actually unmounts it.
class _PinConfirmationDialog extends StatefulWidget {
  const _PinConfirmationDialog({required this.prompt, required this.verifyPin});

  final String prompt;
  final Future<bool> Function(String) verifyPin;

  @override
  State<_PinConfirmationDialog> createState() => _PinConfirmationDialogState();
}

class _PinConfirmationDialogState extends State<_PinConfirmationDialog> {
  final _controller = TextEditingController();
  String? _error;
  bool _checking = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    if (_checking) return;
    setState(() {
      _checking = true;
      _error = null;
    });
    try {
      final ok = await widget.verifyPin(_controller.text);
      if (!mounted || ModalRoute.of(context)?.isCurrent != true) return;
      if (ok) {
        Navigator.pop(context, true);
      } else {
        setState(() {
          _checking = false;
          _error = 'Wrong PIN';
        });
      }
    } catch (_) {
      if (!mounted || ModalRoute.of(context)?.isCurrent != true) return;
      setState(() {
        _checking = false;
        _error = 'Could not verify PIN. Please try again.';
      });
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Confirm your PIN'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.prompt),
            const SizedBox(height: AppSpacing.md),
            AppTextField(
              controller: _controller,
              label: 'PIN',
              obscureText: true,
              autofocus: true,
              keyboardType: TextInputType.number,
              errorText: _error,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: _checking ? null : _confirm,
            child: const Text('Confirm'),
          ),
        ],
      );
}
