import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/interfaces/enforcement_bridge.dart';
import 'package:openlock/src/features/enforcement/providers/config_providers.dart';
import 'package:openlock/src/features/settings/providers/settings_providers.dart';

/// Live snapshot of the special-access permissions the monitor service needs.
/// Refreshed whenever the user returns from a system settings screen.
final class PermissionsController extends AsyncNotifier<PermissionStates> {
  bool _awaitingDeviceAdmin = false;

  @override
  Future<PermissionStates> build() =>
      ref.watch(enforcementBridgeProvider).getPermissionStates();

  Future<void> refresh() async {
    state = const AsyncLoading<PermissionStates>().copyWithPrevious(state);
    state = await AsyncValue.guard(
      () async {
        if (_awaitingDeviceAdmin) {
          await ref.read(configControllerProvider.future);
          await ref
              .read(preventUninstallControllerProvider.notifier)
              .refresh();
          _awaitingDeviceAdmin = false;
        }
        return _bridge.getPermissionStates();
      },
    );
  }

  IEnforcementBridge get _bridge => ref.read(enforcementBridgeProvider);

  Future<void> requestUsageAccess() => _bridge.requestUsageAccess();
  Future<void> requestAppInfo() => _bridge.requestAppInfo();

  Future<void> requestDeviceAdmin() async {
    // Open Android first. Saving/enabling protection before activation can
    // delay this request or cause the guard to cover its own activation page.
    // Resume refresh syncs the config with Android's actual decision.
    _awaitingDeviceAdmin = true;
    try {
      await _bridge.requestDeviceAdmin();
    } catch (_) {
      _awaitingDeviceAdmin = false;
      rethrow;
    }
  }
  Future<void> requestOverlay() => _bridge.requestOverlayPermission();
  Future<void> requestBattery() => _bridge.requestBatteryExemption();
  Future<void> requestNotifications() =>
      _bridge.requestNotificationPermission();
  Future<void> requestNotificationAccess() =>
      _bridge.requestNotificationAccess();
  Future<void> requestScreenGuardAccess() => _bridge.requestScreenGuardAccess();

  Future<void> startService() async {
    await _bridge.startService();
    await refresh();
  }

  Future<void> stopService() async {
    await _bridge.stopService();
    await refresh();
  }
}

final permissionsControllerProvider =
    AsyncNotifierProvider<PermissionsController, PermissionStates>(
  PermissionsController.new,
);
