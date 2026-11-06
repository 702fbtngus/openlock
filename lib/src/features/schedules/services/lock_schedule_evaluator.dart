import 'package:openlock/src/features/schedules/models/lock_schedule.dart';

/// Pure evaluator that answers: given these Focus Schedules and the current
/// local time, which packages must be locked *right now*?
///
/// All arithmetic is done on the local wall clock's weekday and
/// minutes-since-midnight, never on absolute UTC durations, so it is inherently
/// DST-safe: a window like 09:00–17:00 stays 09:00–17:00 in local time even on
/// the day the clocks change.
abstract final class LockScheduleEvaluator {
  /// The union of packages from every enabled schedule active at [now].
  static Set<String> lockedPackagesAt(
    Iterable<LockSchedule> schedules,
    DateTime now,
  ) {
    final locked = <String>{};
    for (final schedule in schedules) {
      if (!schedule.enabled || schedule.lockPhone) continue;
      if (isActiveAt(schedule, now)) {
        locked.addAll(schedule.packages);
      }
    }
    return locked;
  }

  static bool isPhoneLockedAt(
    Iterable<LockSchedule> schedules,
    DateTime now,
  ) =>
      schedules.any((s) => s.enabled && s.lockPhone && isActiveAt(s, now));

  /// Whether a single [schedule] is active at local time [now].
  static bool isActiveAt(LockSchedule schedule, DateTime now) {
    final nowMinutes = now.hour * 60 + now.minute;
    final today = now.weekday; // 1..7
    final yesterday = today == 1 ? 7 : today - 1;

    // All-day window: active for the whole of each selected weekday.
    if (schedule.isAllDay) {
      return schedule.weekdays.contains(today);
    }

    if (!schedule.isOvernight) {
      // Same-day window: [start, end).
      return schedule.weekdays.contains(today) &&
          nowMinutes >= schedule.startMinutes &&
          nowMinutes < schedule.endMinutes;
    }

    // Overnight window (e.g. 22:00 → 06:00). It belongs to the weekday it
    // *starts* on, so the early-morning tail is attributed to yesterday.
    final eveningPortion = schedule.weekdays.contains(today) &&
        nowMinutes >= schedule.startMinutes;
    final morningPortion = schedule.weekdays.contains(yesterday) &&
        nowMinutes < schedule.endMinutes;
    return eveningPortion || morningPortion;
  }
}
