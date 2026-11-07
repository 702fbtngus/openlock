import 'dart:async';

import 'package:core_theme/core_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/interfaces/shell_terminal.dart';
import 'package:openlock/src/features/settings/screens/shizuku_terminal_screen.dart';

import '../helpers/fakes.dart';

void main() {
  Future<void> showTerminal(WidgetTester tester, FakeEnforcementBridge bridge,
      {double textScale = 1,
      Brightness brightness = Brightness.light,
      Future<bool> Function()? reauthenticate}) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [enforcementBridgeProvider.overrideWithValue(bridge)],
      child: MaterialApp(
        theme: AppTheme.build(brightness, accent: AppAccents.openlock),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: ShizukuTerminalScreen(
            reauthenticate: reauthenticate ?? () async => true),
      ),
    ));
    await tester.pumpAndSettle();
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await bridge.terminalEvents.close();
    });
  }

  testWidgets('streams stdout and stderr and displays exit status',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    await showTerminal(tester, bridge);
    expect(find.text('Shell connected · UID 2000'), findsOneWidget);
    await tester.enterText(
        find.byType(TextField), 'printf hello; printf error >&2');
    await tester.tap(find.byTooltip('Run command'));
    await tester.pump();
    final command = bridge.shellCommands.single;
    expect(command.command, 'printf hello; printf error >&2');
    bridge.terminalEvents.add(ShellEvent(
        id: command.id, type: 'output', stream: 'stdout', text: 'hello'));
    bridge.terminalEvents.add(ShellEvent(
        id: command.id, type: 'output', stream: 'stderr', text: 'error'));
    await tester.pump();
    expect(find.text('hello'), findsOneWidget);
    expect(find.text('error'), findsOneWidget);
    expect(find.text('Running…'), findsOneWidget);
    bridge.terminalEvents.add(ShellEvent(
        id: command.id,
        type: 'complete',
        outcome: 'finished',
        exitCode: 7,
        elapsedMs: 123));
    await tester.pumpAndSettle();
    expect(find.text('Exit 7'), findsOneWidget);
    expect(find.text('0.12 s'), findsOneWidget);
    expect(find.byTooltip('Run command'), findsOneWidget);
  });

  testWidgets('stops commands, reports truncation and allows reuse',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    await showTerminal(tester, bridge);
    await tester.enterText(find.byType(TextField), 'logcat');
    await tester.tap(find.byTooltip('Run command'));
    await tester.pump();
    final id = bridge.shellCommands.single.id;
    bridge.terminalEvents.add(ShellEvent(id: id, type: 'truncated'));
    await tester.tap(find.byTooltip('Stop command'));
    await tester.pump();
    expect(bridge.cancelledCommands, contains(id));
    bridge.terminalEvents
        .add(ShellEvent(id: id, type: 'complete', outcome: 'cancelled'));
    await tester.pumpAndSettle();
    expect(find.text('Stopped'), findsOneWidget);
    expect(find.text('Output limited to 65,536 characters.'), findsOneWidget);
    await tester.tap(find.byTooltip('Use command'));
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'logcat');
    await tester.tap(find.byTooltip('Clear session'));
    await tester.pumpAndSettle();
    expect(find.text('Run a shell command'), findsOneWidget);
  });

  testWidgets('offline state disables execution and opens manager',
      (tester) async {
    final bridge = FakeEnforcementBridge()
      ..shellStatus = const ShellStatus(connected: false);
    await showTerminal(tester, bridge);
    expect(find.text('Shizuku is offline'), findsOneWidget);
    final runButton = find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == 'Run command');
    expect(tester.widget<IconButton>(runButton).onPressed, isNull);
    await tester.tap(find.byTooltip('Open Shizuku manager'));
    await tester.pump();
    expect(bridge.shizukuOpened, isTrue);
    expect(bridge.shellCommands, isEmpty);
  });

  testWidgets('leaving the screen cancels the active command', (tester) async {
    final bridge = FakeEnforcementBridge();
    await showTerminal(tester, bridge);
    await tester.enterText(find.byType(TextField), 'sleep 60');
    await tester.tap(find.byTooltip('Run command'));
    await tester.pump();
    await tester.pumpWidget(const SizedBox.shrink());
    expect(bridge.cancelledCommands, contains(bridge.shellCommands.single.id));
  });

  testWidgets('backgrounding cancels execution and resuming reauthenticates',
      (tester) async {
    final bridge = FakeEnforcementBridge();
    var confirmations = 0;
    await showTerminal(tester, bridge, reauthenticate: () async {
      confirmations++;
      return false;
    });
    await tester.enterText(find.byType(TextField), 'sleep 60');
    await tester.tap(find.byTooltip('Run command'));
    await tester.pump();
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pump();
    expect(bridge.cancelledCommands, contains(bridge.shellCommands.single.id));
    bridge.terminalEvents.add(ShellEvent(
        id: bridge.shellCommands.single.id,
        type: 'complete',
        outcome: 'cancelled'));
    await tester.pump();
    for (final state in [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pumpAndSettle();
    expect(confirmations, 1);
    final button = find.byWidgetPredicate(
        (widget) => widget is IconButton && widget.tooltip == 'Run command');
    expect(tester.widget<IconButton>(button).onPressed, isNull);
  });

  testWidgets(
      'confirmation from a previous foreground visit cannot reopen terminal',
      (tester) async {
    final confirmation = Completer<bool>();
    var attempts = 0;
    await showTerminal(tester, FakeEnforcementBridge(), reauthenticate: () {
      attempts++;
      return attempts == 1 ? confirmation.future : Future.value(false);
    });
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    confirmation.complete(true);
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
    for (final state in [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
    }
    await tester.pumpAndSettle();
    expect(attempts, 2);
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
  });

  testWidgets('narrow dark layout supports large text', (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await showTerminal(tester, FakeEnforcementBridge(),
        textScale: 1.8, brightness: Brightness.dark);
    expect(tester.takeException(), isNull);
  });

  testWidgets('command input fits a narrow screen with keyboard and large text',
      (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await showTerminal(tester, FakeEnforcementBridge(), textScale: 1.8);
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('Run command'), findsOneWidget);
  });

  test('terminal event decoding retains outcome and timing', () {
    final event = ShellEvent.fromMap({
      'id': 'command',
      'type': 'complete',
      'outcome': 'timedOut',
      'elapsedMs': 120000
    });
    expect(event.outcome, 'timedOut');
    expect(event.elapsedMs, 120000);
    expect(ShellStatus.fromMap({'connected': true, 'uid': 0}).uid, 0);
  });
}
