# 설정 화면 보호 변경 및 기기 테스트

선택 기능인 ADB 권한 기반 접근성 자동 복구의 설정과 기기 검증은
[ACCESSIBILITY_RECOVERY.md](ACCESSIBILITY_RECOVERY.md)에 정리했다.

## 메인 스레드 대기 중 스택 자동 채집

현재 진단 버전은 **settings-guard-8-entry3**이다. `entry2 commit=3885764f04f1`의
느린 특별한 접근 진입에서는 창 변경 이벤트가 약 1,481ms 늦게 콜백에 도착했지만,
그 후 조회·판정은 31ms, PIN 요청 후 첫 그리기는 55ms였다. 150ms 재검사 타이머도
약 1,440ms 늦게 실행됐다. `eventDeliveryMs`에는 앱 메인 스레드에서 기다린 시간도
포함되므로 이 기록만으로 삼성의 이벤트 전송 지연이라고 결론 내리지 않는다.

진단을 명시적으로 켜면 별도 `OpenLock-stall-sampler` 스레드가 100ms 간격으로
메인 Handler에 응답 확인용 Runnable을 보낸다. 한 번에 하나만 대기시킨다.
응답이 250ms 이상 없으면 **메인 스레드의 현재 Java 스택**과 당시 실행 중인
**화면 조회 작업의 단계·경과 시간·작업 스레드 스택**을 채집한다. 대기가 계속되면
500ms 간격으로 추가 채집한다. 최근 접근성 콜백과 재검사 상태도 함께 기록한다.
이 감시는 메인 스레드의 타이머를 사용하지 않으며 기존 보호·인증 정책은 바꾸지 않는다.

- `Main-thread stall sampler`: 총 채집 수, 늦은 응답 횟수, 보관 수. 최근 24개 기록과
  스레드별 최대 48개 프레임만 메모리에 남긴다. 파일 저장·업로드는 하지 않는다.
- `Sampler heartbeat` / `Last main probe acknowledgement`: 감시 스레드와 메인 스레드가
  실제로 언제 실행됐는지 확인한다. 감시 스레드도 늦으면 최대 실행 간격에 드러난다.
- `Main-thread stall sample`: 같은 probe 번호의 반복 스택으로 대기가 지속됐는지 본다.
  `pendingForMs`는 probe를 보낸 뒤 경과 시간이다. 실제 정지 시작 시각과 같지는 않다.
- `Main probe recovered`: 메인 Runnable이 실행될 때까지 걸린 시간. 감시 스레드도
  늦어서 대기 중 스택을 놓쳤더라도 늦은 응답 자체는 남긴다. 이 기록만으로 과거의
  호출 스택을 알 수는 없다.
- `ackAtEndUptimeMs`가 null이 아니면 스택을 채집하는 도중 응답이 돌아온 경우다.
  두 스레드의 스택과 상태는 원자적으로 동시에 채집되는 것이 아니다.

지연 기준에는 deep sleep을 제외하는 uptime을 사용한다. 10분 제한에는 기존처럼
elapsedRealtime을 사용하며 별도 감시 스레드가 스스로 종료한다. 화면을 깨우거나
wakelock을 얻지 않는다. 진단 중지 시 남은 probe와 감시 작업을 정리하고 기록은
복사용으로 보관한다. 다시 시작하면 이전 캡처의 기록과 감시 작업을 교체한다.
스택에는 호출 위치만 있고 메서드 인자·PIN·지역 변수 값은 없다. 기존 화면 스냅샷은
별도 기능이므로 보고서를 공유할 때는 이전과 같이 내용을 확인한다.

기기에서 확인할 순서:

1. 새 APK에서 진단을 켜고 헤더가 `settings-guard-8-entry3`인지 확인한다.
2. 화면을 껐다 켠 뒤 특별한 접근·앱 정보 등에서 느린 순간을 재현한다.
3. 앱으로 돌아와 진단을 중지하고 보고서를 복사한다. `Main-thread stall sample`과
   `Main probe recovered`, `Sampler heartbeat`가 함께 포함됐는지 확인한다.
4. 같은 probe에서 Binder 응답 대기, 모니터/잠금 대기, 앱 코드 실행 등이 반복되는지
   기존 이벤트·조회 시각과 비교한다. 스택 한 장만으로 잠금 소유자나 삼성 내부 원인을
   확정하지 않는다. Java 프레임으로 보이지 않는 네이티브 대기도 있을 수 있다.
5. 중지 후 기록이 늘지 않는지, 재시작하면 새 캡처만 남는지, 10분 자동 종료가 되는지,
   화면을 꺼 둔 시간만으로 큰 지연이 생기지 않는지 확인한다. 기존 PIN 인증·취소,
   서비스 보호 및 휴대폰 전체 잠금도 확인한다.

`GuardHeartbeatTest`는 가상 uptime으로 단일 대기 probe, 임계값·반복 간격, 늦은
감시 실행과 복구, 재시작된 캡처 간 격리, deep sleep 시간 제외를 검사한다.
실제 Android 스택 채집과 생명주기는 기기 검증이 필요하다. 이번 변경은 원인 확인을
위한 계측이며 지연 자체를 해결했다고 주장하지 않는다.

## 삼성 앱 목록 인식 누락과 트리 순회 대기 수정

현재 진단 버전은 **settings-guard-8-entry2**이다. `settings-guard-8-entry commit=b5884d6813ad`
보고서에 잡힌 느린 **OpenLock 앱 정보 진입**은 이전 보고서와 병목이 달랐다.

| 구간 | 기록 |
| --- | --- |
| 창 변경 이벤트 전달 | 1ms |
| 검사 작업 대기 / root 조회 | 8ms / 1ms |
| root 이후 트리 조회·판별 | 1,171ms |
| 잠금 요청 → 첫 onDraw | 47ms |

`source=window-scan`이므로 앱 목록 클릭 조기 감지는 이 진입에 사용되지 않았다.
실제 창에는 `SubSettings` 클래스와 `com.android.settings:id/apps_list` RecyclerView,
OpenLock의 `android:id/title` 행이 있었지만 toolbar에는 앱 목록 제목이 없었다.
기존 코드는 제목 또는 전용 Activity 클래스가 필요했으므로 목록임을 인정하지 못했다.

수정 내용:

- 확인된 Samsung `apps_list` RecyclerView와 OpenLock 행도 앱 목록 근거로 인정한다.
  앱 목록을 보기만 할 때에는 계속 PIN을 띄우지 않는다.
- Settings 행의 이벤트 이름은 정확한 OpenLock 이름 뒤의 쉼표 하나도 허용한다.
  보고서의 행 노드에 `OpenLock,` 형태가 보였지만 실제 클릭 이벤트 텍스트는 기록에
  없으므로 이것이 이번 클릭 실패의 추가 원인이었다고 확정하지는 않는다.
  `OpenLock Pro,` 또는 `OpenLock, shortcut` 같은 다른 이름/설명은 허용하지 않는다.
- 기존 순회는 부모의 자식을 역순으로 모두 조회한 뒤 첫 자식을 방문했다. 이제
  첫 자식을 조회·방문한 다음 다음 형제를 조회한다. 앞쪽에 보호 근거가 있으면
  방문하지 않을 뒤쪽 형제의 원격 응답을 기다리지 않는다.
- `entity_header_title`/`admin_name`이 OpenLock임을 확인하면 검사 간격이 올 때까지
  기다리지 않고 기존 판별 함수를 즉시 호출한다. 판별 규칙 자체는 유지한다.
- 창이 바뀌어 검사 결과가 무효가 되면 매 노드 방문 시 중단한다. 정상 음성 판정은
  기존의 최대 600개 범위에서 끝까지 검사하고, 중단/실패 시 보유 노드를 정리한다.

보고서에서 재검사 타이머도 약 1초 늦게 실행된 흔적이 있으므로 공통 대기가 전부
해결됐다고 보지 않는다. 1,171ms 중 어느 개별 노드 조회나 CPU 처리에 시간이 들었는지는
이 보고서만으로 확정할 수 없다. 이번 수정은 확인된 목록 인식 실패와 불필요한
순회·형제 선조회 경로를 개선한다. 다른 보호 화면의 지연까지 같은 원인이라고 가정하지 않는다.

새 APK에서 화면을 껐다 켜 진단을 시작하고 같은 앱 목록에서 OpenLock을 누른다.
`source=own-app-row-click`가 나오는지, 조기 클릭이 불가능한 경로에서도 헤더 직후
판정되어 노드 수와 `walkMs`가 줄었는지 확인한다. 다른 앱 정보·일반 목록 비차단,
특별한 권한·기기 관리자·System Service·삭제 보호와 PIN 취소/인증도 검증한다.
`GuardTreeWalkTest`는 조기 중단 전에 불필요한 형제를 조회하지 않는지, 음성 판정 순서,
노드 수 제한과 실패 시 정리를 검사한다. 실제 속도 개선은 기기에서 측정해야 한다.

## 앱 정보 진입 감지 및 기기 관리자 영역 보호

현재 진단 버전은 **settings-guard-8-entry**이다. guard-8 재검사와 기존 PIN 흐름을
유지하며, guard-9의 설정 검사 로딩은 사용하지 않는다.

| 경로 | 조기 감지 조건과 동작 |
| --- | --- |
| 설정 → 애플리케이션 → OpenLock | 현재 창을 앱 목록으로 확인했고 OpenLock 행이 보였을 때, 같은 창에서 그 이름/패키지의 실제 클릭을 받으면 PIN 요청 (`own-app-row-click`) |
| 삼성 서랍 → OpenLock 길게 누르기 → 정보 | One UI Home의 OpenLock 아이콘 long-click 후 5초 안에 정보 버튼 클릭까지 확인하면 PIN 요청 (`launcher-app-info-click`) |
| 기기 관리자 목록/개별 관리자 설정 | 메뉴 클릭 (`device-admin-entry-click`), 구분되는 화면 클래스 (`device-admin-window`), 실제 페이지 제목/트리 중 먼저 확인한 경로로 PIN 요청 |

일반 앱 목록을 보기만 하거나 OpenLock 아이콘을 길게 누르기만 해서는 잠그지 않는다.
다른 아이콘 long-click, 다른 바로가기 클릭, 다른 앱으로 이동, 5초 경과, 서비스 중단은
런처 진입 근거를 무효화한다. 앱 목록의 근거도 창이 바뀌거나 다른 화면을 읽으면 버린다.
런처 팝업에는 다른 창 ID가 붙을 수 있으므로 own-icon 근거와 실제 정보 동작을 함께 쓴다.

기기 관리자 영역은 이제 **다른 앱을 포함한 목록/등록/해제 화면 전체**가 보호 대상이다.
기존 `isAboutOwnApp` 판별은 유지하며 전체 화면 보호를 별도로 추가했다. PIN 인증 후에는
기존 Settings 세션으로 정상적인 관리자 등록/해제를 허용한다. 검색/보안 설정 목록에서
기기 관리자 항목이 보인다는 이유만으로 해당 목록 전체를 잠그지 않는다.

런처 처리는 Samsung One UI Home (`com.sec.android.app.launcher`)의 이벤트 메타데이터만
사용한다. 런처 전체 트리를 새로 수집하지 않으며 다른 런처를 지원한다고 가정하지 않는다.
클릭/long-click에 이름·정보 동작이 없거나 예상하지 않은 클래스라면 기존 앱 정보 헤더
감지가 계속 동작한다. 근거가 부족한 상태에서 모든 정보 버튼이나 앱 목록을 잠그지 않는다.
따라서 이 두 경로에서도 실기기 이벤트 확인 전 조기 감지가 된다고 단정할 수 없다.

삼성 기기에서 확인할 항목:

1. 화면을 껐다 켜 Settings 인증 세션을 만료시키고 진단을 시작한다.
2. 앱 목록을 보기만 하면 PIN이 없고 OpenLock 행을 누르면 `own-app-row-click`가
   기록되는지 확인한다. Chrome 등 다른 앱 정보와 검색 입력은 불필요하게 잠기지 않아야 한다.
3. 서랍의 OpenLock 아이콘 long-click만으로 PIN이 없어야 한다. 바로 정보 버튼을 누르면
   `launcher-app-info-click`가 기록되는지 확인한다. 5초 넘게 기다린 경우에는 기존 상세
   화면 감지를 사용한다. 다른 앱 아이콘/바로가기와 섞어도 잘못된 정보 버튼을 차단하지 않는지 본다.
4. 런처 조기 감지가 없다면 `own-launcher-long-click`, `own-launcher-action` 스냅샷과
   launcher click/long-click 이벤트 이력을 확인한다. action 스냅샷은 명시적으로 진단을
   켜고 own-icon 근거가 남아 있을 때만 메타데이터 일부를 메모리에 기록한다.
5. 기기 관리자 목록 진입, OpenLock 외 관리자 상세, OpenLock의 등록/해제에서 PIN을
   확인한다. 인증 후 등록/해제가 가능하고 PIN 뒤로가기 취소가 루프 없이 동작해야 한다.
6. 특별한 접근, System Service, 삭제 보호, 화면 끄기/켜기, 일반 앱 및 휴대폰 전체
   스케줄 잠금을 회귀 검증한다. Xiaomi/Huawei에서는 기존 화면 감지의 유지 여부를 별도로 본다.

`AppInfoEntryGuardTest`와 `DeviceAdminProtectionTest`를 추가하고 기존 기기 관리자 테스트를
새 보호 범위에 맞췄다. 테스트는 합성 이벤트/트리이며 기기 호환성의 증거가 아니다.
Java/Flutter가 없는 로컬에서는 실행할 수 없어 Actions에서 Kotlin 테스트와 APK 빌드를
확인해야 한다. 이벤트 자체가 늦게 전달되는 경우의 지연은 남을 수 있다.

참고: One UI Home 패키지는 [Samsung 문서](https://www.samsung.com/ae/b2b-faqs/samsung-knox/how-do-i-set-a-custom-launcher-with-the-navigation-bar-using-knox-configure/)에서 확인했다.
이 문서는 최신 One UI의 정보 버튼 이벤트 형식을 보장하지 않는다. 이전 절의
기기 관리자 목록 비차단 설명은 당시 구현에 대한 기록이며 현재 정책은 이 절이 우선한다.

## guard-8 복귀 및 특별한 접근 클릭 감지

설정 검사 전 로딩을 띄우던 guard-9 변경을 제거하고 `2905459`의 guard-8 흐름으로
되돌렸다. 기존 PIN 취소 후 뒤로가기 로딩과 휴대폰 전체 잠금 스케줄은 유지한다.
이번 진단 헤더는 **settings-guard-8-click**이다.

- 실제 `TYPE_VIEW_CLICKED` 이벤트가 특별한 접근/권한 또는 알려진 하위 특수 권한
  항목을 정확히 가리키면 상세 화면 구조를 기다리지 않고 기존 Settings PIN을 요청한다.
  진단에는 `source=special-access-entry-click`로 기록한다.
- 삼성 팝업 메뉴의 TextView와 일반 설정 항목의 컨테이너 클릭을 허용한다.
  메뉴 팝업의 창 ID가 달라져도 현재 Settings에 있는 상태에서 온 정확한 클릭은
  사용할 수 있다. 기존 System Service 클릭의 동일 창 검사는 유지한다.
- 이벤트의 텍스트/설명/클래스만 사용하고 `event.source`나 root를 클릭 시점에
  동기 조회하지 않는다. 현지화된 Settings 제목은 기존 작업 스레드가 읽어 둔
  값을 사용한다. 이 값이 준비되지 않았다면 기본 한/영 등 제목을 사용한다.
- EditText/SearchView/스위치/전체 ListView 등의 소스는 조기 감지에서 제외한다.
  검색 화면과 알 수 없는 OEM/Compose 클릭도 기존 상세 화면 검사로 처리한다.
  항목을 보기만 하거나 일부 문자열만 일치하는 경우에는 클릭 잠금을 요청하지 않는다.
- 기존 접근성 상세/특별한 접근 하위 화면/자기 앱 정보/기기 관리자/삭제 보호와
  150ms 재검사, PIN 인증 세션은 유지한다. 새로운 검사 로딩이나 3초 인증 전환은 없다.

확인할 항목:

1. 화면을 껐다 켜고 Settings → Apps → More 메뉴를 연다. 메뉴를 보기만 할 때는
   PIN이 나오지 않고, 특별한 접근을 누르면 PIN이 나오는지 확인한다.
2. 진단에서 `source=special-access-entry-click`가 기록되는지 확인한다.
   기록이 없다면 기기가 클릭 이벤트에 정확한 항목 이름을 제공하는지 확인해야 한다.
3. System Service, 앱 정보, 기기 관리자, 삭제 보호와 PIN 취소/인증이 유지되는지 본다.
4. 일반 메뉴/앱 목록/검색 입력 및 스크롤에 새 로딩이나 불필요한 PIN이 없는지 본다.
5. 직접 링크나 지원하지 않는 클릭 클래스로 들어갈 때 기존 상세 화면 감지가
   유지되는지 확인하고, 삼성 확인 후 Xiaomi/HyperOS·Huawei/EMUI에서 별도로 검증한다.

`SpecialAccessEntryClickTest`는 메뉴/행, 언어·현지화, 입력·목록·검색 제외와
수동으로 메뉴를 보기만 하는 경우의 비차단을 검증한다. Java/Flutter가 없는 로컬에서는
실행할 수 없어 Actions 빌드/테스트와 실제 기기 검증이 필요하다.
클릭 이벤트 전달 자체가 늦거나 정보가 부족한 경우의 지연까지 해결했다고 보지 않는다.

앱 정보에 대한 검토 결과: 우선 `com.android.settings:id/entity_header_title`을
직접 조회해 OpenLock 이름/패키지를 확인하고, 실패하면 기존 트리 탐색으로 돌아가는
방법이 후보이다. 다른 앱과 동일한 앱 정보 화면 클래스를 쓰므로 클래스만으로 잠그지
않는다. 설정 앱 목록·검색·런처의 앱 정보 진입은 클릭 형태가 달라 별도 증거가 필요하다.
헤더 우선 조회는 아직 적용하지 않았다. root/시스템 조회 자체가 늦으면 이 방법도
기다릴 수 있으므로 실제 시간 비교가 필요하다.
참고: [Android resource ID로 노드 조회](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#findAccessibilityNodeInfosByViewId(java.lang.String)).

## 재검사 연속성과 진단 누락: 여덟 번째 수정

`settings-guard-7 commit=bdad044223b1` 삼성 보고서의 마지막 보호 화면은 **특별한 접근**이다.
해당 콘텐츠 이벤트는 `eventDeliveryMs=1205`로 도착했고, 함께 수신한 다른 이벤트도
1,304~1,469ms 늦었다. 수신 이후 잠금 요청까지는 41ms, 요청 이후 첫 onDraw까지는
35ms였다. 이 보고서에서도 주된 지연은 PIN 실행 요청 이전이며, 캐시를 비활성화한
상태에서도 남았다. 캐시 설정만으로 해결됐다고 보지 않는다.

마지막 watchdog 검사는 `startedAt=6242638`, `scanMs=65`였고, 이후 잠금 요청
`6244449`까지 약 1.75초 동안 완료 기록이 없었다. 기존 진단은 창 전환으로 폐기된
검사를 기록하지 않았다. 따라서 이 빈 구간만으로 **재검사가 중단됐는지, root/자식
노드 조회가 기다렸는지 확정할 수 없다.** 이전 창은 특별한 접근 항목이 있는
설정 메뉴 팝업이었으며, 항목이 보인다는 이유만으로 팝업 전체를 보호하지 않는다.

수정 내용:

- 이미 예약한 150ms 재검사 시각을 유지한다. 콘텐츠 이벤트나 다른 검사 완료가
  타이머를 계속 뒤로 미루지 않는다. 정상 적용뿐 아니라 창 전환으로 폐기되거나
  실패한 검사도 완료 후 재검사를 예약한다. 직렬 검사 스레드는 하나로 유지한다.
- Settings 감시 중 잠깐 나타나는 SystemUI 창이나 null root 때문에 감시를 바로
  끄지 않고 최대 2초 동안 현재 창 재검사를 유지한다. 반복된 임시 창은 유예를
  연장하지 않는다. Settings를 다시 확인하면 정상 감시로 복귀한다. 자신의 PIN
  화면이나 다른 앱으로 나가면 즉시 중단한다. 새로 감시를 시작할 때는 실제
  Settings 이벤트 또는 root를 확인해야 한다.
- 보호 화면 판별, 클릭 조기 감지, 인증 세션, 뒤로가기, 300ms 보조 폴링은 유지한다.
  목록의 System Service 행이나 특별한 접근 메뉴 항목만으로 잠그지 않는다.
- 진단 버전은 **settings-guard-8**이다. root 조회, 자식 노드 조회, 메인 전달 대기를
  구분하고, 창 전환으로 버린 결과도 기록한다. 새 창 이벤트 이력에는 SystemUI 등
  다른 패키지도 포함한다. 재검사 예약/대기/중단 사유를 함께 기록한다.

| 추가 진단 | 의미 |
| --- | --- |
| Settings watchdog state / state changes | 예약 시각, 진행 중 검사 대기, 정책상 생략, 중단 사유 |
| Active screen read | 아직 끝나지 않은 검사의 단계, 해당 단계 대기 시간, 읽은 노드 수 |
| Read unfinished at capture stop | 진단 중지 당시 미완료 읽기의 단계와 시간. 현재도 실행 중이라는 의미는 아님 |
| Recent screen read outcomes | 적용/폐기/실패를 포함한 최근 검사 완료 이력 |
| accepted / outcome | 창 전환 등으로 결과가 버려졌는지와 사유 |
| rootMs / walkMs | root 조회 시간 / 이후 메타데이터·트리·판별·정리 시간 |
| deliveryToMainMs | 작업 스레드 완료 이후 메인 스레드에서 결과 처리까지 |

기기에서 확인할 항목:

1. 새 APK의 진단 헤더가 `settings-guard-8`인지 확인한다. 화면을 껐다 켜고 진단을
   시작한 뒤 System Service 상세와 특별한 접근에 각각 5~10회 진입한다. 인증하면
   Settings 세션이 풀리므로 반복 비교에는 PIN 뒤로가기 취소를 사용한다.
2. 특별한 접근이 보이는 메뉴 팝업, 설치된 접근성 앱 목록, 다른 서비스 상세,
   일반 앱 목록은 계속 사용할 수 있어야 한다. 실제 보호 상세/특별한 접근에
   진입할 때 PIN이 나타나는지 확인한다.
3. 알림창을 잠깐 내렸다 닫은 뒤에도 Settings 감시가 이어지는지, 다른 앱과
   OpenLock으로 이동하면 감시가 중단되는지 확인한다.
4. 느린 순간을 포함한 보고서에서 재검사 예약과 읽기 완료 시각을 비교한다.
   `accepted=false`인 느린 읽기도 확인한다. 재검사가 기다리고 있으면
   `Active screen read`의 root/child/awaiting-main 단계와 대기 시간을 확인한다.
   진단을 중지하고 복사했다면 `Read unfinished at capture stop`도 함께 확인한다.
5. 기존 앱 정보·삭제·기기 관리자 보호, PIN 취소/인증, 화면 끄기/켜기,
   일반 앱 및 휴대폰 전체 스케줄 잠금이 유지되는지 확인한다.

`SettingsWatchStateTest`는 임시 창 유예·복귀·종료 경계를,
`GuardReadProgressTest`는 진행 중 읽기의 시간 구분을 검증한다. 로컬에 Java/Flutter가
없어 실행과 APK 빌드는 Actions에서 확인해야 한다. 이번 변경은 실제 재검사 단절
경로를 보완하지만, 시스템의 이벤트 전달 및 직렬 Binder 조회 대기까지 제거하거나
PIN 표시 속도를 보장하지 않는다. 삼성에서 먼저 측정하고 Xiaomi/HyperOS에서도
별도로 검증해야 한다.

## 이벤트 전달 지연: 일곱 번째 수정

`settings-guard-6 commit=5a2a7ec38b11` 삼성 보고서에서 느린 두 번째 진입은
`source=service-entry-click eventDeliveryMs=1539 eventToRequestMs=1`,
`requestToResumeMs=22 requestToFirstDrawMs=36`이었다. 뒤따른 창/콘텐츠 이벤트도
1,219~1,488ms 늦게 묶여 도착했다. 콜백 이후 PIN 실행보다 **콜백 이전**이 병목이다.
같은 보고서의 첫 번째 진입은 `eventDeliveryMs=7`이었다.

수정 내용:

- 기존 창/콘텐츠/클릭 이벤트 처리를 유지하고, Settings에서 보호가 필요한 동안
  **검사 결과 적용 후 150ms** 뒤 현재 활성 창을 다시 읽는다. 기존 재검사는 진입 후
  500ms에 끝났으므로, 나중에 발생한 클릭 통지가 늦으면 독립적으로 감지할 길이 없었다.
  150ms는 검사 간 대기 시간이며 PIN 표시 시간의 상한이 아니다.
- 별도 작업 스레드를 늘리지 않고 기존 직렬 검사 스레드에서 실행한다. 느린 읽기가
  끝나기 전에 주기적 검사를 계속 쌓지 않는다. 실제 root의 패키지/창을 사용하며,
  늦게 도착한 이벤트의 class, pane 제목, 시간 정보를 주기 검사에 붙이지 않는다.
- 목록의 System Service 행만으로 잠그지 않는다. 기존 `UninstallGuard`의 상세 화면,
  특별한 접근, 자신의 앱 정보/기기 관리자/삭제 확인 판별 규칙을 그대로 사용한다.
  새 터치 차단 창이나 PiP는 추가하지 않는다.
- 화면이 꺼져 있거나, PIN이 표시 중이거나, 인증으로 Settings 세션이 풀렸거나,
  뒤로가기 처리 중이면 주기 검사를 생략한다. Settings 밖으로 나간 것이 확인되면
  주기 검사를 중단한다. 기존 300ms 사용 기록 폴링과 잠금 세션 정책은 유지한다.
- API 33 이상에서 공개 API `setCacheEnabled(false)`를 적용한다. Android는 앱 콜백 전에
  접근성 캐시를 처리하며, 클릭된 캐시 노드를 동기 갱신할 수 있다. 이 경로의 대기를
  피하고 지연된 콘텐츠 이벤트 전에도 새 노드를 읽기 위한 변경이다. **보고서만으로
  캐시 갱신이 원인이라고 확정하지 않는다.** 캐시를 끄면 prefetch도 꺼지므로 실기기에서
  검사 비용을 확인해야 한다. API 32 이하는 기존 플랫폼 캐시를 유지한다.

참고: [캐시 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#setCacheEnabled(boolean)),
[콜백 전 처리 순서](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/AccessibilityService.java),
[클릭 노드 갱신](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/accessibility/AccessibilityCache.java).
삼성 OS의 내부 구현이 이 소스와 같다고 가정하지 않는다.

새 진단 버전은 **settings-guard-7**이다. 다음을 확인한다.

1. 진단을 새로 켜고 `Accessibility reads: nodeCache=disabled (prefetch disabled)`인지 확인한다.
   API <33 또는 실패/거부이면 보고서에 그 상태가 표시된다.
2. 화면을 껐다 켜 인증 세션을 만료시킨 뒤, 설치된 앱 목록에서 2초 이상 기다리고
   System Service에 진입한다. PIN 뒤로가기로 취소하고 느린 순간을 포함해 5~10회 반복한다.
3. 콜백이 늦더라도 `source=window-watchdog`로 PIN이 먼저 실행되는지 확인한다.
   이 경로에는 원인 이벤트가 없으므로 `eventDeliveryMs/eventToRequestMs`를 붙이지 않는다.
   `Last Settings watchdog read`와 `Slow screen reads`에서 검사 시간/메인 전달 대기를 비교한다.
4. 목록을 보기만 하거나 다른 접근성 서비스를 선택할 때 잠기지 않는지 확인한다.
   PIN 인증 뒤 서비스 설정 변경을 허용하고, 취소 뒤 재진입하면 다시 잠기는지도 확인한다.
5. 특별한 접근/앱 정보/기기 관리자/서랍 삭제 보호와 일반 앱 잠금/스케줄/알림 숨김,
   화면 끄기/켜기, 회전, 서비스 재시작을 확인한다. Settings를 벗어나면 주기 검사가 멈춰야 한다.
6. 삼성에서 개선 폭과 `scanMs`를 확인한 뒤 Xiaomi/HyperOS에서도 같은 항목을 확인한다.

노드 조회 자체가 늦거나 앱 메인 스레드/OS가 정체되면 지연은 남을 수 있다.
`Slow usage polls: 0`만으로 메인 스레드나 OS의 정체가 없었다고 결론 내리지 않는다.
빌드/단위 테스트는 이벤트 전달 속도나 빠른 비활성화 방지를 보장하지 않는다.

## 간헐적인 지연: 여섯 번째 수정

새 삼성 보고서의 마지막 기록은 `queueWaitMs=0`, `inspectToMatchMs=7`,
`matchToRequestMs=88`, `requestToResumeMs=9`였다. 클릭 조기 감지도 기록됐다.
이 값만으로 느렸던 모든 진입이나 화면 표시까지의 총 지연을 설명할 수는 없다.

- 사용 기록 조회(`queryEvents` 및 이벤트 순회)를 전용 작업 스레드로 옮긴다.
  알림 정리 시스템 호출도 이 스레드에서 처리한다. 느린 시스템 응답 때문에
  메인 스레드의 접근성 이벤트·즉시 잠금·PIN 그리기가 기다리지 않도록 한다.
- 300ms 보조 폴링을 유지한다. 결과 적용 후 다음 조회를 예약해 조회/결과를
  한 번에 하나만 처리한다. 서비스 중지/재시작이나 접근성에서 보고된 창 전환
  이전의 결과는 적용하지 않는다. PIN 실행·인증·debounce 상태는 메인 스레드에 둔다.
- 진단을 시작했을 때만 이벤트 전달 지연, 느린 사용 기록 조회, 최근 잠금 요청/
  재개/첫 그리기 이력을 각각 최대 16행 보관한다. 마지막 정상 기록이 이전의
  느린 기록을 덮어쓰는 것을 줄인다. 자동 중지와 메모리 내 저장 정책은 유지한다.
- 검사 결과의 시간 정보를 해당 보호 화면에 직접 연결한다. 기존 250ms 제한으로
  느린 검사와 실행 요청의 연결이 끊기던 진단의 빈틈을 없앤다.
- 이 단계의 진단 버전은 **settings-guard-6**이다.

| 항목 | 측정 범위 |
| --- | --- |
| eventDeliveryMs | 이벤트의 OS 시각 → 접근성 콜백 수신. 이벤트 시각이 없거나 유효하지 않으면 unknown |
| eventToRequestMs | 해당 콜백 수신 → 잠금 요청. 재검사면 재검사를 유발한 이벤트 기준 |
| queueWaitMs | 검사 예약 → 검사 스레드 시작 |
| inspectToMatchMs | 검사 시작 → 보호 화면 판정 |
| scanToMainMs | 판정 완료 → 결과의 메인 스레드 전달 |
| matchToRequestMs | 판정 완료 → 잠금 요청. scanToMainMs를 포함 |
| requestToResumeMs | 잠금 요청 → LockActivity 재개 |
| requestToFirstDrawMs | 잠금 요청 → Activity의 첫 onDraw 콜백. GPU 표시 완료 시각은 아님 |
| queryMs / deliveryToMainMs | 보조 사용 기록 조회 시간 / 조회 결과의 메인 스레드 전달 대기 |

이벤트 시간 차이는 uptime, 검사·실행 시간 차이는 elapsedRealtime을 사용한다.
측정값은 물리적인 터치부터 화면 픽셀 표시까지의 총 시간으로 합산하지 않는다.
OEM이 이벤트를 만들기 전의 지연, 실제 화면에 픽셀이 표시되는 시점은 직접 측정하지 않는다.
`source=service-entry-click`과 `source=window-scan`을 구분한다.

실기기에서는 진단을 새로 시작하고, 인증 세션이 잠긴 상태에서 System Service 진입을
5~10회 반복한다. PIN에서 뒤로가기로 취소하고 같은 상세 화면에 재진입하면
인증 없이도 여러 번 비교할 수 있다. 느린 순간이 포함되면 `Recent lock requests/resumes`,
`Recent Settings window/click/slow events`, `Slow usage polls`를 함께 확인한다.
서비스 중지/재시작, 일반 앱 잠금, 스케줄, 알림 내용 숨김, 다른 앱으로 빠른 전환,
삭제/설정 보호와 PIN 뒤로가기 흐름도 확인한다.

`ForegroundPollSessionTest`는 중지·재시작·창 전환 후 오래된 결과 배제를,
`GuardTimingTest`는 지연 구간 계산 및 250ms를 넘는 기록 보존을 검사한다.
로컬 Java/Flutter SDK가 없어 테스트/빌드는 Actions에서, 실제 개선 폭은 기기에서 확인해야 한다.

## 삼성 접근성 화면 지연: 다섯 번째 수정

실제 삼성 진단에서 설치된 앱 목록 검사 한 번에 `scanMs=1537`이 기록됐다.
기록된 보호 화면 판정은 4ms, 잠금 요청부터 Activity 재개까지는 10ms였다.
이 값은 서로 다른 시점의 기록이며 전체 진입 지연이나 첫 화면 표시 시간을 뜻하지 않는다.

- 동기식 화면 트리 읽기를 별도 HandlerThread로 옮긴다. 메인 스레드의 접근성 이벤트,
  모니터, PIN Activity가 느린 노드 응답을 함께 기다리지 않도록 한다.
- 대기 중인 검사는 최신 요청 하나로 합친다. 창 변경/진입 클릭 이전의 결과는 버리고,
  중단/종료 후 결과를 적용하지 않는다. UI·인증·뒤로가기 상태는 메인 스레드에서만 변경한다.
- 실제 클릭 이벤트가 현재 설정 창의 서비스 이름과 정확히 일치하면 상세 화면 로딩 전에
  기존 잠금 흐름을 호출한다. 이 경로는 event.source/root를 동기 조회하지 않는다.
  입력 필드/목록 컨테이너 클래스는 허용하지 않으며, 클릭 이벤트 정보가 부족하면
  기존 창/콘텐츠 검사로 처리한다. 목록을 보기만 하는 경우에는 잠그지 않는다.
- 자신의 명확한 toolbar/pane 제목이 보이면 스위치나 상태 문구 로딩을 기다리지 않는다.
  일반 텍스트 제목의 추정 규칙에는 기존 추가 조건을 유지한다.
- 진단 버전은 **settings-guard-5**이며 `queueWaitMs`는 검사 요청부터 작업 시작까지이다.

Actions에서 Kotlin 테스트/빌드를 확인한 뒤, 새 APK로 다음을 실제 기기에서 확인한다.

1. 화면을 껐다 켜 인증 세션을 만료시킨 뒤 설치된 앱 목록만 보면 PIN이 나타나지 않는지.
2. System Service를 누르면 PIN이 얼마나 빨리 표시되는지. 다른 서비스는 잠그지 않는지.
3. 검색/직접 링크로 상세 화면에 들어가도 제목 기반 감지가 되는지.
4. 진입 직후 빠르게 화면을 옮겨도 이전 창의 검사 결과로 잘못 잠기지 않는지.
5. PIN 뒤로가기, 특별한 접근, 앱 정보, 기기 관리자, 삭제 확인 보호가 유지되는지.
6. 진단을 새로 시작해 `queueWaitMs`, `inspectToMatchMs`, `requestToResumeMs`를 비교한다.
   PIN 인증 후 `sessionUnlocked=true`로 잠금을 생략하는 기존 정책은 유지한다.

노드 조회 자체의 지연 및 이벤트 도착 전의 경쟁 구간은 남을 수 있다.
실제 속도 개선이나 빠른 비활성화 차단을 빌드만으로 보장하지 않는다.

## 보호 화면 진입 후 PIN 표시 지연: 네 번째 수정

사용자가 느리다고 지적한 구간은 PIN의 뒤로가기가 아니라 **보호 화면 진입 → PIN 표시**이다.
화면은 Android 접근성 이벤트로 검사하고, `UninstallGuard`에서 다음 신호를 조합한다.
설정/설치 프로그램 패키지, 현재 창과 일치하는 화면 클래스, 실제 제목/paneTitle,
단일 앱 헤더의 앱 이름/패키지, 서비스 이름과 스위치/비활성화 버튼이다.
일반 앱 목록의 행 이름만으로는 감지하지 않는다.

이전에는 매번 최대 600개 노드의 탐색을 마친 뒤 판단했다. 이번 변경:

- 알려진 특별한 접근 전용 클래스와 현재 창에서 확인된 pane 제목을 먼저 검사한다.
- 그 외에는 루트와 초반 8개 단위로 기존 판별 규칙을 검사하고,
  32개 이후에는 64/128/256/512개처럼 간격을 넓힌다.
  보호를 입증하는 단서가 충분하면 나머지 자식 노드를 조회하지 않고 즉시 잠금을 요청한다.
- 비보호 판정은 기존처럼 제한 내의 전체 탐색을 거친다. 뒤로 이동 후 안전한 화면인지
  판단할 때도 부분적인 정보만으로 보호를 해제하지 않는다.
- 고정 제목/버튼/권한 문구의 정규화를 캐시해 반복 정규식 처리를 줄인다.
- 기존 즉시 이벤트 경로와 300ms 보조 폴링을 유지한다. 75/200/500ms 검사는 초기 로딩
  보완을 위한 재검사이며, 최초 검사를 그만큼 기다리는 설정은 아니다.

진단을 직접 시작한 상태에서는 보고서에 `nodesRead`, `scanMs`와 다음 시간이 추가된다.

| 항목 | 측정 범위 |
| --- | --- |
| inspectToMatchMs | 활성 창 검사 시작 → 보호 판정 |
| matchToRequestMs | 보호 판정 → 잠금 Activity 실행 요청 |
| requestToResumeMs | 실행 요청 → LockActivity의 onResume |

이는 화면 터치부터의 총 시간이나 실제 첫 화면 표시 시간을 측정하지 않는다.
Android가 이벤트를 전달하기 전의 지연과 onResume 이후 그리기 시간은 포함되지 않는다.
잠금 요청과 가까운 보호 검사만 연결하며, 연결되지 않은 요청은 requestToResumeMs만 표시한다.
빠른 양성 판정에서는 노드 목록이 화면 트리의 일부일 수 있고 전용 클래스 판정은 0개일 수 있다.

실기기에서 각 보호 화면의 첫 진입·반복 진입을 확인하고, 계속 느리면 진단을 시작해
문제 화면을 한 번 재현한 보고서를 확인한다. 특히 `scanMs`가 큰지,
`requestToResumeMs`가 큰지를 구분한다. 실제 ms 개선 폭은 아직 측정하지 않았다.
로컬에는 Java/Flutter가 없어 빌드·테스트는 Actions 확인이 필요하다.
이 수정 당시 버전 식별자는 **settings-guard-4**였다.

## 설정 잠금에서 뒤로가기: 세 번째 수정

사용자가 삼성에서 접근성 서비스 상세·특별한 권한·앱 정보·기기 관리자 해제·서랍 삭제 시도에
PIN이 나타나는 것을 확인했다. 이번 변경은 감지 규칙을 유지하며 설정 잠금 취소 동작을 바꾼다.
설정 화면을 지키려고 띄운 잠금에만 `guarded_settings` 표시를 전달한다.

- PIN의 뒤로가기 버튼/제스처 → 임시 터치 차단 화면 부착 → PIN Activity 종료.
- 임시 화면은 앱의 밝은/어두운 테마 배경에 작은 로딩 원만 표시한다.
  이전의 “Returning to the previous screen…” 문구는 표시하지 않는다.
- 설정 창이 활성화된 것을 확인한 다음 접근성의 시스템 Back을 실행한다.
  이동 중에는 터치 차단 화면을 유지하며 PIN 중복 실행만 막는다.
- 앞 화면도 보호 대상이면 최대 4번까지 돌아가 보호되지 않은 이전 화면을 찾는다.
  정상 화면이 연속 검사에서 안정된 뒤 차단 화면을 제거한다.
- 3초 안에 돌아가지 못하면 홈 이동을 시도한다. 홈도 실패하면 PIN을 복구하고,
  실제 PIN 재표시/홈 이동을 확인할 때까지 차단 화면을 유지한다.
- 취소에서는 `LockSession.markUnlocked`를 호출하지 않는다. 늦게 도착한 생체 인증 성공도
  취소한 잠금에 인증 세션을 만들지 않는다. 보호 화면 재진입에는 계속 PIN이 필요하다.
  취소 완료 시 이전 잠금의 중복 실행 방지 시각을 초기화해 빠른 재진입도 다시 검사한다.
- 일반 앱 잠금과 패키지 설치 프로그램의 삭제 확인 잠금은 기존 홈 이동 동작을 유지한다.

실기기에서 추가로 확인할 사항:

1. 위 네 설정 진입 경로에서 PIN → 뒤로가기 한 번으로 이전 목록에 복귀하고 재잠금 반복이 없는지.
2. 특별한 권한 하위 화면에서 취소하면 보호된 부모 메뉴도 지나 안전한 이전 화면으로 이동하는지.
3. 돌아온 뒤 같은 보호 화면에 재진입하면 PIN이 다시 뜨는지.
4. 돌아가는 도중 빠르게 탭해도 서비스/권한/기기 관리자를 변경할 수 없는지.
5. 세 버튼 내비게이션과 뒤로가기 제스처, 일반 앱 잠금, 서랍의 삭제 확인 동작.
6. 생체 인증 취소/늦은 성공, 화면 회전·화면 꺼짐, 접근성 서비스 중단, 홈/최근 앱 이동.
7. 직접 링크로 진입해 이전 설정 화면이 없거나 제조사가 Back을 무시할 때 홈/PIN 복구가 되는지.

`GuardBackNavigationTest`는 대기·뒤로가기 횟수 제한·안정화·홈/PIN 복구 판단을 검사한다.
로컬에는 Java/Flutter가 없으므로 테스트 실행과 APK 빌드는 Actions에서 확인해야 한다.
오버레이·시스템 Back·제스처의 실제 동작은 이 단위 테스트만으로 보장하지 않는다.
뒤로가기 변경의 버전 식별자는 **settings-guard-3**이었다.

참고: [시스템 Back/Home API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)),
[접근성 오버레이](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY).

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
   보고서 첫 줄에 **settings-guard-6**와 빌드 커밋 앞 12자리가 표시되어야 한다.
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
