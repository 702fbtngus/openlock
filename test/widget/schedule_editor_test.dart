import 'package:core_theme/core_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/interfaces/enforcement_bridge.dart';
import 'package:openlock/src/features/enforcement/providers/config_providers.dart';
import 'package:openlock/src/features/schedules/models/lock_schedule.dart';
import 'package:openlock/src/features/schedules/screens/schedule_editor_screen.dart';

import '../helpers/fakes.dart';

void main() {
  for (final initiallyPhone in [false, true]) {
    testWidgets('editing phone mode $initiallyPhone preserves apps and enabled state',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(440, 1400);
      addTearDown(tester.view.reset);

      final container = ProviderContainer(overrides: [
        enforcementBridgeProvider.overrideWithValue(FakeEnforcementBridge(apps: [
          InstalledApp(packageName: 'com.alpha', label: 'Alpha', versionCode: 1),
        ])),
        secureStorageProvider.overrideWithValue(FakeSecureStorage()),
        configFileStoreProvider.overrideWithValue(FakeConfigFileStore()),
      ]);
      addTearDown(container.dispose);
      await container.read(configControllerProvider.future);
      await container.read(configControllerProvider.notifier).upsertSchedule(
            LockSchedule(
              id: 'night',
              name: 'Night',
              packages: {'com.alpha'},
              weekdays: {5},
              startMinutes: 1320,
              endMinutes: 360,
              enabled: false,
              lockPhone: initiallyPhone,
            ),
          );

      final router = GoRouter(routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: TextButton(
              onPressed: () => context.push('/edit'),
              child: const Text('Edit'),
            ),
          ),
        ),
        GoRoute(
          path: '/edit',
          builder: (_, __) => const ScheduleEditorScreen(scheduleId: 'night'),
        ),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          theme: AppTheme.build(Brightness.light, accent: AppAccents.openlock),
          routerConfig: router,
        ),
      ));
      await tester.tap(find.text('Edit'));
      await tester.pumpAndSettle();

      if (initiallyPhone) {
        expect(find.text('Alpha'), findsNothing);
      }
      await tester.tap(find.text('Entire phone'));
      await tester.pumpAndSettle();
      expect(find.text('Alpha'), findsNothing);
      expect(find.textContaining('cannot fully block Home'), findsOneWidget);
      await tester.tap(find.text('Selected apps'));
      await tester.pumpAndSettle();
      expect(tester.widget<Checkbox>(find.byType(Checkbox)).value, isTrue);

      // Save a mode change in both directions; hiding the picker must not
      // destroy its selections or re-enable a disabled schedule.
      if (!initiallyPhone) {
        await tester.tap(find.text('Entire phone'));
        await tester.pumpAndSettle();
      }
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      final saved = container.read(configControllerProvider).valueOrNull!
          .schedules.single;
      expect(saved.lockPhone, !initiallyPhone);
      expect(saved.enabled, isFalse);
      expect(saved.packages, {'com.alpha'});
    });
  }
}
