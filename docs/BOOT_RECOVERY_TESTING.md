# 재부팅 후 보호 복구

## 변경 내용

- 이전 BootReceiver는 PIN과 잠글 앱/스케줄이 있어야만 복구했다. 삭제·설정 보호만
  사용하는 경우도 복구 대상에 포함한다.
- Turn on protection과 명시적인 중지 상태를 별도로 저장한다. 켜 둔 보호는 선택한 앱이
  없어도 복구 대상이며, 직접 끈 보호는 자동으로 다시 켜지 않는다.
- 부팅/앱 업데이트 수신 시 `goAsync`로 설정 확인을 수행한다. 메인 스레드에서
  Keystore 초기화를 기다리지 않는다. 사용자의 재부팅 후 첫 기기 잠금 해제 이후가 대상이다.
- 접근성 서비스 연결 및 이벤트를 보조 복구 경로로 사용한다. 중복 확인은 제한한다.
  앱을 직접 열었을 때도 버튼을 누르기 전에 복구를 시도한다.
- 서비스는 암호화 설정을 초기화하기 전에 포그라운드 알림을 등록한다.
- OS가 시작을 거부하면 오류 클래스와 복구 경로를 기록한다. 기존의 조용한 실패 대신
  설정 보호 진단에 `Monitor recovery:`가 표시된다. PIN/비밀번호/앱 목록은 기록하지 않는다.

## 실제 기기 확인

1. 새 APK를 설치하고 PIN/기본 권한을 확인한 뒤 **Turn on protection**을 한 번 누른다.
2. 잠글 앱 하나와 삭제 방지를 설정한다. OpenLock을 닫고 전원을 완전히 껐다 켠다.
3. 기기 잠금을 처음 해제한 뒤 OpenLock을 열지 않은 채 대상 앱을 실행한다.
   PIN이 표시되는지, 보호 알림이 있는지 확인한다.
4. 설정의 System Service 상세/특별한 권한/앱 정보 보호도 확인한다.
5. 선택 앱과 스케줄 없이 삭제 방지만 켠 경우에도 반복한다.
6. 명시적으로 서비스를 중지한 경우에는 부팅/접근성 재연결 후 다시 켜지지 않는지 확인한다.
7. 화면만 껐다 켜기, 앱 업데이트, 접근성 서비스 재연결도 각각 확인한다.
8. 실패하면 **Turn on protection을 다시 누르기 전에** 설정 보호 진단을 복사한다.
   `Monitor recovery:`와 Permissions의 `serviceRunning`을 확인한다.
   앱을 여는 것만으로 복구된 경우에도 그 결과를 구분해 기록한다.

선택적으로 ADB에서 프로세스 시작부터 수집한다:

```text
adb logcat -s OpenLockStartup ActivityManager AndroidRuntime
```

`MonitorRestartPolicyTest`는 기존 설정 이관, 삭제 방지만 사용, 명시적 중지 우선,
PIN 미설정 제외를 검사한다. 부팅 브로드캐스트와 OEM의 실제 서비스 실행은 기기 테스트가 필요하다.

## Android 및 제조사 조건

일반 저장소의 PIN/설정은 재부팅 직후 첫 기기 잠금 해제 전에는 읽지 않는다.
Direct Boot 지원을 위해 PIN을 다른 저장소로 옮기지 않는다.
제조사가 자동 실행을 막거나 앱을 강제 종료한 경우까지 자동 복구를 보장하지 않는다.
삼성의 절전/백그라운드 제한과 Xiaomi의 자동 실행/배터리 설정도 실제 기기에서 확인한다.

- [Android: 부팅 및 백그라운드 포그라운드 서비스 시작 조건](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android: 재부팅 후 암호화 저장소 접근](https://developer.android.com/privacy-and-security/direct-boot)
