# 휴대폰 전체 잠금 스케줄: 키오스크 방식 검토

2026-11-06 기준 검토. 기능은 아직 구현하지 않았으며, 이 검토로 기기 정책을 변경하지 않는다.

후속 결정: 사용자가 Device Owner를 사용하지 않기로 하여 이 안은 채택하지 않았다.
현재 구현은 [일반 설치 방식의 휴대폰 잠금](PHONE_LOCK_SCHEDULES.md)을 따른다.

## 가능 범위와 등록 조건

OpenLock을 기기의 Device Owner로 등록하고 Lock task 모드로 PIN 화면을 실행하는 구성이
요구사항에 맞는다. 키오스크에 진입한 뒤에는 알림창/빠른 설정/홈/최근 앱 제한을 Android가
처리하므로, 매번 접근성 이벤트를 받고 다른 화면을 덮는 방식에 의존하지 않는다.
키오스크 진입 전의 시간 차이까지 없어진다는 뜻은 아니다.
[Lock task 가이드](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).

현재 `requestDeviceAdmin()`은 일반 기기 관리자 등록만 요청한다. 앱의 Grant 버튼,
제한된 설정 허용, Shizuku에서 삭제 방지 플래그를 설정하는 작업은 Device Owner 등록과 별개다.
실제 휴대폰의 Owner 상태는 아직 조회하지 않았다.

- 일반적인 전용 기기 배포는 초기화 후 등록한다.
  [기기 등록 안내](https://developer.android.com/work/dpc/dedicated-devices#provision_dedicated_devices).
- 개발 중에는 계정이 없는 기기에 ADB로 Owner를 등록하는 초기화 없는 경로도 있다.
  현재 삼성/Xiaomi의 계정·사용자·기존 관리 상태에서 성공한다고 가정하지 않는다.
  계정 삭제나 초기화는 이 검토의 작업 범위가 아니다.
  [개발용 등록 안내](https://developer.android.com/work/dpc/dedicated-devices/cookbook#development_setup).

## 현재 코드에서 추가할 부분

| 위치 | 필요한 변경 |
| --- | --- |
| `LockSchedule` / 스케줄 편집·목록 | `Selected apps`와 `Entire phone (kiosk)` 모드. 기존 JSON은 앱 모드로 읽어 호환 유지 |
| Flutter/native 스케줄 평가 | 휴대폰 스케줄과 앱별 잠금을 분리. 요일·자정 넘김·중복 스케줄 규칙은 재사용 |
| `EnforcementPlugin` | Owner 여부/실제 키오스크 허용·실행 상태 조회. 일반 관리자 활성 상태만으로 준비 완료 표시 금지 |
| `OpenLockMonitorService` | 스케줄 시작, 사용자 잠금 해제, 화면 재개에 휴대폰 잠금을 평가. 실제 키가드가 잠겼을 때 무조건 시작하지 않음 |
| `LockActivity` | 휴대폰 잠금 모드에서는 취소→홈 동작을 사용하지 않음. 인증 후 키오스크를 해제하고 종료 |
| 기기 관리자·복구 흐름 | 일반 관리자 해제를 Owner 해제로 취급하지 않음. 재부팅·업데이트·프로세스 재시작 후 실제 OS 상태와 스케줄을 재확인 |

기존 PIN 검증 UI는 재사용할 수 있지만 `showWhenLocked`/`turnScreenOn`, 취소, 인증 후
단순 `finish()` 동작을 그대로 사용하는 것은 적합하지 않다. 기존 앱별 잠금과 설정 보호의
세션을 휴대폰 인증 때문에 자동으로 모두 해제해서도 안 된다.

## 권장 동작 초안

1. 스케줄에서 `Entire phone (kiosk)`를 선택한다. Owner 등록과 PIN이 확인된 경우에만 활성화한다.
2. 지정 시간이 시작되면, 사용 중인 화면이거나 시스템 잠금 해제가 완료된 시점에 PIN을 띄운다.
   `KeyguardManager`로 확인하고, 이미 시스템 잠금 중이면 무리하게 키오스크를 시작하지 않는다.
3. OpenLock만 Lock task 허용 목록에 두고 실제 `LOCK_TASK_MODE_LOCKED` 진입을 확인한다.
   허용되지 않았는데 `startLockTask()`를 호출해 단순 화면 고정을 성공으로 표시하지 않는다.
4. 알림창·빠른 설정·홈·최근 앱은 제한하되 시스템 잠금화면은 유지한다.
   제안하는 기능 조합은 `KEYGUARD | GLOBAL_ACTIONS | SYSTEM_INFO`이다. 전원 메뉴와
   시계/배터리 표시는 유지하고, 알림창·홈·최근 앱을 허용하는 플래그는 넣지 않는다.
5. PIN 인증 시 키오스크를 해제한다. 휴대폰 사용 인증은 다음 화면 꺼짐까지 유지하는 안을
   권장한다. 이는 아직 확정되지 않은 UX이며 구현 전에 확정해야 한다.
6. 스케줄 종료/비활성화 시 PIN 없이 해제한다. 화면 재개 때도 종료 여부를 먼저 확인한다.
   중복 스케줄 중 하나가 끝났어도 다른 휴대폰 잠금 스케줄이 유효하면 계속 적용한다.
7. 복구용으로 인증 후 키오스크를 중지하는 경로를 먼저 구현한다. Owner 등록 해제는
   키오스크 일시 종료와 다른 기능이며, 일상적인 PIN 인증이나 스케줄 종료에서 수행하지 않는다.

위 상태 전이는 제안 설계이다. 키가드가 잠긴 상태의 진입을 피해야 하는 점과 화면 고정/키오스크의
차이는 [Lock task 가이드](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode#start_lock_task_mode)를 따른다.
시스템 UI 제어는 `setLockTaskFeatures()`로 한다. `setStatusBarDisabled()`는 Lock task 중에는
효과가 없으며, 일반 시스템 잠금화면에도 적용되지 않으므로 그것 하나로 대체하지 않는다.
[DevicePolicyManager API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setStatusBarDisabled(android.content.ComponentName,%20boolean)).

## 실제 기기 확인과 구현 순서

먼저 삼성 테스트 기기에서 Owner 등록 가능 여부와 기존 Owner/계정/사용자 구성을 확인한다.
현재 PC에는 `adb`가 없어 기기를 조회하지 못했다. Owner 등록·계정 변경·초기화 명령을 실행하지 않았다.

구현 순서는 상태 조회 → 인증 가능한 수동 진입/해제 → 스케줄 연결 → 재부팅/오류 복구가 적절하다.
삼성 Android 17과 최종 Xiaomi Android 16/HyperOS에서는 아래를 각각 확인해야 한다.

- 화면을 껐다 켜 시스템 잠금을 해제하면 PIN으로 돌아오는지. 시스템 PIN/지문을 건너뛰지 않는지.
- 알림창/빠른 설정/홈/최근 앱, 제스처·버튼 내비게이션에서 제한이 적용되는지.
- PIN 성공·실패·뒤로가기, 생체 인증, 화면 회전과 프로세스 재시작 시 상태가 일관되는지.
- 종료 시각, 자정 넘김, 중복 스케줄, 스케줄 비활성화 후 정상 사용으로 돌아오는지.
- 수신 전화·긴급전화·알람의 실제 동작. 필요한 시스템 화면을 통째로 허용하지 않고 필요한 경로를 검증할 것.
- 재부팅·앱 업데이트·보호 서비스 재시작 뒤 복구, 기존 앱별/설정 보호 기능의 유지.

검토 결론: 구현 가능한 방향이다. 현재 앱의 일반 기기 관리자 기능만으로 활성화할 수는 없으며,
Owner 등록 가능 여부와 테스트 기기에서의 수동 해제 검증이 먼저 필요하다.
