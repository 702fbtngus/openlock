import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:openlock/src/core/di.dart';

/// User-started local capture, available in signed CI builds as well as previews.
class GuardDiagnosticsDialog extends ConsumerStatefulWidget {
  const GuardDiagnosticsDialog({super.key});

  @override
  ConsumerState<GuardDiagnosticsDialog> createState() =>
      _GuardDiagnosticsDialogState();
}

class _GuardDiagnosticsDialogState extends ConsumerState<GuardDiagnosticsDialog> {
  String _report = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _read();
  }

  Future<void> _read({bool? capture, bool copy = false}) async {
    setState(() => _busy = true);
    try {
      final bridge = ref.read(enforcementBridgeProvider);
      if (capture != null) {
        await bridge.setGuardDiagnosticsEnabled(enabled: capture);
      }
      final report = await bridge.getGuardDiagnostics();
      if (copy) await Clipboard.setData(ClipboardData(text: report));
      if (!mounted) return;
      setState(() => _report = report);
      if (copy) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('진단 내용을 복사했습니다.')),
        );
      }
    } on Exception catch (error) {
      if (mounted) setState(() => _report = '진단을 읽지 못했습니다: $error');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('설정 보호 진단'),
        scrollable: true,
        content: SizedBox(
          width: 560,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '진단 시작 → 닫기 → 문제 설정 화면 재현 → 여기로 돌아와 '
                '보고서 복사 순서로 진행하세요.\n\n'
                '설정 화면의 문구와 구조를 기기 메모리에 최대 10분간 수집합니다. '
                '자동 전송되지 않습니다. 공유 전 개인정보를 확인하세요. '
                '진단 시작은 잠금 정책과 인증 세션을 바꾸지 않습니다.',
              ),
              const SizedBox(height: 12),
              if (_busy) const LinearProgressIndicator(),
              SizedBox(
                height: 200,
                child: SingleChildScrollView(child: SelectableText(_report)),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: _busy ? null : () => _read(capture: true),
            child: const Text('진단 시작'),
          ),
          TextButton(
            onPressed: _busy ? null : () => _read(capture: false),
            child: const Text('수집 중지·새로고침'),
          ),
          TextButton(
            onPressed: _busy ? null : () => _read(capture: false, copy: true),
            child: const Text('보고서 복사'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('닫기'),
          ),
        ],
      );
}
