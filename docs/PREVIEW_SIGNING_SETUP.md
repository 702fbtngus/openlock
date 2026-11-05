# Preview APK를 삭제 없이 업데이트하기

현재 PC의 `.signing` 폴더에 고정 서명 키와 GitHub 등록용 값 4개를 생성했다.
별도의 Java 설치 없이 Git for Windows의 OpenSSL을 사용했다.
이 키를 앞으로 계속 사용한다. 매번 새로 만들지 않는다.

## 1. GitHub Secrets 4개 등록

[이 저장소의 Actions Secrets](https://github.com/702fbtngus/openlock/settings/secrets/actions)를 연다.
경로는 저장소 **Settings → Secrets and variables → Actions → New repository secret**이다.

VS Code의 프로젝트 터미널(PowerShell)에서 아래 명령을 **한 줄씩** 실행한다.
각 명령은 값을 클립보드에 복사하며 화면에는 표시하지 않는다.
명령 하나 실행 → 해당 Name으로 Secret 생성 → Secret 입력란에 붙여넣기 → Add secret을
누른 다음 다음 줄로 진행한다. 네 줄을 한꺼번에 실행하면 마지막 값만 남으므로 주의한다.

| GitHub의 Name | 터미널에서 실행할 복사 명령 |
| --- | --- |
| `KEYSTORE_BASE64` | `Get-Content .signing/KEYSTORE_BASE64.txt -Raw \| Set-Clipboard` |
| `KEYSTORE_PASSWORD` | `Get-Content .signing/KEYSTORE_PASSWORD.txt -Raw \| Set-Clipboard` |
| `KEY_PASSWORD` | `Get-Content .signing/KEY_PASSWORD.txt -Raw \| Set-Clipboard` |
| `KEY_ALIAS` | `Get-Content .signing/KEY_ALIAS.txt -Raw \| Set-Clipboard` |

또는 `scripts/copy_signing_secret.ps1 -Name KEYSTORE_BASE64` 등의 도우미를 사용할 수 있다.
이 생성 방식은 PKCS12를 사용하므로 KEYSTORE_PASSWORD와 KEY_PASSWORD 값이 같다.
값을 채팅·이슈·커밋 메시지에 넣지 않는다.

## 2. 변경 사항 커밋·푸시

일반 코드 변경 사항을 커밋·푸시한다. `.signing`과 생성 중 임시 폴더는 `.gitignore`에
등록해 Git에서 제외했다. 로컬 검사에서도 값 파일 4개와 keystore가 제외됨을 확인했다.
별도 키 파일을 저장소에 추가할 필요가 없다.

## 3. APK 빌드

GitHub **Actions → Build preview APK → Run workflow**로 최신 커밋을 빌드한다.
main push에도 preview workflow가 실행된다.

- push/수동 실행은 Secrets의 고정 키를 복원하고 release APK를 서명한다.
- Secrets가 빠졌거나 키/비밀번호가 잘못되면 실패한다. 새 테스트 키로 대체하지 않는다.
- APK의 서명 인증서 SHA-256과 복원한 키의 인증서가 같은지 확인한 뒤
  **openlock-preview-apk** Artifact를 업로드한다. 공개 인증서 지문은 실행 Summary에도 표시된다.
- PR은 고정 키를 사용하지 않는다. PR의 debug APK는 **openlock-pr-apk**로 구분한다.
  이 PR APK를 휴대폰의 고정 키 APK 업데이트 용도로 사용하지 않는다.
- 종료 시 러너의 서명 키와 key.properties를 삭제한다.

## 4. 처음 한 번 설치 전환

기존 preview 앱은 다른 테스트 키로 서명됐으므로 새 고정 키 APK를 처음 설치할 때는
기존 앱을 한 번 삭제해야 할 수 있다. 삭제하면 PIN/설정이 초기화될 수 있으므로
새 APK 빌드가 성공한 뒤 전환한다.

그 이후에는 같은 키로 서명된 preview APK를 기존 앱 위에 설치한다.
패키지 이름을 유지하고, versionCode는 기존 설치보다 낮추지 않는다.
원본 공식 OpenLock APK의 키와는 다르므로 공식 APK와 서로 업데이트할 수 없다.

## 5. 키 백업

`.signing` 폴더 전체를 안전한 개인 위치에 백업한다. keystore와 비밀번호 파일이 함께
들어 있으므로 이 폴더를 공개하거나 저장소에 올리지 않는다.
GitHub Secrets만 유일한 백업으로 삼지 않는다. 등록 후 Secret 값을 다시 읽어 내려받는
기능은 없고, 키를 잃으면 기존 서명 앱에 대한 업데이트가 어려워진다.

`scripts/create_signing_key.py`는 `.signing`이 이미 있으면 기존 키를 덮어쓰지 않고 종료한다.
다른 PC에서는 새 키를 생성하는 대신 이 폴더의 백업을 사용한다.
별도의 실제 키 값/비밀번호는 이 문서나 코드에 포함하지 않았다.

## 확인한 범위

키 생성 및 OpenSSL 암호 확인, Secret 값의 Base64 왕복 복원, 누락/잘못된 값 거절,
기존 키 덮어쓰기 거절, 인증서 지문 일치/불일치 검사, workflow 구문과 PR 분기를 로컬에서 확인했다.
Java/Flutter가 없어 실제 APK 서명·빌드와 휴대폰 업데이트 설치는 아직 확인하지 못했다.
GitHub Secrets 등록·원격 푸시·Actions 실행도 아직 수행하지 않았다.

공식 안내: [GitHub repository secret 등록](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets),
[Android 앱 서명](https://developer.android.com/studio/publish/app-signing).
