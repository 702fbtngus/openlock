import 'package:uuid/uuid.dart';

/// A recurring time window for selected apps or a phone-wide PIN requirement.
///
/// Times are stored as minutes-since-midnight in the device's *local* wall
/// clock, and weekdays follow Dart's `DateTime.weekday` (1 = Monday … 7 =
/// Sunday). A window whose [endMinutes] is less than or equal to [startMinutes]
/// is treated as spanning midnight (an overnight window).
final class LockSchedule {
  LockSchedule({
    String? id,
    required this.name,
    required this.packages,
    required this.weekdays,
    required this.startMinutes,
    required this.endMinutes,
    this.enabled = true,
    this.lockPhone = false,
  }) : id = id ?? const Uuid().v4();

  final String id;
  final String name;

  /// Package names locked while this window is active.
  final Set<String> packages;

  /// Active weekdays, 1 (Mon) … 7 (Sun).
  final Set<int> weekdays;

  /// Minutes since local midnight, 0–1439.
  final int startMinutes;
  final int endMinutes;

  final bool enabled;

  /// Require a phone-wide PIN instead of locking a fixed list of apps.
  /// This is an app-level lock, not Android kiosk mode.
  final bool lockPhone;

  /// A window that wraps past midnight (e.g. 22:00 → 06:00). A zero-length
  /// window (start == end) is treated as "all day", not overnight.
  bool get isOvernight => endMinutes < startMinutes;

  /// True when start == end, meaning the window covers the whole day on its
  /// active weekdays.
  bool get isAllDay => startMinutes == endMinutes;

  LockSchedule copyWith({
    String? name,
    Set<String>? packages,
    Set<int>? weekdays,
    int? startMinutes,
    int? endMinutes,
    bool? enabled,
    bool? lockPhone,
  }) =>
      LockSchedule(
        id: id,
        name: name ?? this.name,
        packages: packages ?? this.packages,
        weekdays: weekdays ?? this.weekdays,
        startMinutes: startMinutes ?? this.startMinutes,
        endMinutes: endMinutes ?? this.endMinutes,
        enabled: enabled ?? this.enabled,
        lockPhone: lockPhone ?? this.lockPhone,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'packages': packages.toList()..sort(),
        'weekdays': weekdays.toList()..sort(),
        'startMinutes': startMinutes,
        'endMinutes': endMinutes,
        'enabled': enabled,
        'lockPhone': lockPhone,
      };

  factory LockSchedule.fromJson(Map<String, dynamic> json) => LockSchedule(
        id: json['id'] as String?,
        name: json['name'] as String? ?? 'Schedule',
        packages: ((json['packages'] as List<dynamic>?) ?? const [])
            .map((e) => e as String)
            .toSet(),
        weekdays: ((json['weekdays'] as List<dynamic>?) ?? const [])
            .map((e) => (e as num).toInt())
            .toSet(),
        startMinutes: (json['startMinutes'] as num?)?.toInt() ?? 0,
        endMinutes: (json['endMinutes'] as num?)?.toInt() ?? 0,
        enabled: json['enabled'] as bool? ?? true,
        lockPhone: json['lockPhone'] as bool? ?? false,
      );
}
