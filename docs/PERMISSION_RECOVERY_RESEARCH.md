# OpenLock 권한 감시·자동 복구 조사

조사일: 2026-11-07. Device Owner와 root 없이, 일반 설치 OpenLock에 ADB로
권한을 부여하는 경우와 실행 중인 ADB/Shizuku를 사용하는 경우를 구분했다.
Android 16 `android16-release`와 최신 AOSP의 권한 정의를 대조했다.
삼성 Android 17 및 Xiaomi HyperOS 3의 제조사 수정·실제 실행 결과는 검증하지 않았다.

후속 개발 형태는 **원본 Shizuku manager UI를 유지하는 별도 포크 + OpenLock의
submodule + 하나의 APK**로 정했다. 진입 메뉴는 Settings > Advanced > Shizuku다.
아래의 자기 앱 전용 축소 서버는 비교 대안이며, 현재 구현 계획으로 확정한 것은
아니다. 단계별 진행 상황과 빌드 구성은 [Shizuku 통합 안내](SHIZUKU_INTEGRATION.md)를
기준으로 한다.

## 결과

모든 항목은 OpenLock 자신의 상태를 일반 앱 API로 확인할 수 있다. 그러나 ADB로
현재 상태를 허용하는 작업과, OpenLock에 앞으로의 재허용 권한을 부여하는 작업은
다르다. 후자의 관리 권한 대부분은 일반 앱에 `pm grant`로 줄 수 없다.
[Android 권한 정의](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/res/AndroidManifest.xml),
[shell이 가진 별도 권한](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/packages/Shell/AndroidManifest.xml).

| 항목 | 감시 API / 근거 | ADB 1회 설정 후 OpenLock 단독 재허용 | 실행 중인 shell 권한으로 복구 |
| --- | --- | --- | --- |
| 접근성 | Secure 설정 두 개 + 서비스 연결 콜백 | WRITE_SECURE_SETTINGS로 설정 복구 가능. 기존 선택 기능 | 가능. 타 서비스 목록 보존 필요 |
| 기기 관리자 | DevicePolicyManager.isAdminActive | 불가 | set-active-admin 경로 있음. Device Owner 설정과 별개 |
| Usage access | GET_USAGE_STATS AppOps + 기본 모드의 권한 확인 | 개별 권한의 초기 부여는 가능하지만 명시적 AppOps 거부를 스스로 되돌릴 수 없음 | AppOps allow 경로 있음 |
| Display over other apps | Settings.canDrawOverlays | 개별 권한의 초기 부여는 가능하지만 명시적 AppOps 거부를 스스로 되돌릴 수 없음 | AppOps allow 경로 있음 |
| Ignore battery optimization | PowerManager.isIgnoringBatteryOptimizations | 불가. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS는 승인 화면 요청용 | DeviceIdle whitelist 경로 있음 |
| Notifications | areNotificationsEnabled + POST_NOTIFICATIONS + 채널/그룹 상태 | 초기 알림 권한 부여는 가능하지만 재거부 후 자체 재부여 불가 | 앱 전체 권한은 pm grant 경로 있음. 채널·그룹은 별도 |
| Notification access | isNotificationListenerAccessGranted + 연결 콜백 | WRITE_SECURE_SETTINGS만으로 지원할 수 없음 | cmd notification allow_listener 경로 있음 |

여기서 '경로 있음'은 AOSP가 shell에 해당 작업을 제공한다는 뜻이다. OpenLock에
Shizuku 연동이 구현됐다는 뜻이나, 모든 제조사·사용자 제한을 통과한다는 뜻은 아니다.
앱 프로세스 전체가 정지되면 그 안의 감시 코드도 실행되지 않는다.

## 한 번의 pm grant로 해결되지 않는 이유

- WRITE_SECURE_SETTINGS는 `development`가 포함되어 있어 ADB로 부여할 수 있다.
- PACKAGE_USAGE_STATS와 SYSTEM_ALERT_WINDOW에도 `development`가 있지만,
  권한을 가진 것과 AppOps 거부 상태를 변경할 수 있는 것은 다르다.
- AppOps 변경용 MANAGE_APP_OPS_MODES, 기기 관리자 변경용 MANAGE_DEVICE_ADMINS,
  런타임 권한 부여용 GRANT_RUNTIME_PERMISSIONS, 알림 접근 변경용
  MANAGE_NOTIFICATION_LISTENERS, 배터리 예외 변경용 DEVICE_POWER에는 이 플래그가 없다.
  일반 설치 앱에 대한 `pm grant`만으로 위 관리 능력을 위임할 수 없다.
  [권한별 보호 수준](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/res/AndroidManifest.xml).

UsageStatsService는 AppOps가 기본 모드일 때에만 PACKAGE_USAGE_STATS 권한으로
판정을 보충하며, 명시적으로 거부된 AppOps는 거부로 처리한다. Overlay 검사에도
기본 모드에서의 권한 확인과 명시적 거부를 구분하는 코드가 있다. 따라서 개발 권한
부여만으로 설정 화면에서 꺼진 권한을 영구적으로 유지할 수 있다고 보지 않는다.
[UsageStatsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/usage/java/com/android/server/usage/UsageStatsService.java),
[Settings.canDrawOverlays의 판정](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/provider/Settings.java),
[AppOps 변경의 권한 검사](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/appop/AppOpsService.java).

## OpenLock용 ADB 검증 명령

아래는 연결된 기기에서 실행할 **상태 변경 명령의 예시**이며 이번 조사에서 실행하지
않았다. 사용자 0에 설치된 OpenLock을 전제로 한다. 먼저 `adb shell am get-current-user`로
확인하고, 다른 사용자라면 사용자 ID와 설치 위치를 맞춰야 한다. 각 명령은 원할 때
개별적으로 실행하고 실제 상태를 다시 확인한다.

접근성 자동 복구에 사용할 권한:

```sh
adb shell pm grant --user 0 dev.abdullah.openlock android.permission.WRITE_SECURE_SETTINGS
```

Usage access와 overlay의 현재 AppOps 허용:

```sh
adb shell cmd appops set --user 0 dev.abdullah.openlock GET_USAGE_STATS allow
adb shell cmd appops set --user 0 dev.abdullah.openlock SYSTEM_ALERT_WINDOW allow
```

기기 관리자 등록과 Android 배터리 예외 목록 추가:

```sh
adb shell dpm set-active-admin --user 0 dev.abdullah.openlock/.OpenLockDeviceAdminReceiver
adb shell dumpsys deviceidle whitelist +dev.abdullah.openlock
```

`set-active-admin`은 `set-device-owner`와 다른 명령이다. 일반 관리자 등록 경로만
사용하며 이 조사에서는 Device Owner를 제안하지 않는다.
[DPM shell 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerServiceShellCommand.java).
배터리 예외 추가 API는 DEVICE_POWER를 검사하며 shell 명령도 제공한다.
HyperOS의 별도 자동 시작·백그라운드 실행 설정까지 바뀐다고 가정하지 않는다.
[DeviceIdleController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java).

Android 13 이상에서 앱의 알림 전송 권한 부여와 알림 접근 승인:

```sh
adb shell pm grant --user 0 dev.abdullah.openlock android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener dev.abdullah.openlock/.OpenLockNotificationListenerService 0
```

첫 명령은 알림 전송 권한이고 두 번째는 다른 앱의 알림을 읽는 서비스의 승인이다.
서로 대체되지 않는다.
[알림 권한의 공식 ADB 예시](https://developer.android.com/develop/ui/views/notifications/notification-permission),
[allow_listener shell 구현](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/notification/NotificationShellCmd.java).

개별 알림 채널이 차단된 경우 POST_NOTIFICATIONS만 다시 부여해도 그 채널은
해결되지 않을 수 있다. 최신 AOSP에는 shell을 허용하는 채널 변경 Binder API가
있지만, 그룹 변경 등 다른 API는 권한 검사가 다르다. '알림 전체 복구'로 묶지 말고
앱 권한·채널·그룹을 따로 검증해야 한다.
[NotificationManagerService의 채널·그룹 변경 검사](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/notification/NotificationManagerService.java).

앱에서 위 명령 문자열을 Runtime.exec로 실행해도 app UID가 shell UID가 되는 것은
아니다. 자체 권한으로 할 수 없는 작업에는 실행 중인 privileged backend가 필요하다.
Shizuku의 Binder wrapper 또는 UserService가 이 경로를 제공한다.
[Shizuku 개발 문서](https://github.com/RikkaApps/Shizuku-API).

## Shizuku를 사용할 경우

OpenLock 앱은 자신의 권한만 감시하고, 해제가 확인되면 승인된 Shizuku backend에
복구를 요청하는 구조를 사용할 수 있다. 복구 전후의 상태 조회는 앱의 정상 API로
수행하고, backend 부재·권한 거부·재시작 필요를 성공과 구분해야 한다.

ADB로 시작한 Shizuku는 root가 아닌 shell 권한으로 동작한다. 이전 조사에서는
비루팅 방식이 재부팅마다 수동 시작을 요구한다고 설명했으나, 이는 최신 릴리스에
대해서는 정정해야 한다. 공식 **13.6.0** 릴리스에는 **Android 13 이상에서 신뢰한
WLAN에 연결된 경우 비루팅 자동 시작**이 추가됐다. 시작 안내와 API README의
수동 재시작 설명만으로 최신 동작을 판단하면 이 기능을 놓칠 수 있다.
[공식 13.6.0 변경 사항](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0).

동일 태그의 BootCompleteReceiver를 확인하면 사용자 0, Android 13 이상,
Shizuku 자신의 WRITE_SECURE_SETTINGS 보유, 마지막 시작 방식이 ADB라는 조건을
검사한다. 이어 무선/USB 디버깅 설정을 켜고, 저장된 ADB 키와 mDNS로 발견한 포트를
사용해 127.0.0.1에 연결하여 Shizuku를 시작한다. 따라서 OpenLock에만 WSS를 부여한
상태와 Shizuku 자체의 자동 시작 준비가 완료된 상태를 구분해야 한다.
[13.6.0 부팅 처리 코드](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt),
[WSS 요청 선언](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/AndroidManifest.xml).

필요한 경우 PC에서 Shizuku 자체에 부여하는 명령의 예시는 다음과 같다. 이번 조사에서
실행하지 않았다. OpenLock에 부여하는 명령과 대상 패키지가 다르다.

```sh
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

이 경로는 최초 무선 디버깅 페어링·시작, 신뢰한 Wi-Fi, 유효한 키, 디버깅 허용,
부팅 수신과 백그라운드 실행을 전제로 한다. 부팅 코드의 mDNS 대기 시간은 3초이며,
이 코드만으로 부팅 때 Wi-Fi가 늦게 연결되는 경우까지 재시도를 보장할 수 없다.
따라서 '재부팅 후 반드시 자동 복구' 또는 'Wi-Fi 없이 자동 재시작'이라고 안내하지
않는다. Shizuku 서버를 시작하는 동작과 OpenLock 권한을 복구하는 동작도 별개다.
Xiaomi에서는 별도의 USB debugging (Security options) 설정이 필요할 수 있다.
[Shizuku 제조사 제한 안내](https://shizuku.rikka.app/guide/setup/).

## 초기화·root·Device Owner 없이 권장하는 구조

OpenLock의 기존 접근성 복구를 유지하고, 나머지 권한 복구에 Shizuku를 선택적으로
연동하는 방식을 우선 검토한다. 이는 Android 권한 검사를 우회하는 일반 앱 API를
발견했다는 뜻이 아니라, 사용자가 승인한 shell 실행 경로를 사용하는 설계다.

1. **독립적인 감시:** 접근성 서비스의 생존에 감시를 의존시키지 않는다. 현재 monitor의
   별도 작업 스레드에서 자신의 권한 상태와 서비스 연결을 확인한다. 상태 변경 알림과
   주기적인 재확인, 화면 켜짐·잠금 해제·Shizuku 연결 시 검사를 조합한다.
2. **복구 수단 선택:** 접근성 설정은 WSS로 복구한다. Usage access·overlay는 AppOps,
   일반 기기 관리자는 set-active-admin, Android 배터리 예외는 DeviceIdle,
   앱 알림 권한과 알림 접근은 각각의 shell 경로를 Shizuku UserService에서 사용한다.
   권한마다 실기기에서 독립적으로 지원 여부를 판정한다.
3. **복구 검증:** 종료 코드만 보지 않고 앱 API로 실제 상태를 재확인한다. 알림 채널과
   그룹 차단, HyperOS 자동 시작·배터리 정책 등은 별도 항목으로 표시한다. 관리자 등록
   제한이나 제조사 거부를 성공으로 처리하지 않는다.
4. **Shizuku 연결 복원:** 서버가 준비되면 즉시 다시 검사한다. 서버가 없으면 복구를
   대기 상태로 표시하고 접근성 WSS 복구는 계속한다. 13.6.0 자동 시작은 대상 HyperOS의
   재부팅·Wi-Fi 연결 시점을 달리해 검증한 뒤 지원 여부를 안내한다.
5. **사용자의 중지:** PIN 인증으로 전체 복구를 일시 중지하거나 해제할 수 있게 한다.
   정상 상태에는 쓰지 않고 실패 재시도에 제한을 둔다. backend는 OpenLock 자신과
   해당 사용자만 대상으로 하며 외부에서 임의의 명령이나 패키지를 전달받지 않는다.

Shizuku에 의존하지 않는 대안은 **앱 내부의 로컬 ADB 클라이언트**다. LADB는 Android의
무선 디버깅으로 같은 기기에 연결하는 구현 사례다. OpenLock에서도 처음 페어링한
키로 로컬 adbd에 연결해 자신의 복구 명령만 실행하는 설계가 가능하다고 추론할 수
있다. 다만 인증 키 보관, TLS 페어링, 포트 탐색, 연결 복원과 shell 작업 처리를 직접
구현해야 하며 Wi-Fi·디버깅·제조사 제한은 사라지지 않는다. 현재 LADB README는
Shizuku와의 충돌도 명시하므로 두 앱을 함께 설치하는 해결책으로 제안하지 않는다.
[LADB의 구현 설명](https://github.com/tytydraco/LADB#how-does-it-work),
[Android 무선 디버깅](https://developer.android.com/tools/adb#wireless-android11-commandline).

외부 PC의 USB ADB 감시 스크립트도 대안이다. 휴대폰의 OpenLock 프로세스가 강제 종료된
경우에도 외부 감시 주체가 남는 장점이 있지만, PC와 디버깅 연결이 유지되어야 한다.
무선 연결이면 네트워크·디버깅 중단에 종속된다. 사용자에게 반복적인 수동 원격 명령을
요구하기보다 필요할 경우 외부 스크립트가 고정된 자기 앱 복구만 수행하도록 구성한다.

별도 앱 설치가 허용된다면 우선순위는 **기존 WSS 접근성 복구 + 공식 Shizuku 연동 +
조건부 부팅 자동 시작**이다. 별도 앱을 추가하지 않는 요구에 대해서는 아래 내장형
설계를 우선 검토한다.
OpenLock과 Shizuku가 모두 실행되지 않거나 디버깅 경로가 차단되면 이 구성 역시
복구할 주체가 없어진다. 완전한 무중단 보호나 복구 시간 상한을 보장하지 않는다.

## 추가 앱 없이 Shizuku 구성 요소를 내장하는 방안

사용자는 자신의 삼성 테스트 기기에서 `무선 디버깅 켜기 → Shizuku 시작 → 무선
디버깅 끄기 → 재부팅 → Wi-Fi 연결` 후 Shizuku가 다시 동작함을 확인했다고 보고했다.
이는 사용자 관찰이며, OpenLock 내장 구현이나 최종 Xiaomi 기기에서 검증한 결과는 아니다.

시작된 서버는 별도 shell 프로세스에서 Binder 요청을 처리하므로 ADB 연결이 계속
열려 있어야 하는 구조는 아니다. 다만 재부팅하면 해당 프로세스도 종료된다.
13.6.0 부팅 코드는 WSS로 무선/USB 디버깅을 켠 뒤 저장된 키로 로컬 ADB에 연결하고,
서버 시작 명령을 보낸 뒤 연결을 닫는다. 따라서 사용자 관찰은 '디버깅 기능을
영구적으로 꺼 둔 상태에서도 부팅마다 시작된다'가 아니라, 자동 시작 과정에서
디버깅이 다시 켜졌을 가능성과 일치한다. 실제 토글 상태와 시작 로그는 별도 확인이 필요하다.
[서버 진입점과 Binder 처리](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/server/src/main/java/rikka/shizuku/server/ShizukuService.java),
[부팅 시 디버깅 활성화와 연결 종료](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt).

Shizuku는 server와 starter를 Android library 모듈로 빌드하고, manager 애플리케이션이
이들을 포함한다. 이 구조를 근거로 **OpenLock 한 APK에 페어링·시작 코드와 shell 서버를
통합하는 것은 기술적으로 가능하다고 판단한다.** 그대로 의존성을 추가하면 완성되는
공식 임베딩 API가 있다는 의미는 아니다.
[server 모듈](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/server/build.gradle),
[starter 모듈](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/starter/build.gradle),
[manager의 의존성과 네이티브 빌드](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/build.gradle).

Shizuku API 라이브러리만 추가하는 방법은 외부 Shizuku 서버를 사용하는 클라이언트
연동이다. 별도 설치를 없애려면 서버와 시작 경로까지 포함해야 한다. submodule은
상위 소스의 버전을 고정·관리하는 수단으로 쓸 수 있지만 APK 통합을 대신하지 않는다.

구체적으로 필요한 작업은 다음과 같다.

1. **소스 고정과 빌드 분리:** 검토한 상위 버전을 고정하고, OpenLock의 Flutter/Gradle
   빌드에서 필요한 Android·네이티브 모듈을 포함한다. 전체 manager UI와 타 앱 권한
   관리 화면은 요구 범위에 포함하지 않는다. 현재 OpenLock은 Java 17을 사용하지만
   상위 manager는 JVM 21과 CMake 설정을 사용하므로 빌드 설정을 그대로 복사하지 않는다.
2. **최초 설정:** OpenLock 안에 사용자가 직접 승인하는 무선 디버깅 페어링 화면과
   연결 상태를 제공한다. 기존 Shizuku의 페어링 키나 앱 데이터를 가져온다고 가정하지
   않는다. OpenLock용 별도 키를 만들고 백업·로그·진단에 비밀 키를 노출하지 않는다.
3. **자기 앱 전용 서버:** 상위 서버는 원래 manager 패키지와 provider를 찾아 통신한다.
   패키지 상수·provider authority·서버 검색 경로를 OpenLock에 맞추고, Binder 호출자의
   UID를 검증한다. 서버는 자기 앱의 정해진 복구 동작만 제공하며 외부 앱에 일반
   shell 실행이나 Shizuku 호환 권한 부여 API를 제공하지 않는 설계를 우선한다.
4. **생명주기와 복구:** 앱의 일반 프로세스는 감시·PIN·설정을 담당하고, ADB로 시작된
   shell 프로세스가 필요한 시스템 변경을 담당한다. 일반 Android Service나 별도
   `android:process` 선언만으로 shell 신원을 얻을 수는 없다. 부팅 후 허용된 Wi-Fi에서
   서버 재시작을 시도하고, 상태를 '시작 중/연결됨/재설정 필요'로 구분한다.
5. **기존 보호 유지:** WSS 접근성 복구와 기존 잠금 경로를 독립적으로 유지한다.
   내장 서버가 없어도 기존 보호가 가능한 범위는 계속 동작해야 한다. PIN으로 복구를
   중지할 수 있고, 각 쓰기 직전에도 사용자의 현재 복구 설정을 확인한다.
6. **단계별 실기기 확인:** 먼저 내장 서버 시작과 자기 앱 전용 왕복 호출을 검증한 뒤
   Usage access 등 한 권한씩 복구를 추가한다. 무선 디버깅 수동 끄기, 화면 꺼짐,
   Wi-Fi 상실·재연결, 서버 종료, 재부팅, 앱 업데이트를 각각 시험한다. 포그라운드
   서비스 알림이나 시스템 페어링 기록까지 없어지는 설계로 설명하지 않는다.

상위 저장소 루트는 Apache-2.0으로 수정·재배포 조건을 명시한다. 도입 시 해당 버전의
라이선스·저작권·NOTICE 및 수정 표시를 보존하고, API 하위 모듈과 네이티브 의존성의
라이선스도 개별 확인해야 한다.
[Shizuku 13.6.0 라이선스](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/LICENSE).

이 문단은 구현 가능성과 작업 범위를 조사한 것이며 내장 서버를 구현한 것은 아니다.
후속 논의에서는 **기존 WSS 복구 + 원본 manager UI를 유지하는 Shizuku 포크 내장**을
개발 방향으로 선택했다. 외부 Shizuku 설치를 없애도 제조사의 디버깅 차단을 우회할 권한이 생기거나,
OpenLock 자체가 시스템 앱·root 앱으로 바뀌지는 않는다.

## 후속 구현 시 확인할 점

1. 모든 상태 확인과 backend 호출을 메인 스레드 밖에서 수행한다. 진단에서 발견한
   메인 스레드 대기에 감시·복구까지 묶지 않는다.
2. 정상 상태에는 쓰기를 하지 않는다. 변경 알림과 낮은 빈도의 재검사, 중복 억제,
   재시도 제한을 사용한다. 현재 보호 의도와 선택 옵션을 쓰기 직전에도 확인한다.
3. 사용자가 PIN 인증으로 복구를 일시 중지하거나 끌 수 있어야 한다. 다른 앱의
   권한은 변경하지 않고, 전체 접근성/알림 접근 목록을 덮어쓰지 않는다.
4. '설정 허용', '서비스 연결', '실제 보호 동작'을 별도로 확인한다. 권한 부여 성공
   응답만으로 접근성 또는 알림 리스너가 동작한다고 보고하지 않는다.
5. 현재 Notification access 조회는 Secure 문자열을 사용한다. API 27 이상에서는
   NotificationManager.isNotificationListenerAccessGranted를 우선 사용하도록 개선할
   근거가 있다. 승인이 남아 있는데 연결만 끊긴 경우 requestRebind도 검토할 수
   있지만, 승인 자체가 해제된 경우에는 이 API로 다시 승인할 수 없다.
   [승인 조회 API](https://developer.android.com/reference/android/app/NotificationManager#isNotificationListenerAccessGranted(android.content.ComponentName)),
   [requestRebind의 제한](https://developer.android.com/reference/android/service/notification/NotificationListenerService#requestRebind(android.content.ComponentName)).
6. 현재 Usage access 조회는 MODE_ALLOWED만 인정한다. 개발 권한을 별도로 부여하는
   시나리오를 지원한다면 MODE_DEFAULT와 PACKAGE_USAGE_STATS의 조합도 반영해야 한다.
7. 삼성과 Xiaomi 각각에서 초기 허용, 설정에서 재해제, backend 종료·재시작,
   재부팅, 앱 업데이트, 권한 철회와 복구 중 보호 중지를 검증한다. 실제 기기에서
   확인하기 전에는 제조사 지원이나 복구 시간 상한을 보장하지 않는다.

이 조사의 후속 작업으로 원본 Shizuku 관리 화면과 서버를 내장하는 모듈 및
Settings 진입 메뉴를 구현했다. 단계별 빌드·검증 상태는 [통합 안내](SHIZUKU_INTEGRATION.md)에
기록한다. 권한 복구는 [접근성 복구](ACCESSIBILITY_RECOVERY.md)를 제공한다.
내장 Shizuku 서버에는 TCP 재시작, 부팅 재시도와 워치독을 연결했다.

실험적 오프라인 시작 옵션은 사용 가능한 ADB 연결과 Wi-Fi 연결이 없을 때 임시
로컬 전용 핫스팟을 요청하고, Android가 무선 디버깅을 끄면 제한된 시간 동안
다시 활성화를 요청한다. 기본 비활성 상태이며 Android 11 이상, 기존 페어링,
WRITE_SECURE_SETTINGS와 핫스팟용 런타임 권한이 필요하다. 부팅 후 첫 잠금 해제
시점에 재시도를 요청한다. 실패한 자동 실험에는 5분 대기와 3회 제한을 적용하고,
수동 시작으로 다시 시도할 수 있다. 시도 결과는 내장 Shizuku의 Startup에 있는
Offline startup diagnostics에서 확인하고 복사한다.

이 옵션의 실제 오프라인 시작 및 재부팅 복구 성공 여부는 Samsung과 Xiaomi에서
각각 확인해야 한다. 핫스팟 요청과 무선 디버깅 활성화는 OS 및 제조사 정책의
영향을 받는다. 설정 위치와 검증 절차는 [통합 안내](SHIZUKU_INTEGRATION.md)에 기록한다.
