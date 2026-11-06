import 'package:flutter_test/flutter_test.dart';
import 'package:openlock/src/features/enforcement/models/lock_config.dart';
import 'package:openlock/src/features/schedules/models/lock_schedule.dart';
import 'package:openlock/src/features/schedules/services/lock_schedule_evaluator.dart';

void main() {
  LockSchedule phone({bool enabled = true}) => LockSchedule(
        id: 'phone',
        name: 'Night',
        packages: {'com.saved-selection'},
        weekdays: {5},
        startMinutes: 22 * 60,
        endMinutes: 6 * 60,
        enabled: enabled,
        lockPhone: true,
      );

  test('legacy schedules remain app-only and phone mode round-trips', () {
    final oldJson = phone().toJson()..remove('lockPhone');
    expect(LockSchedule.fromJson(oldJson).lockPhone, isFalse);
    final restored = LockSchedule.fromJson(phone(enabled: false).toJson());
    expect(restored.lockPhone, isTrue);
    expect(restored.enabled, isFalse);
    expect(restored.copyWith(name: 'Edited').lockPhone, isTrue);
    expect(restored.copyWith(lockPhone: false).lockPhone, isFalse);
    expect(restored.packages, {'com.saved-selection'});
  });

  test('phone schedule reaches native config and backup JSON', () {
    final config = LockConfig(schedules: [phone()]);
    final restored = LockConfig.fromJson(config.toJson());
    expect(restored.schedules.single.lockPhone, isTrue);
    final native = config.toNativeMap(pinHash: 'hash', pinSalt: 'salt');
    expect((native['schedules'] as List).single['lockPhone'], isTrue);
  });

  test('phone and app schedules remain independent', () {
    final app = phone().copyWith(lockPhone: false, packages: {'com.app'});
    final now = DateTime(2026, 11, 6, 23); // Friday, local wall time.
    expect(LockScheduleEvaluator.isPhoneLockedAt([phone(), app], now), isTrue);
    expect(LockScheduleEvaluator.lockedPackagesAt([phone(), app], now), {'com.app'});
    expect(LockScheduleEvaluator.isPhoneLockedAt([app], now), isFalse);
    expect(LockScheduleEvaluator.isPhoneLockedAt([phone(enabled: false)], now),
        isFalse);
  });

  test('overnight phone window belongs to Friday and ends at 06:00', () {
    final schedules = [phone()];
    expect(LockScheduleEvaluator.isPhoneLockedAt(
        schedules, DateTime(2026, 11, 6, 21, 59)), isFalse);
    expect(LockScheduleEvaluator.isPhoneLockedAt(
        schedules, DateTime(2026, 11, 6, 22)), isTrue);
    expect(LockScheduleEvaluator.isPhoneLockedAt(
        schedules, DateTime(2026, 11, 7, 5, 59)), isTrue);
    expect(LockScheduleEvaluator.isPhoneLockedAt(
        schedules, DateTime(2026, 11, 7, 6)), isFalse);
    expect(LockScheduleEvaluator.isPhoneLockedAt(
        schedules, DateTime(2026, 11, 7, 23)), isFalse);
  });
}
