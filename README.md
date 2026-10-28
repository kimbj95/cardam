# 카담 (Cardam) · 차가 건네는 말

> 경고등이 켜지는 순간 먼저 알려 주고, 그 순간을 기록해 정비소에 보여 주는 Pleos Connect 차량용 앱

## 프로젝트 소개
카담은 계기판 경고등이 켜지는 순간 무슨 뜻인지, 계속 주행해도 되는지를 음성으로 먼저 안내하고, 경고등이 켜진 순간의 차량 상태를 기록해 정비소에서 그대로 보여 줄 수 있게 하는 차량용 앱입니다. 경고등 설명은 현대자동차 그랜저(GN7) 2026 취급설명서를 요약해 사용합니다.

본 프로젝트는 현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 주요 기능
| 핵심 기능 | 내용 |
|---|---|
| 켜지는 순간 안내 | 경고등이 7초 이상 켜져 있으면 음성 안내(시동 시 3~6초 점등 확인은 제외), 위험도 구분: 바로 세우기(빨간 경고등) / 곧 점검(노란 경고등) / 표시등(전조등·방향지시등 등)은 안내하지 않음 |
| 다음 행동 연결 | 곧 점검 단계는 "가까운 정비소로 길안내할까요?" → "네" 한마디로 가장 가까운 블루핸즈(없으면 정비소) 길안내, 길안내 중이면 경유지 추가 |
| 바로 세우기 안내 | 주행 중에는 질문 없이 짧게 안내, 정차(P단)하면 대처 방법과 기록 위치를 다시 안내 |
| 경고등 블랙박스 | 켜진 순간의 시각, 차속, 기어, 바깥 기온, 출발 후 시간, 주행거리, 함께 켜진 경고등, 앞뒤 30초 속도·브레이크 기록, 꺼진 시각과 켜져 있던 시간 기록 |
| 반복 패턴 | 같은 경고등이 최근 30일 동안 반복되면 공통 조건 정리 (예: "모두 바깥 기온 0℃ 이하, 출발 10분 안이었어요"), 추운 날 타이어 공기압 경고는 설명서 기준 안내 |
| 정비소 리포트 | 정차 화면에서 [정비소에 보여 주기] → 기록 요약과 QR 코드(휴대폰 카메라로 읽으면 글자로 표시, 인터넷 연결 불필요) |
| 앱이 꺼져 있던 동안의 경고등 | 서비스가 다시 시작되면 지금 켜진 경고등을 확인해 기록에 없던 경고등을 안내 |

- 주행 중에는 화면 없이 음성으로만 안내하며, 운전자는 "네"·"아니요"로 답합니다. 대답이 없으면 아무 동작도 하지 않습니다.
- 정차 중(P단)에만 화면을 사용합니다. Pleos 차량 제어 앱과 같은 타일 구성, 낮·밤 테마, Asta Sans 글꼴을 적용했습니다.
- "이 경고등은 무슨 뜻인가요?" 같은 질문에는 Gleo AI가 답할 수 있습니다. 카담은 묻기 전에 켜지는 순간 먼저 알리고, 다음 행동을 연결하고, 그 순간을 기록하는 데 집중합니다.

## 활용 기술
| 구분 | 내용 |
|---|---|
| Pleos Vehicle SDK | 경고등(Telltale: 종류, 켜짐·깜빡임, 색), 브레이크, 조향각, 주행거리, 남은 주행 가능 거리, 차대번호 |
| AAOS Car API | 차속, 기어, 외기 온도, 주행 제한 상태 |
| Pleos NaviHelper | 현재 위치, 경로 요청, 경유지 추가 |
| Pleos TTS·STT | 온디바이스(ON_DEVICE) 음성 안내와 짧은 대답 인식 |
| Android 표준 | 포그라운드 서비스, 부팅 완료 시 자동 시작, 휴대폰 블루투스 연결 시 서비스 재시작(시스템이 허용하지 않으면 시작 알림과 음성 안내) |
| 경고등 설명 | 현대자동차 그랜저(GN7) 2026 취급설명서 4장 '경고등 및 표시등', 8장 '엔진 과열'·'타이어 공기압 경보 시스템'을 요약하고 쪽수 표기 (`app/src/main/assets/telltales.json`) |
| 정비소 위치 | OpenStreetMap 정비소 1,591곳(블루핸즈 97곳 포함), © OpenStreetMap contributors, ODbL (`app/src/main/assets/licenses/OpenStreetMap-ODbL.txt`) |
| QR 코드 | ZXing core 3.5.3 (Apache License 2.0) |
| 글꼴 | Asta Sans (42dot, SIL Open Font License 1.1, `app/src/main/assets/licenses/AstaSans-OFL.txt`) |

- 경고등 정보는 `pleos.car.permission.PRIVATE_CAR_SAFETY` 권한이 있어야 읽을 수 있습니다(보호 수준 normal). Pleos App Market 배포 시 해당 권한의 승인 여부 확인이 필요합니다.

## 검증
- Pleos Connect Emulator v2.1.0에서 핵심 기능을 시험했습니다. 상세 결과와 화면은 [docs/verification.md](docs/verification.md)에 있습니다.

## 시연 영상
- 영상 파일: [docs/cardam-demo.mp4](docs/cardam-demo.mp4) (2분 27초)
- Pleos Connect Emulator v2.1.0에서 실제로 동작하는 앱을 녹화한 영상입니다. 계기판 경고등은 에뮬레이터의 주행 정보 화면에 실제로 표시된 것입니다.
- 경고등과 차량 신호(차속, 기어, 주행거리)는 에뮬레이터의 Virtual Console로 입력했습니다.
- 영상 제작 과정에서 보완한 항목은 다음과 같습니다.

| 항목 | 내용 |
|---|---|
| 지난 기록 | 1편의 반복 패턴을 보여 주기 위해 지난 두 번의 타이어 공기압 경고 기록을 시연 명령(`seed:TPMS`)으로 넣었습니다. |
| 바깥 기온 | Virtual Console에 외기 온도 조작이 없어 시연 명령(`outside:-3`)으로 -3℃를 지정했습니다. |
| 시간 경과 | '6분 뒤', '정비소 도착' 구간은 시연 명령으로 출발 시각을 지정하거나 영상에서 잘라 냈습니다. |
| 지도 | 에뮬레이터 내비게이션 지도가 비어 있고 위치가 고정되어 있어, 실제 도로 경로(© OpenStreetMap, OSRM)를 Pleos 지도 스타일로 그려 합성했습니다. 화면에 '합성'으로 표기했습니다. |
| 앱 음성 | 녹화 중 에뮬레이터 CPU 부하로 음성이 끊겨, 같은 앱이 같은 문장을 다시 말하게 하여 별도로 녹음했습니다. 목소리와 문장은 앱 출력 그대로이며, 영상에서 앱이 말한 시점에 배치했습니다. |
| 운전자 대답 | 시연 명령(`answer:`)으로 미리 녹음한 대답을 음성 인식기에 직접 입력해 녹화에 소리가 남지 않으므로, 같은 말을 합성 음성으로 덧붙였습니다. |
| 설명 화면 | 자막, 장면 제목, 차량 신호 패널, 경고등 위치 표시는 이해를 돕기 위해 덧붙인 화면입니다. |

## 실행 방법 (APK)
- APK 파일: `apk/` 폴더 (서명된 release 빌드, 제출 전 추가 예정)
1. 설치: `adb install -r <APK 파일>`
2. 권한 허용 (첫 실행 때 표시되는 창에서 허용해도 됩니다)
   ```
   adb shell pm grant --user 10 app.cardam android.car.permission.CAR_SPEED
   adb shell pm grant --user 10 app.cardam android.permission.POST_NOTIFICATIONS
   adb shell pm grant --user 10 app.cardam android.permission.BLUETOOTH_CONNECT
   ```
3. 실행: `adb shell am start --user 10 -n app.cardam/.MainActivity`
- 한 번 실행한 뒤에는 부팅할 때마다 서비스가 자동으로 시작됩니다.

## 테스트 환경 및 설정
- 테스트 환경: Pleos Connect Emulator v2.1.0 (Android Automotive OS, Android 14). 설치 방법은 [Pleos Connect 개발 환경 설정 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다.
- 앱은 사용자 10에서 실행되므로 adb 명령에 `--user 10`을 붙입니다.
- 경고등과 차량 신호: 에뮬레이터의 Virtual Console(에뮬레이터 안 8088 포트)로 입력합니다. `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`을 엽니다. Windows에서 8088 포트가 예약되어 있으면 `adb forward tcp:18088 tcp:8088`과 18088 포트를 사용합니다.
  - 경고등 켜기 예: `curl -X PUT "http://127.0.0.1:8088/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow"` (`state`: `On`, `Blink`, `Off`)
  - Virtual Console로 켤 수 있는 경고등은 37가지입니다(SDK가 구분하는 경고등은 95가지).
- 길안내: 에뮬레이터를 켠 뒤 내비게이션 앱을 한 번 열어야 현재 위치를 응답합니다.
- 음성 대답: PC 마이크로 답하려면 `adb emu avd hostmicon`을 실행합니다. 마이크 없이 시험할 때는 [시연 명령](#시연-명령)의 `answer:`로 미리 녹음한 대답을 넣습니다.

## 개발 환경
- 언어: Kotlin
- 플랫폼: Pleos Connect (Android Automotive OS, Android 14)
- 빌드: Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.0
- SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech·SpeechToText 3.3.1

## 소스에서 빌드
1. `local.properties`에 Android SDK 경로(`sdk.dir`)를 지정합니다.
2. 빌드: `./gradlew assembleDebug` (설치 파일: `app/build/outputs/apk/debug/app-debug.apk`)
3. release 빌드: 저장소 최상위에 `keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)를 두고 `./gradlew assembleRelease`를 실행합니다. 서명 키와 이 파일은 저장소에 올리지 않습니다.

## 시연 명령
에뮬레이터에서 재현하기 어려운 상황은 adb 전용 명령으로 재현합니다. 정차 화면의 제목을 3초 누르면 자주 쓰는 명령을 버튼으로 실행할 수 있습니다.

```
adb shell am broadcast --user 10 -a app.cardam.DEMO -n app.cardam/.DemoReceiver --es cmd <명령>
```

| 명령 | 내용 |
|---|---|
| `seed:TPMS` 등 | 해당 경고등의 지난 기록 두 번 추가 (추운 아침, 시연용 표시) |
| `outside:-3`, `outside:off` | 시연용 바깥 기온 |
| `drive:<분>` | 지정한 분 전에 출발한 것으로 설정 |
| `answer:ne` 등 | 다음 질문에 미리 녹음한 대답 입력 (`ne`, `aniyo`, `yes`, `no`, `geurae`) |
| `clear` | 경고등 기록 초기화 |
| `shop` | 가장 가까운 정비소 길안내 |
| `service:stop` | 서비스 종료 (Suspend 중 앱이 종료된 상황 재현) |
| `bt:connected` | 휴대폰 블루투스 연결 신호 대체 (에뮬레이터 블루투스 미지원) |
| `say:<문장>` | 앱 음성으로 문장 읽기 (시연 영상 음성 녹음용) |
| `status` | 현재 상태 로그 출력 |

## 폴더 구조
| 경로 | 내용 |
|---|---|
| `app/src/main/java/.../vehicle` | 차량 신호와 경고등 수신 |
| `app/src/main/java/.../warning` | 경고등 판단, 기록, 반복 패턴, 정비소 리포트, 시연 설정 |
| `app/src/main/java/.../voice` | 음성 안내·질문·대답 판정 |
| `app/src/main/java/.../navi` | 내비게이션 연동 |
| `app/src/main/java/.../data` | 경고등 사전, 정비소 위치 |
| `app/src/main/assets` | 경고등 사전(취급설명서 요약), 정비소 위치, 시연용 대답 음성, 글꼴과 라이선스 |
| `docs/` | 검증 결과, 화면 캡처, 시연 영상 |
