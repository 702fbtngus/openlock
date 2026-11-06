import 'package:core_theme/core_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/interfaces/enforcement_bridge.dart';
import 'package:openlock/src/core/widgets/screen_header.dart';
import 'package:openlock/src/features/enforcement/providers/config_providers.dart';
import 'package:openlock/src/features/onboarding/screens/onboarding_screen.dart';
import 'package:openlock/src/features/onboarding/screens/permissions_screen.dart';

import '../helpers/fakes.dart';

Widget _wrap(Widget child, {List<Override> overrides = const []}) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp(
      theme: AppTheme.build(Brightness.light, accent: AppAccents.openlock),
      home: child,
    ),
  );
}

void main() {
  testWidgets('onboarding renders the first intro page', (tester) async {
    await tester.pumpWidget(_wrap(const OnboardingScreen()));
    expect(find.text('Lock the apps that matter'), findsOneWidget);
    expect(find.text('Next'), findsOneWidget);
  });

  testWidgets('permissions checklist renders each item + status banner',
      (tester) async {
    final bridge = FakeEnforcementBridge(
      permissions: const PermissionStates(
        usageAccess: false,
        overlay: false,
        notifications: false,
        notificationAccess: false,
        screenGuardAccess: false,
        batteryExempt: false,
        serviceRunning: false,
      ),
    );
    await tester.pumpWidget(
      _wrap(
        const PermissionsScreen(),
        overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Protection not active yet'), findsOneWidget);
    expect(find.text('Allow restricted settings'), findsNothing);
    expect(find.text('Blocked by restricted settings?'), findsOneWidget);
    expect(find.byType(DetailRow), findsNWidgets(6));
    expect(find.text('Device administrator'), findsOneWidget);
    expect(find.text('Usage access'), findsOneWidget);
    expect(find.text('Display over other apps'), findsOneWidget);
    expect(find.text('Ignore battery optimization'), findsOneWidget);
    // Two required permissions ungranted → two "Grant" buttons at least.
    expect(find.text('Grant'), findsWidgets);
  });

  testWidgets('permissions banner shows active when everything is granted',
      (tester) async {
    final bridge = FakeEnforcementBridge(); // all-granted defaults
    await tester.pumpWidget(
      _wrap(
        const PermissionsScreen(),
        overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Protection active'), findsOneWidget);
  });

  testWidgets('restricted settings explains before opening App info',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    await tester.pumpWidget(_wrap(
      const PermissionsScreen(),
      overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
    ));
    await tester.pumpAndSettle();
    final row = find.ancestor(
      of: find.text('Device administrator'),
      matching: find.byType(DetailRow),
    );
    final help = find.descendant(
      of: row, matching: find.text('Blocked by restricted settings?'));
    await tester.ensureVisible(help);
    await tester.tap(help);
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.textContaining('Tap Grant for Device administrator first.'), findsOneWidget);
    expect(bridge.appInfoRequested, isFalse);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(bridge.appInfoRequested, isFalse);

    await tester.tap(help);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Open App info'));
    await tester.pumpAndSettle();
    expect(bridge.appInfoRequested, isTrue);
    // Help opens only App info; it does not request or grant device admin.
    expect(bridge.deviceAdminRequested, isFalse);
    expect(find.descendant(of: row, matching: find.text('Grant')), findsOneWidget);
  });

  testWidgets('device admin launch failure shows feedback and help',
      (tester) async {
    final bridge = FakeEnforcementBridge()
      ..deviceAdminRequestError = PlatformException(
        code: 'device_admin_launch_failed',
        message: 'Android could not open device administrator setup.',
      );
    // No config/storage overrides: launching must reach Android directly.
    await tester.pumpWidget(_wrap(
      const PermissionsScreen(),
      overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
    ));
    await tester.pumpAndSettle();
    final row = find.ancestor(
      of: find.text('Device administrator'), matching: find.byType(DetailRow));
    final grant = find.descendant(of: row, matching: find.text('Grant'));
    await tester.ensureVisible(grant);
    await tester.tap(grant);
    await tester.pumpAndSettle();
    expect(bridge.deviceAdminRequested, isTrue);
    expect(find.text('Android could not open device administrator setup.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Help'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
  });

  for (final activated in [false, true]) {
    testWidgets('device admin refresh handles activation=$activated',
        (tester) async {
      final bridge = FakeEnforcementBridge();
      final container = ProviderContainer(overrides: [
        enforcementBridgeProvider.overrideWithValue(bridge),
        secureStorageProvider.overrideWithValue(FakeSecureStorage()),
        configFileStoreProvider.overrideWithValue(FakeConfigFileStore()),
        clockProvider.overrideWithValue(FakeClock(DateTime(2026, 7, 9))),
        biometricAuthProvider
            .overrideWithValue(FakeBiometricAuth(available: false)),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: AppTheme.build(Brightness.light, accent: AppAccents.openlock),
          home: const PermissionsScreen(),
        ),
      ));
      await tester.pumpAndSettle();
      final row = find.ancestor(
        of: find.text('Device administrator'),
        matching: find.byType(DetailRow),
      );
      final grant = find.descendant(of: row, matching: find.text('Grant'));
      await tester.ensureVisible(grant);
      await tester.tap(grant);
      await tester.pumpAndSettle();
      expect(bridge.deviceAdminRequested, isTrue);
      expect(
        container.read(configControllerProvider).valueOrNull?.preventUninstall ?? false,
        isFalse,
      );

      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      bridge.deviceAdminActive = activated;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(
        container.read(configControllerProvider).valueOrNull!.preventUninstall,
        activated,
      );
      expect(grant, activated ? findsNothing : findsOneWidget);
    });
  }
}
