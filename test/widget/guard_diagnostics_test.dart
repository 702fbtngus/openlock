import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/features/settings/screens/guard_diagnostics_dialog.dart';

import '../helpers/fakes.dart';

void main() {
  testWidgets('capture requires explicit start and copying stops it',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    String? clipboard;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          clipboard = (call.arguments as Map<Object?, Object?>)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    await tester.pumpWidget(
      ProviderScope(
        overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
        child: const MaterialApp(
          home: Scaffold(body: GuardDiagnosticsDialog()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(bridge.guardDiagnosticsEnabled, isFalse);
    expect(find.text(bridge.guardDiagnosticsReport), findsOneWidget);

    await tester.tap(find.text('진단 시작'));
    await tester.pumpAndSettle();
    expect(bridge.guardDiagnosticsEnabled, isTrue);

    await tester.tap(find.text('보고서 복사'));
    await tester.pumpAndSettle();
    expect(bridge.guardDiagnosticsEnabled, isFalse);
    expect(clipboard, bridge.guardDiagnosticsReport);
  });
}
