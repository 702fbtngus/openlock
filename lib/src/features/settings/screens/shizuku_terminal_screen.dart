import 'dart:async';

import 'package:core_theme/core_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:openlock/src/core/di.dart';
import 'package:openlock/src/core/interfaces/enforcement_bridge.dart';
import 'package:openlock/src/core/interfaces/shell_terminal.dart';
import 'package:uuid/uuid.dart';

/// Authenticated Settings opens this route directly. History lasts for this visit.
class ShizukuTerminalScreen extends ConsumerStatefulWidget {
  const ShizukuTerminalScreen({required this.reauthenticate, super.key});

  final Future<bool> Function() reauthenticate;

  @override
  ConsumerState<ShizukuTerminalScreen> createState() =>
      _ShizukuTerminalScreenState();
}

class _ShizukuTerminalScreenState extends ConsumerState<ShizukuTerminalScreen>
    with WidgetsBindingObserver {
  final _input = TextEditingController();
  final _focus = FocusNode();
  final _scroll = ScrollController();
  final _history = <_CommandRun>[];
  late final IEnforcementBridge _bridge;
  late final StreamSubscription<ShellEvent> _subscription;
  ShellStatus _status = const ShellStatus(connected: false);
  _CommandRun? _active;
  bool _checking = false;
  bool _stopping = false;
  bool _authorized = true;
  bool _authenticating = false;
  int _backgroundEpoch = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bridge = ref.read(enforcementBridgeProvider);
    _subscription = _bridge.shellEvents.listen(
      _onEvent,
      onError: (_) => _connectionLost(),
      onDone: _connectionLost,
    );
    _refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _backgroundEpoch++;
      setState(() => _authorized = false);
      if (_active != null) {
        unawaited(
            _bridge.cancelShellCommand(_active!.id).catchError((Object _) {}));
      }
    }
    if (state == AppLifecycleState.resumed) _resume();
  }

  Future<void> _resume() async {
    if (_authenticating) return;
    if (!_authorized) {
      _authenticating = true;
      final epoch = _backgroundEpoch;
      var confirmed = false;
      try {
        confirmed = await widget.reauthenticate();
      } catch (_) {
        // Returning to Settings leaves the same authentication gate on re-entry.
      } finally {
        _authenticating = false;
      }
      if (!mounted) return;
      if (epoch != _backgroundEpoch) {
        if (WidgetsBinding.instance.lifecycleState ==
            AppLifecycleState.resumed) {
          unawaited(_resume());
        }
        return;
      }
      setState(() => _authorized = confirmed);
      if (!confirmed) {
        await Navigator.of(context).maybePop();
        return;
      }
    }
    await _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (_active != null) {
      unawaited(
          _bridge.cancelShellCommand(_active!.id).catchError((Object _) {}));
    }
    unawaited(_subscription.cancel());
    _input.dispose();
    _focus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_checking) return;
    setState(() => _checking = true);
    try {
      final status = await _bridge.getShellStatus();
      if (mounted) setState(() => _status = status);
    } catch (_) {
      if (mounted) {
        setState(() => _status = const ShellStatus(
              connected: false,
              message: 'Could not reach Shizuku. Try refreshing.',
            ));
      }
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  void _connectionLost() {
    if (!mounted) return;
    final active = _active;
    if (active != null) {
      unawaited(
          _bridge.cancelShellCommand(active.id).catchError((Object _) {}));
    }
    setState(() {
      active?.outcome = 'error';
      active?.message = 'The terminal connection was interrupted.';
      _active = null;
      _status = const ShellStatus(connected: false);
    });
  }

  void _onEvent(ShellEvent event) {
    if (!mounted || event.id != _active?.id) return;
    final follow = !_scroll.hasClients ||
        _scroll.position.pixels >= _scroll.position.maxScrollExtent - 100;
    setState(() {
      final run = _active!;
      switch (event.type) {
        case 'output':
          if (event.stream == 'stderr') {
            run.stderr += event.text ?? '';
          } else {
            run.stdout += event.text ?? '';
          }
        case 'truncated':
          run.truncated = true;
        case 'complete':
          run.outcome = event.outcome ?? 'error';
          run.exitCode = event.exitCode;
          run.elapsedMs = event.elapsedMs;
          run.message = event.message;
          _active = null;
          _stopping = false;
      }
    });
    if (follow) _scrollToEnd();
    if (event.type == 'complete' && event.outcome == 'error') {
      unawaited(_refresh());
    }
  }

  Future<void> _run() async {
    final command = _input.text.trim();
    if (!_authorized ||
        _active != null ||
        !_status.connected ||
        command.isEmpty) {
      return;
    }
    final run = _CommandRun(const Uuid().v4(), command);
    setState(() {
      if (_history.length == 50) _history.removeAt(0);
      _history.add(run);
      _active = run;
      _input.clear();
    });
    _scrollToEnd();
    try {
      await _bridge.startShellCommand(id: run.id, command: command);
    } catch (error) {
      if (!mounted || _active?.id != run.id) return;
      setState(() {
        run.outcome = 'error';
        run.message = error is PlatformException
            ? error.message ?? 'Could not start the command.'
            : 'Could not start the command.';
        _active = null;
      });
      await _refresh();
    }
  }

  Future<void> _stop() async {
    final active = _active;
    if (active == null || _stopping) return;
    setState(() => _stopping = true);
    try {
      await _bridge.cancelShellCommand(active.id);
    } catch (_) {
      if (mounted) _message('Could not stop the command. Try again.');
    } finally {
      if (mounted) setState(() => _stopping = false);
    }
  }

  Future<void> _openManager() async {
    try {
      await _bridge.openShizuku();
    } catch (_) {
      if (mounted) _message('Could not open Shizuku.');
    }
  }

  void _message(String text) => ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(text)),
      );

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 180), curve: Curves.easeOut);
      }
    });
  }

  void _useCommand(String command) {
    _input.value = TextEditingValue(
      text: command,
      selection: TextSelection.collapsed(offset: command.length),
    );
    _focus.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final c = SuiteColors.of(context);
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;
    final busy = _active != null;
    final identity = _status.uid == 0 ? 'Root' : 'Shell';
    return Scaffold(
      appBar: AppBar(
        title: const Text('Shizuku terminal'),
        actions: [
          IconButton(
            tooltip: 'Open Shizuku manager',
            onPressed: _authorized ? _openManager : null,
            icon: const Icon(Icons.developer_mode_outlined),
          ),
          IconButton(
            tooltip: 'Clear session',
            onPressed: busy || _history.isEmpty
                ? null
                : () => setState(_history.clear),
            icon: const Icon(Icons.delete_sweep_outlined),
          ),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 920),
            child: Column(
              children: [
                Container(
                  margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: _status.connected ? c.accentContainer : c.surfaceAlt,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Row(
                    children: [
                      Icon(_status.connected ? Icons.terminal : Icons.link_off,
                          color:
                              _status.connected ? c.accent : c.textSecondary),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                                _checking
                                    ? 'Checking connection…'
                                    : _status.connected
                                        ? '$identity connected · UID ${_status.uid}'
                                        : 'Shizuku is offline',
                                maxLines: keyboardOpen ? 1 : 2,
                                overflow: TextOverflow.ellipsis,
                                style: AppTextStyles.body
                                    .copyWith(fontWeight: FontWeight.w600)),
                            if (!keyboardOpen)
                              Text(
                                  _status.connected
                                      ? 'Device-shell commands through Shizuku'
                                      : _status.message ??
                                          'Start the service in Shizuku, then refresh.',
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: AppTextStyles.bodySmall
                                      .copyWith(color: c.textSecondary)),
                          ],
                        ),
                      ),
                      IconButton(
                        tooltip: 'Refresh connection',
                        onPressed: _checking ? null : _refresh,
                        icon: const Icon(Icons.refresh),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: _history.isEmpty
                      ? _emptyState(c)
                      : ListView.separated(
                          controller: _scroll,
                          padding: const EdgeInsets.all(16),
                          itemCount: _history.length,
                          separatorBuilder: (_, __) =>
                              const SizedBox(height: 12),
                          itemBuilder: (_, index) => _CommandCard(
                            run: _history[index],
                            onUse: () => _useCommand(_history[index].command),
                          ),
                        ),
                ),
                Container(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                  decoration: BoxDecoration(
                    color: c.surface,
                    border: Border(top: BorderSide(color: c.hairline)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _input,
                              enabled: _authorized,
                              focusNode: _focus,
                              style: const TextStyle(
                                  fontFamily: AppTextStyles.monoFamily,
                                  fontSize: 14),
                              autocorrect: false,
                              enableSuggestions: false,
                              minLines: 1,
                              maxLines: keyboardOpen ? 3 : 4,
                              maxLength: 16384,
                              keyboardType: TextInputType.multiline,
                              decoration: InputDecoration(
                                labelText: 'Shell command',
                                hintText: 'id',
                                prefixText: r'$ ',
                                counterText: '',
                                filled: true,
                                fillColor: c.surfaceAlt,
                                border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(12)),
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          if (busy)
                            IconButton.filledTonal(
                              tooltip: 'Stop command',
                              onPressed: _stopping ? null : _stop,
                              icon: const Icon(Icons.stop_rounded),
                            )
                          else
                            IconButton.filled(
                              tooltip: 'Run command',
                              onPressed: _authorized && _status.connected
                                  ? _run
                                  : null,
                              icon: const Icon(Icons.arrow_upward_rounded),
                            ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                          keyboardOpen
                              ? 'Fresh shell in / · 2 min limit'
                              : 'Fresh shell in / · 2 min limit · History stays in this session',
                          maxLines: keyboardOpen ? 1 : null,
                          overflow: keyboardOpen ? TextOverflow.ellipsis : null,
                          style: AppTextStyles.bodySmall
                              .copyWith(color: c.textTertiary)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _emptyState(SuiteColors c) => SingleChildScrollView(
        primary: false,
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.code_rounded, size: 48, color: c.accent),
            const SizedBox(height: 16),
            const Text('Run a shell command',
                textAlign: TextAlign.center, style: AppTextStyles.h3),
            const SizedBox(height: 8),
            Text(
                'Enter the command you would run after adb shell.\nTry a read-only command to check the connection.',
                textAlign: TextAlign.center,
                style:
                    AppTextStyles.bodySmall.copyWith(color: c.textSecondary)),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                for (final command in [
                  'id',
                  'getprop ro.product.model',
                  'pm list packages'
                ])
                  ActionChip(
                      label: Text(command),
                      onPressed: () => _useCommand(command)),
              ],
            ),
          ],
        ),
      );
}

class _CommandRun {
  _CommandRun(this.id, this.command);
  final String id;
  final String command;
  String stdout = '';
  String stderr = '';
  String? outcome;
  int? exitCode;
  int? elapsedMs;
  String? message;
  bool truncated = false;
}

String _plainOutput(String text) => text
    .replaceAll(RegExp(r'\x1b\[[0-?]*[ -/]*[@-~]'), '')
    .replaceAll(RegExp(r'\x1b\][^\x07]*(?:\x07|\x1b\\)'), '')
    .replaceAll('\r\n', '\n')
    .replaceAll('\r', '\n');

class _CommandCard extends StatelessWidget {
  const _CommandCard({required this.run, required this.onUse});
  final _CommandRun run;
  final VoidCallback onUse;

  @override
  Widget build(BuildContext context) {
    final c = SuiteColors.of(context);
    final running = run.outcome == null;
    final success = run.outcome == 'finished' && run.exitCode == 0;
    final color = running
        ? c.accent
        : success
            ? c.positive
            : c.warning;
    final label = switch (run.outcome) {
      null => 'Running…',
      'cancelled' => 'Stopped',
      'timedOut' => 'Timed out',
      'finished' => 'Exit ${run.exitCode}',
      _ => 'Could not complete',
    };
    final stdout = _plainOutput(run.stdout);
    final stderr = _plainOutput(run.stderr);
    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.hairline),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            color: c.surfaceAlt,
            padding: const EdgeInsets.fromLTRB(14, 6, 4, 6),
            child: Row(
              children: [
                Text(r'$ ',
                    style: TextStyle(
                        color: c.accent, fontFamily: AppTextStyles.monoFamily)),
                Expanded(
                    child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 120),
                  child: SingleChildScrollView(
                    primary: false,
                    child: SelectableText(run.command,
                        style: const TextStyle(
                            fontFamily: AppTextStyles.monoFamily,
                            fontSize: 13,
                            height: 1.5)),
                  ),
                )),
                IconButton(
                    tooltip: 'Use command',
                    onPressed: onUse,
                    icon: const Icon(Icons.keyboard_return, size: 18)),
                IconButton(
                  tooltip: 'Copy command and output',
                  icon: const Icon(Icons.copy_all_outlined, size: 18),
                  onPressed: () => Clipboard.setData(ClipboardData(
                    text:
                        '\$ ${run.command}\n$stdout${stderr.isEmpty ? '' : '\nSTDERR\n$stderr'}\n$label',
                  )),
                ),
              ],
            ),
          ),
          if (stdout.isNotEmpty || stderr.isNotEmpty)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 320),
              child: SingleChildScrollView(
                primary: false,
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (stdout.isNotEmpty)
                      _OutputBlock('STDOUT', stdout, c.textPrimary),
                    if (stderr.isNotEmpty)
                      _OutputBlock('STDERR', stderr, c.warning),
                  ],
                ),
              ),
            ),
          if (run.message != null)
            Padding(
                padding: const EdgeInsets.all(14),
                child: Text(run.message!, style: TextStyle(color: c.negative))),
          if (run.truncated)
            const Padding(
                padding: EdgeInsets.fromLTRB(14, 8, 14, 0),
                child: Text('Output limited to 65,536 characters.')),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Wrap(
              spacing: 8,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                if (running)
                  SizedBox(
                      width: 10,
                      height: 10,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: color))
                else
                  Icon(
                      success ? Icons.check_circle_outline : Icons.info_outline,
                      size: 14,
                      color: color),
                Text(label,
                    style: AppTextStyles.bodySmall.copyWith(color: color)),
                if (run.elapsedMs != null)
                  Text('${(run.elapsedMs! / 1000).toStringAsFixed(2)} s',
                      style: AppTextStyles.bodySmall
                          .copyWith(color: c.textTertiary)),
                if (!running &&
                    stdout.isEmpty &&
                    stderr.isEmpty &&
                    run.message == null)
                  Text('No output',
                      style: AppTextStyles.bodySmall
                          .copyWith(color: c.textTertiary)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _OutputBlock extends StatelessWidget {
  const _OutputBlock(this.label, this.text, this.color);
  final String label;
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(label,
                style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1,
                    color: SuiteColors.of(context).textTertiary)),
            const SizedBox(height: 4),
            SelectableText(text,
                style: TextStyle(
                    fontFamily: AppTextStyles.monoFamily,
                    fontSize: 13,
                    height: 1.5,
                    color: color)),
          ],
        ),
      );
}
