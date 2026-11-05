# 설정 화면 보호 변경 및 기기 테스트

## 삼성에서 새 화면 감지가 되지 않는 경우: 두 번째 수정

2026-11-06: 삼성에서 화면을 껐다 켠 후에도 새 보호가 작동하지 않는다는 보고가 있었다.
실제 트리가 없으므로 단일 원인을 확정하지 않았다. 코드에서 확인한 누락은 툴바 외부의
제목, paneTitle, semantic heading, stateDescription을 감지에 쓰지 않았다는 점이다.
이번 수정은 이 신호들과 목록/클릭 가능한 행 구분을 추가하고 제목의 줄바꿈·특수 공백을 정규화한다.
`flagIncludeNotImportantViews`도 요청해 기본 트리에서 빠질 수 있는 제목/구조 노드 조회를 보완한다.
pane 제목 이벤트는 현재 창 ID가 일치하는 즉시 검사에만 사용한다.
일반 event.text 전체를 제목으로 사용하거나 이전 화면의 제목을 다음 화면에 유지하지 않는다.

**새 APK 확인/진단 절차 — ADB 불필요:**

1. 변경 사항을 커밋·푸시한 뒤 해당 커밋의 **Build preview APK** 실행에서
   **openlock-preview-apk** Artifact를 내려받아 설치한다.
   앱 내부의 업데이트 다운로드는 공식 릴리스를 받을 수 있으므로 이번 커밋의 Artifact를 사용한다.
2. OpenLock → Settings → Advanced → **설정 보호 진단**을 연다.
   보고서 첫 줄에 **settings-guard-2**와 빌드 커밋 앞 12자리가 표시되어야 한다.
   이 메뉴가 없다면 이번 수정이 포함된 APK가 아니다.
3. **진단 시작** → **닫기**를 누른다. 이때 인증 세션/잠금 정책은 바꾸지 않는다.
4. 화면을 껐다 켠 다음 문제의 특별한 접근 화면을 연다. OpenLock으로 돌아와
   진단 창의 **보고서 복사**를 누른다. 공유 전 개인정보를 확인한다.
5. 접근성 서비스 상세 화면도 별도로 위 순서로 재현한다. 각 보고서에 화면 이름을 붙인다.
   서비스 자체를 끄면 수집이 중단되므로 상세 화면 진입만 먼저 재현한다.

수집은 사용자가 시작할 때만 켜지며 최대 10분, 최근 8개 화면, 화면당 최대 120개 노드로
제한한다. 설정/설치 프로그램의 보이는 문구와 ID/클래스·pane/heading·목록 소속을
기기 메모리에 보관하며 편집/암호 필드는 제외한다. 보고서 복사 시 수집을 중지한다.
앱 종료 후 유지할 파일을 쓰거나 서버에 전송하지 않는다. 만료는 추가 수집을 멈추며,
수집한 내용은 새 진단 시작 또는 앱 프로세스 종료 시 제거된다.
일반 CI 서명 APK에도 들어가므로 아래의 선택형 logcat 빌드를 따로 만들 필요가 없다.

보고서는 권한 상태·PIN 존재 여부(값 제외)·보호 활성 상태, 감지된 화면 여부,
세션/중복 실행 방지로 잠금이 생략됐는지, 잠금 실행 요청/실패/Activity 표시 여부를 포함한다.
따라서 제목 인식 실패, 다른 패키지로 위임, 루트 조회 실패, 감지는 됐지만 잠금이 뜨지 않는
경우를 구분할 수 있다. 실제 삼성 트리 수집 전에는 실기기 해결을 확정하지 않는다.

새 회귀 사례는 툴바 없는 TextView/pane/heading, 목록·클릭 행 제외, 제목 정규화이다.
위젯 테스트는 진단이 자동 시작되지 않고 복사 시 수집이 중지되는 흐름을 검사한다.
로컬 Flutter/Java 부재는 동일하여 이 테스트들과 빌드의 실행 결과는 Actions 확인이 필요하다.

API 참고: [paneTitle](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getPaneTitle()),
[pane 제목 이벤트](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#CONTENT_CHANGE_TYPE_PANE_TITLE).

## 구현 범위

`UninstallGuard.kt`의 기존 `isAboutOwnApp` 규칙은 유지하고, 새 진입점
`isGuardedScreen`에서 다음을 추가 검사한다.

- OpenLock 접근성 서비스 상세: 실제 서비스 리소스 이름과 툴바 제목을 비교하고,
  스위치 또는 활성 상태 + 비활성화 버튼을 함께 요구한다. 전용 상세 fragment가
  노출되는 경우에는 해당 클래스와 서비스 이름을 조합한다.
- 서비스 비활성화 확인 대화상자: 상세 진입을 먼저 감지하는 것이 기본이며,
  빠른 탭으로 확인창이 먼저 나타난 경우에는 서비스 이름·대화상자 제목·확인 버튼을 검사한다.
- 특별한 접근 최상위·하위 권한 목록·앱별 상세: 알려진 전용 클래스, 실제 툴바의
  권한 제목, 또는 단일 앱 헤더 + 스위치 + 구체적인 권한 허용 문구로 판별한다.
  특별한 접근에서는 OpenLock 이외 앱의 권한 관리 화면도 보호 대상이다.
- 한국어·영어 등의 제목을 보완적으로 사용하며, 설정 앱의 알려진 문자열 리소스를
  현재 언어로 읽어 제목 후보에 추가한다. 리소스가 없는 제조사에서는 기본 규칙을 사용한다.
- 일반 `android:id/title`만으로 화면 제목이라고 판단하지 않는다.
  툴바/전용 제목 ID 외에도 목록 밖의 클릭 불가능한 TextView/heading과 paneTitle을 사용한다.
  일반 Apps 목록·설정 검색·다른 앱의
  일반 앱 정보·기기 관리자 목록은 새 보호 대상으로 추가하지 않는다.
  기존 OpenLock 자체 앱 정보 및 하위 화면 보호는 계속 적용된다.

`OpenLockGuardAccessibilityService.kt`는 현재 창 ID와 일치하는 클래스만 사용한다.
보이는 노드의 text와 content description을 모두 검사하며 편집·암호 필드는 제외한다.
화면 초기 로딩을 위해 진입 즉시 검사 후 75/200/500ms에 현재 활성 창을 다시 읽는다.
후속 content 이벤트도 검사한다. XML의 notificationTimeout은 150ms에서 0으로 줄였다.

`GuardedScreen` 감지 콜백이 `OpenLockMonitorService`의 기존 보호 판단을 즉시 호출한다.
300ms 폴링도 같은 함수를 사용한다. PIN 설정과 Prevent uninstall 활성화 조건,
기존 1.5초 중복 실행 방지, 기존 `LockActivity`와 `LockSession`은 유지한다.
새 잠금 UI나 새로운 인증 방법을 추가하지 않는다.

## 인증 세션 동작

기존 동작 그대로 **설정 패키지에 대해 인증하면 화면을 끌 때까지 허용**한다.
따라서 인증 후 설정으로 돌아가 접근성 서비스를 끄거나 특수 권한을 변경할 수 있다.
같은 세션에서 다른 보호 설정 화면으로 이동해도 다시 인증하지 않는다.
화면을 껐다 켜면 다시 인증해야 한다. 기존 생체 인증 옵션도 그대로 적용된다.
무조건 매 화면마다 PIN을 요구하는 정책은 이번 변경에 포함하지 않았다.

## 검증 상태

- 기존 `UninstallGuardMatchingTest`는 새 통합 진입점을 사용하도록 변경했다.
  앱 정보·삭제 확인·기기 관리자 화면에 대한 기존 양성/음성 사례가 포함된다.
- `SettingsProtectionMatchingTest`에는 상세/목록 구분, 한국어/영어 버튼 형태,
  제목 로딩 전후, 언어 독립 클래스, 앱별 특수 권한 상세, 검색 오탐 방지 사례를 추가했다.
- 테스트의 화면 노드는 **설계를 검증하기 위한 가상 데이터**이다.
  Samsung / Xiaomi / Huawei에서 수집한 실제 트리가 아니다.
- 로컬에서 `flutter build apk --release`를 시도했으나 Flutter 실행 파일을 찾지 못했다.
  `android/gradlew.bat :app:testDebugUnitTest`도 Java 및 JAVA_HOME 부재로 시작하지 못했다.
  따라서 Kotlin 컴파일·JUnit·Flutter 테스트·APK 빌드 성공을 아직 확인하지 못했다.
- 로컬 정적 확인: 변경 diff 공백 검사, XML 파싱, workflow YAML 구문 및 단계 연결 확인.
  컴파일 및 실기기 검증을 대체하지 않는다.

## GitHub Actions 빌드

변경 사항을 GitHub 저장소에 커밋·푸시한 다음:

1. GitHub → Actions → **Build preview APK** → **Run workflow**.
2. 평소에는 `guard_diagnostics`를 끈 상태로 실행한다.
3. Flutter 테스트 → release APK 빌드 → Kotlin JVM 테스트가 모두 성공하면
   기존 이름인 **openlock-preview-apk** Artifact가 업로드된다.
4. APK를 내려받아 테스트 기기에 설치한다. 서명이 다르면 기존 앱 위에 업데이트할 수 없다.
   앱 삭제 시 PIN과 설정이 초기화될 수 있다. 서명 키 설정은 이번 변경에 포함하지 않았다.

이번 작업에서는 원격 푸시 및 Actions 실행을 하지 않았다.

## 실기기 체크리스트

Samsung Galaxy / One UI, Xiaomi Redmi Note 14 Pro 5G / HyperOS 3.0.8.0 / Android 16,
Huawei / EMUI에서 각각 수행한다. Huawei는 모델·EMUI·Android 버전을 함께 기록한다.
가능하면 한국어와 영어 UI 모두에서 확인한다.

준비: PIN, Prevent uninstall, 사용정보 접근, 다른 앱 위에 표시, 접근성 서비스를 활성화하고
모니터가 실행 중인지 확인한다. 각 **미인증 진입** 테스트 직전에 화면을 껐다 켜서
이전 설정 인증 세션을 끝낸다.

| 진입/조작 | 기대 결과 |
| --- | --- |
| 접근성 → 설치된 앱 목록 | OpenLock 행이 보이더라도 잠기지 않음 |
| OpenLock uninstall guard 상세 진입 | 비활성화 버튼을 누르기 전에 잠금 표시 |
| 상세 진입 직후 즉시 비활성화 버튼 탭 | 가능한 경쟁 조건 확인. 확인창이 먼저 뜨는지도 기록 |
| 다른 접근성 서비스 상세 | 새 보호 규칙 때문에 잠기지 않음 |
| 앱 → 특별한 접근 최상위 | 인증 전 잠금 표시 |
| 특별한 접근 → 다른 앱 위에 표시 / 알람 및 리마인더 | 권한 목록에서 잠금 표시 |
| 위 목록의 OpenLock 및 다른 앱 상세에 직접 진입 | 권한 변경 전에 잠금 표시 |
| 사용정보 접근·모든 파일 접근·설정 변경 등 사용 가능한 다른 특수 권한 | 최상위 경유 및 직접 진입 모두 확인 |
| EMUI: Settings → Apps → Apps → More → Special access | 최상위 및 하위 화면 보호 확인 |
| 일반 앱 목록·다른 앱의 일반 앱 정보·설정 검색 | 새 규칙으로 불필요하게 잠기지 않음 |
| OpenLock 앱 정보·삭제 확인·기기 관리자 해제 | 기존 보호 유지 |
| 올바른 PIN / 기존에 허용된 생체 인증 | 설정 복귀 후 변경 가능, 즉시 재잠금 없음 |
| 인증 후 화면 껐다 켜기 | 보호 화면에서 재인증 |
| 틀린 PIN·뒤로가기·최근 앱·빠른 화면 전환 | 인증 없이 보호 화면 사용 불가, 중복 화면 쌓임 없음 |
| Prevent uninstall 해제 / PIN 미설정 | 새 설정 화면 보호도 동작하지 않음 |
| 재부팅·백그라운드 정리·절전 모드 | 모니터/접근성 상태 및 재진입 동작 확인 |

특수 권한 관리용 앱 목록은 의도적으로 보호한다. 일반 설치 앱 목록과 구분해서 확인한다.
자동 화면 복귀·가로 모드·큰 글꼴·스크롤·설정 검색을 통한 직접 진입도 확인한다.

## OEM 진단 로그 수집

화면 정보가 부족한 OEM 화면은 추측으로 패키지 전체를 차단하지 않는다.
현재 후보는 `com.android.settings`와 기존 package installer 패키지들이다.
제조사가 다른 패키지(예: 별도 권한 관리자)에 화면을 위임하면 추가 규칙이 필요하다.

1. Actions 실행 시 **guard_diagnostics**를 켜서 진단 APK를 빌드한다.
2. PC에서 ADB와 USB 디버깅을 준비하고 연결한다.
3. 다음 명령을 실행한 채 문제 화면을 재현한다.

   ```text
   adb logcat -v time OpenLockGuard:D *:S
   ```

로그에는 패키지·window class·판별 결과·보이는 노드 ID/클래스·툴바 소속·스위치/클릭 여부가
출력된다. 텍스트는 툴바/전용 제목과 OpenLock 이름만 기록하고 일반 행/설명은 가린다.
최대 120개 노드를 출력하며 동일 구조는 1초에 한 번으로 제한한다.
후보가 아닌 패키지는 이름/클래스만 출력한다. 진단 기본값은 꺼짐이고 서버로 전송하지 않는다.
실제 화면 제목에 앱 이름 등이 포함될 수 있으므로 공유 전 로그를 확인한다.

전체 트리가 필요하면 본인이 관리하는 테스트 기기에서 잠시 보호를 끄고 해당 설정 화면에
머무른 상태로 아래 명령을 사용한다. 확인 후 보호와 권한을 복구한다.

```text
adb shell uiautomator dump /sdcard/openlock-settings.xml
adb pull /sdcard/openlock-settings.xml
```

이 덤프는 접근성 서비스가 읽은 트리와 완전히 같다고 보장할 수 없다.
덤프와 로그에 기종/OS/언어/진입 경로를 붙여 확인한 뒤 실제 ID/클래스 기반 회귀 사례를 추가한다.

## 한계와 참고 자료

이 변경은 시스템 권한을 강제로 유지하는 기능이 아니다. 감지부터 잠금 표시까지의 짧은
경쟁 구간, OEM의 백그라운드 실행 제한, 권한 해제는 실기기에서 확인해야 한다.
접근성이 꺼지면 화면 감지가 중단되며 자동으로 다시 켤 수 없다.
기존 앱의 `hasScreenGuardAccess`는 설정된 서비스 상태를 조회할 수 있지만 화면 감지를
대체하지 않는다. `onDestroy`가 프로세스 강제 종료 때 반드시 실행되는 것도 아니다.
안전 모드·ADB·초기화까지 차단한다고 주장하지 않는다.

설계 참고용 AOSP 자료이며 OEM 호환성 증거는 아니다:

- [Android 16 접근성 서비스 상세 구현](https://raw.githubusercontent.com/aosp-mirror/platform_packages_apps_settings/android16-release/src/com/android/settings/accessibility/ToggleAccessibilityServicePreferenceFragment.java)
- [AOSP 설정의 권한 관리 전용 Activity들](https://android.googlesource.com/platform/packages/apps/Settings/+/021f36b3fa9/src/com/android/settings/Settings.java)
