/// Live state of the embedded privileged server.
final class ShellStatus {
  const ShellStatus({required this.connected, this.uid, this.message});

  factory ShellStatus.fromMap(Map<Object?, Object?> map) => ShellStatus(
        connected: map['connected'] == true,
        uid: (map['uid'] as num?)?.toInt(),
        message: map['message'] as String?,
      );

  final bool connected;
  final int? uid;
  final String? message;
}

/// Output and completion updates for one user-started command.
final class ShellEvent {
  const ShellEvent({
    required this.id,
    required this.type,
    this.stream,
    this.text,
    this.outcome,
    this.exitCode,
    this.elapsedMs,
    this.message,
  });

  factory ShellEvent.fromMap(Map<Object?, Object?> map) => ShellEvent(
        id: map['id'] as String,
        type: map['type'] as String,
        stream: map['stream'] as String?,
        text: map['text'] as String?,
        outcome: map['outcome'] as String?,
        exitCode: (map['exitCode'] as num?)?.toInt(),
        elapsedMs: (map['elapsedMs'] as num?)?.toInt(),
        message: map['message'] as String?,
      );

  final String id;
  final String type;
  final String? stream;
  final String? text;
  final String? outcome;
  final int? exitCode;
  final int? elapsedMs;
  final String? message;
}
