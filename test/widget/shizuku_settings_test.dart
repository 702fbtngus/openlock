import 'package:core_theme/core_theme.dart';
import 'package:core_update/core_update.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/features/enforcement/providers/config_providers.dart';
import 'package:openlock/src/features/settings/screens/settings_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../helpers/fakes.dart';

class _NoUpdates implements IUpdateService {
  @override
  Future<UpdateInfo?> check() async => null;
  @override
  Future<bool> openDownload(UpdateInfo info) async => true;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<void> showSettings(
    WidgetTester tester,
    FakeEnforcementBridge bridge,
  ) async {
    final appearance = (await tester.runAsync(AppearanceController.load))!;
    final container = ProviderContainer(overrides: [
      appearanceProvider.overrideWithValue(appearance),
      enforcementBridgeProvider.overrideWithValue(bridge),
      secureStorageProvider.overrideWithValue(FakeSecureStorage()),
      configFileStoreProvider.overrideWithValue(FakeConfigFileStore()),
      biometricAuthProvider.overrideWithValue(
        FakeBiometricAuth(available: false),
      ),
      keyDerivationProvider.overrideWithValue(const FakeKeyDerivation()),
      updateServiceProvider.overrideWithValue(_NoUpdates()),
    ]);
    addTearDown(container.dispose);
    await tester.runAsync(() async {
      await container.read(pinAuthServiceProvider).setupPin('135790');
      await container.read(configControllerProvider.future);
    });
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: AppTheme.build(Brightness.light, accent: AppAccents.openlock),
        home: const SettingsScreen(),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Shizuku'), 400);
    await tester.tap(find.text('Shizuku'));
    await tester.pumpAndSettle();
  }

  Future<void> confirmPin(WidgetTester tester) async {
    // Wait for the actual async verifier outside Flutter's fake clock.
    final dynamic action = tester
        .widget<FilledButton>(find.widgetWithText(FilledButton, 'Confirm'))
        .onPressed;
    await tester.runAsync(() async => await action());
    await tester.pumpAndSettle();
  }

  testWidgets('canceling PIN does not open the native Shizuku manager',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    await showSettings(tester, bridge);
    expect(find.text('Confirm your PIN'), findsOneWidget);
    expect(bridge.shizukuOpened, isFalse);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(bridge.shizukuOpened, isFalse);
  });

  testWidgets('wrong PIN is rejected and correct PIN opens Shizuku',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    await showSettings(tester, bridge);
    await tester.enterText(find.byType(TextField), '000000');
    await confirmPin(tester);
    expect(find.text('Wrong PIN'), findsOneWidget);
    expect(bridge.shizukuOpened, isFalse);
    await tester.enterText(find.byType(TextField), '135790');
    await confirmPin(tester);
    expect(bridge.shizukuOpened, isTrue);
  });
}
