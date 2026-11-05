import 'package:core_theme/core_theme.dart';
import 'package:core_ui/core_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:openlock/src/core/interfaces/enforcement_bridge.dart';
import 'package:openlock/src/core/router/app_router.dart';
import 'package:openlock/src/core/widgets/screen_header.dart';
import 'package:openlock/src/features/onboarding/providers/permissions_providers.dart';

/// Guided permissions checklist. Each item shows its live status and a button
/// that opens the right system settings screen. A banner reflects whether
/// protection is actually active.
class PermissionsScreen extends ConsumerStatefulWidget {
  const PermissionsScreen({super.key});

  @override
  ConsumerState<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends ConsumerState<PermissionsScreen>
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
    // Re-check after returning from a system settings screen.
    if (state == AppLifecycleState.resumed) {
      ref.read(permissionsControllerProvider.notifier).refresh();
    }
  }

  Future<void> _explainRestrictedSettings() async {
    final openAppInfo = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Allow restricted settings'),
        content: const Text(
          'Android may restrict sensitive permissions for apps installed from '
          'an APK. Allow restricted settings before activating device '
          'administrator access if Android asks you to.\n\n'
          '1. On the App info screen, tap the three-dot menu in the top right.\n'
          '2. Tap "Allow restricted settings" and confirm with your device '
          'PIN or fingerprint.\n'
          '3. Return to OpenLock and grant Device administrator.\n\n'
          'If this option is missing or already allowed, continue with Device '
          'administrator. The menu varies by device. Android does not let '
          'OpenLock verify this step automatically.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Got it'),
          ),
        ],
      ),
    );
    if (!mounted || openAppInfo != true) return;
    await ref.read(permissionsControllerProvider.notifier).requestAppInfo();
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(permissionsControllerProvider);
    final controller = ref.read(permissionsControllerProvider.notifier);
    final states = async.valueOrNull ?? const PermissionStates.unknown();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Set up protection'),
        actions: [
          IconButton(
            onPressed: controller.refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: PageList(
          children: [
            _StatusBanner(states: states),
            const SizedBox(height: AppSpacing.md),
            RowGroup(
              children: [
                _PermissionTile(
                  icon: Icons.app_settings_alt_rounded,
                  title: 'Allow restricted settings',
                  subtitle: states.restrictedSettingsAllowed == true
                      ? 'This Android version does not require this step.'
                      : 'If required, allow this in App info before granting '
                          'Device administrator. Check manually in Settings.',
                  granted: states.restrictedSettingsAllowed == true,
                  required: false,
                  onFix: _explainRestrictedSettings,
                ),
                _PermissionTile(
                  icon: Icons.admin_panel_settings_rounded,
                  title: 'Device administrator',
                  subtitle:
                      'Protects OpenLock from being uninstalled while active.',
                  granted: states.deviceAdmin,
                  required: false,
                  onFix: controller.requestDeviceAdmin,
                ),
                _PermissionTile(
                  icon: Icons.query_stats_rounded,
                  title: 'Usage access',
                  subtitle:
                      'Lets OpenLock notice which app is open so it can lock it.',
                  granted: states.usageAccess,
                  required: true,
                  onFix: controller.requestUsageAccess,
                ),
                _PermissionTile(
                  icon: Icons.layers_rounded,
                  title: 'Display over other apps',
                  subtitle:
                      'Lets the lock screen appear on top of a locked app.',
                  granted: states.overlay,
                  required: true,
                  onFix: controller.requestOverlay,
                ),
                _PermissionTile(
                  icon: Icons.battery_saver_rounded,
                  title: 'Ignore battery optimization',
                  subtitle:
                      'Keeps the guard running reliably in the background.',
                  granted: states.batteryExempt,
                  required: false,
                  onFix: controller.requestBattery,
                ),
                _PermissionTile(
                  icon: Icons.notifications_rounded,
                  title: 'Notifications',
                  subtitle: 'Shows the ongoing "protection active" notice.',
                  granted: states.notifications,
                  required: false,
                  onFix: controller.requestNotifications,
                ),
                _PermissionTile(
                  icon: Icons.notifications_off_rounded,
                  title: 'Notification access',
                  subtitle:
                      'Lets OpenLock hide locked apps\' notification content.',
                  granted: states.notificationAccess,
                  required: false,
                  onFix: controller.requestNotificationAccess,
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.lg),
            if (states.canEnforce && !states.serviceRunning)
              VaultButton(
                label: 'Turn on protection',
                onPressed: controller.startService,
              )
            else if (states.serviceRunning)
              VaultButton(
                label: 'Continue',
                onPressed: () => context.go(AppRoutes.apps),
              )
            else
              VaultButton(
                label: 'Continue anyway',
                variant: VaultButtonVariant.secondary,
                onPressed: () => context.go(AppRoutes.apps),
              ),
          ],
        ),
      ),
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.states});

  final PermissionStates states;

  @override
  Widget build(BuildContext context) {
    final active = states.protectionActive;
    return AppBanner(
      kind: active ? BannerKind.success : BannerKind.warning,
      icon: active ? Icons.verified_user_rounded : Icons.gpp_maybe_rounded,
      title: active ? 'Protection active' : 'Protection not active yet',
      message: active
          ? 'OpenLock is guarding your locked apps.'
          : 'Grant the required permissions below to start.',
    );
  }
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.granted,
    required this.required,
    required this.onFix,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool granted;
  final bool required;
  final VoidCallback onFix;

  @override
  Widget build(BuildContext context) {
    final c = SuiteColors.of(context);
    return DetailRow(
      leading: IconBadge(icon: icon, color: granted ? c.positive : null),
      title: title,
      detail: required ? 'Required. $subtitle' : subtitle,
      trailing: granted
          ? Icon(Icons.check_circle_rounded, color: c.positive)
          : TextButton(onPressed: onFix, child: const Text('Grant')),
    );
  }
}
