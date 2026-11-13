# 플레오케 (PLEOKE)

## 1. 앱 이름 및 한 줄 소개

**플레오케 (PLEOKE)**: 주차한 차 안에서 가족과 친구가 좌석마다 파트를 나눠 함께 부르는 차량 노래방 Android Automotive 앱

프로젝트명은 **가족과 친구를 위한 좌석별 참여형 차량 노래방 서비스**입니다.

앞좌석은 차량 화면에서, 뒷좌석은 각자 휴대폰으로 QR 코드를 찍어 참여합니다. 곡을 예약하면 참여한 좌석에 절과 후렴 파트를 나눠 추천합니다. 공연 중에는 큰 가사가 지금 부르는 좌석의 색으로 차오르고, 뒷좌석 휴대폰에는 내 가사와 다음 차례가 뜹니다. 기어가 P가 아니거나 차량이 조작을 제한하면 공연을 멈추고, 다시 주차하면 멈춘 곳부터 직접 이어 부를 수 있습니다.

| 항목 | 내용 |
| --- | --- |
| 앱 버전 | 0.2.0 (versionCode 2) |
| 패키지 | `io.github.myshihi.pleosproject26.n02vehiclekaraoke` |
| 시연 영상 | [demo-video/플레오케-시연영상.mp4](demo-video/플레오케-시연영상.mp4) (75초, 자막 `.srt` 포함) |

### 폴더 구성

| 위치 | 내용 |
| --- | --- |
| `apk/` | 바로 설치할 수 있는 release APK |
| `project/` | 앱 소스 전체 (Android Studio 프로젝트) |
| `demo-video/` | 시연 영상과 자막 |

## 2. 실행 방법

### 2.1 APK로 실행 (권장)

1. Pleos Connect 에뮬레이터를 켭니다. 준비 방법은 [3. 테스트 환경 및 필요한 설정](#3-테스트-환경-및-필요한-설정)에 있습니다.
2. 저장소 최상위 폴더에서 APK를 설치하고 실행합니다.

```bash
adb install -r apk/설계3.5팀_Pleoke_release.apk
adb shell am start -n io.github.myshihi.pleosproject26.n02vehiclekaraoke/.MainActivity
```

에뮬레이터 앱 목록에서 **플레오케**를 눌러 실행해도 됩니다.

같은 패키지가 다른 서명(디버그 빌드 등)으로 이미 설치돼 있으면 설치가 거부됩니다. 이때는 먼저 지운 뒤 다시 설치합니다.

```bash
adb uninstall io.github.myshihi.pleosproject26.n02vehiclekaraoke
```

### 2.2 소스에서 빌드해 실행

1. Android Studio에서 **`project` 폴더**를 엽니다(Open).
2. Gradle JDK는 Android Studio에 들어 있는 JBR(JDK 17 이상)을 씁니다.
3. 실행 기기로 Pleos Connect 에뮬레이터를 고르고 `app`을 실행(Run)합니다.

명령줄로 빌드하려면 다음과 같이 합니다.

```bash
cd project
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

서명 키는 저장소에 넣지 않았습니다. 그래서 소스에서 `assembleRelease`로 빌드하면 서명되지 않은 APK가 나옵니다. 소스 빌드는 debug로 실행하고, release 버전은 `apk/` 폴더의 APK로 확인해 주세요.

### 2.3 사용 순서

1. 기어 **P**인 상태에서 시작합니다. 공연은 기어 P와 차량의 조작 허용 상태가 모두 확인돼야 시작됩니다.
2. **좌석 참여**: 앞좌석은 **이 좌석 참여**를 누르고, 뒷좌석은 **휴대폰 QR**을 눌러 휴대폰으로 참여합니다. 다 모이면 **참여 좌석 확인 완료**를 누릅니다.
3. **곡 예약**: 차량 화면이나 뒷좌석 휴대폰에서 곡을 예약합니다. 앱에는 직접 만든 시연곡 3곡이 들어 있습니다.
4. **파트 나누기**: 추천된 파트를 좌석 이름 옆의 `↔` 버튼으로 바꾸고 **파트 확정**을 누릅니다. 휴대폰에서 보낸 '내가 부르기' 요청은 차량 화면에서 다시 확정해야 반영됩니다.
5. **함께 부르기**를 누르면 반주와 가사가 시작됩니다. **전체 무대**를 누르면 가사만 크게 볼 수 있습니다.
6. 출발하려고 기어를 D로 바꾸면 공연이 멈춥니다. 다시 P로 돌아와 앱을 열면 곡, 파트, 멈춘 위치를 불러오고, 참여 좌석을 다시 확인한 뒤 이어 부릅니다.

## 3. 테스트 환경 및 필요한 설정

### 3.1 빌드 환경

| 항목 | 내용 |
| --- | --- |
| 언어 | Kotlin |
| 빌드 도구 | Android Gradle Plugin 9.4.1, Gradle 9.6.0 |
| SDK | compileSdk 37, minSdk 30, targetSdk 37 |
| 차량 SDK | Pleos Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`) |
| 그 밖의 라이브러리 | `androidx.media:media:1.6.0` (차량 음악 앱 연결) |

### 3.2 에뮬레이터 준비

1. Android Studio → SDK Manager → SDK Update Sites에 Pleos Connect 시스템 이미지 주소를 추가합니다.
   - Windows / Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - macOS (Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms에서 **Show Package Details**를 켜고, API 34의 **Pleos Connect 2.1.0** 이미지를 설치합니다.
3. Device Manager → Create Device → Automotive → **Pleos Connect 2.1.0**으로 기기를 만들고 실행합니다.
4. 소스에서 빌드하려면 Android SDK Platform 37도 설치해야 합니다. Vehicle SDK는 빌드할 때 Pleos 공개 Maven 저장소에서 자동으로 받으므로 인터넷 연결이 필요합니다.

### 3.3 기어 바꾸기 (선택)

에뮬레이터의 Virtual Console(<http://127.0.0.1:8088/>)에서 기어를 P와 D로 바꿔 공연이 멈추고 다시 이어지는 동작을 확인할 수 있습니다.

앱 안에서만 확인하려면 **연결·설정 → 공연 중단 체험**을 누릅니다. 이 버튼은 앱 동작 확인용이며 실제 차량 신호는 바꾸지 않습니다.

### 3.4 PC에서 뒷좌석 휴대폰 화면 보기 (선택)

에뮬레이터 안의 주소는 실제 휴대폰에서 열 수 없습니다. 대신 PC에서 아래 명령을 실행합니다.

```bash
adb forward tcp:8792 tcp:8792
```

그다음 **휴대폰 QR**을 누르면 QR 아래에 주소(`http://…:8792/join?seat=…&token=…`)가 나옵니다. 이 주소의 앞부분을 `http://127.0.0.1:8792`로 바꿔 PC 브라우저로 열면 됩니다. 브라우저 개발자 도구의 모바일 화면 모드로 보면 휴대폰과 비슷하게 보입니다.

### 3.5 차량 노래방 조명 (선택)

**연결·설정 → 차량 노래방 조명 켜기**를 직접 눌러야 동작합니다. 공연 중에는 부르는 좌석의 색으로 실내 앰비언트 조명을 바꾸고, 멈추거나 출발하거나 앱을 벗어나면 원래 색과 밝기로 되돌립니다. 앱을 다시 열어도 저절로 켜지지 않습니다.

### 3.6 차량 음악 앱 연결 (선택)

**연결·설정 → 멜론 · 지니뮤직 차량 구독 연결**에서 차량에 설치된 음악 앱을 찾습니다. 음악 앱을 조작하려면 같은 화면의 **음악 앱 제어 권한 확인**에서 플레오케에 권한을 주어야 합니다. 구독곡은 노래방 가사와 파트를 지어내지 않고 '가사 제공 전'으로 표시합니다. 현재 에뮬레이터에는 두 앱이 없어 '찾지 못했어요'로 나옵니다.

### 3.7 확인한 범위

- Pleos Connect 2.1.0 에뮬레이터에서 디버그 빌드 v0.2.0으로 네 좌석 참여, 휴대폰 QR 화면, 곡 예약과 파트 확정, 노래방 라이브 화면, 조명 연출과 복원, 기어 P→D 전환 시 공연 중단과 복원을 확인했습니다. (2026-11-12)
- release APK는 같은 소스로 빌드했고 서명을 `apksigner`로 확인했습니다. 에뮬레이터에 깔린 디버그 앱의 기록을 지우지 않으려고 release APK는 에뮬레이터에 설치해 보지 않았습니다.
- 실제 차량, 실제 휴대폰의 차량 Wi-Fi 접속(PC 브라우저로만 확인), 무선 마이크 입력과 보컬 믹싱, 멜론·지니뮤직 실제 연결, 실제 차 안의 조명은 확인하지 않았습니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, x86_64) |
| 화면 규격 | 2560 × 1440 px, 160 dpi, 가로 화면 |

## 5. APK 파일 위치

**[apk/설계3.5팀_Pleoke_release.apk](apk/설계3.5팀_Pleoke_release.apk)**

- 디버그 모드를 끈 release 빌드입니다.
- APK Signature Scheme v2로 서명했고, `apksigner verify`로 서명을 확인했습니다.
- 시연곡 3곡의 반주와 가사가 APK 안에 들어 있어 로그인이나 인터넷 연결 없이 실행됩니다.
