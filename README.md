# Drum Practice

Yamaha DTX452K와 Android 스마트폰을 USB로 연결해 타격 데이터를 확인하고 연습하는 오프라인 앱입니다. 드럼 소리는 모듈 자체 음원으로 들으며 앱은 타격에 대한 드럼 소리를 생성하지 않습니다.

## APK 및 빌드

- Android 8.0(API 26) 이상에서 실행합니다. 실기기 검증 대상은 Galaxy S23 Ultra와 Yamaha DTX452K(DTX402 모듈)입니다.
- Android Studio에서 저장소를 열고 SDK Platform 35, Build Tools 36.0.0, JDK 17 이상을 준비합니다. Gradle Wrapper 9.3.1과 AGP 9.1.1을 사용합니다. 최초 Gradle 동기화에는 네트워크가 필요합니다.
- 명령줄에서는 Android SDK 위치를 `ANDROID_HOME` 또는 로컬 `local.properties`의 `sdk.dir`에 지정합니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS/Linux에서는 `./gradlew`를 사용합니다. 두 번째 명령은 에뮬레이터 또는 연결된 Android 기기가 필요합니다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. S23 Ultra에서 직접 설치하는 경우 APK를 연 앱에 대해 Android의 알 수 없는 앱 설치 권한이 필요할 수 있습니다.

## 첫 연결

1. 모듈 전원을 켜고 유선 헤드폰을 `PHONES/OUTPUT`에 연결합니다. 소리를 낮춘 상태에서 테스트를 시작합니다.
2. USB-C ↔ USB-B **데이터** 케이블로 휴대전화와 모듈의 `USB TO HOST`를 연결합니다.
3. 앱의 **연결 진단**에서 USB 장치와 MIDI 출력 포트를 확인한 뒤 모듈에서 들어오는 포트를 선택합니다. 타격하면 Note, Velocity, 수신 시각이 표시됩니다.
4. **12초 테스트음 재생**을 누르고 실제 출력 경로를 봅니다. 모듈 헤드폰에서 테스트음과 자체 드럼 소리가 함께 들리는지 확인합니다.
5. **매핑**에서 Learn으로 드럼을 등록하거나 Note 번호를 직접 편집합니다. **자유 연주**에서는 최근 타격과 개별 드럼 횟수를 확인합니다. 화면의 드럼 버튼과 Mock 패턴으로 장비 없이도 입력 경로를 실행할 수 있습니다.

USB 오디오가 잡히지 않으면 [하드웨어 테스트](docs/HARDWARE_TEST.md)의 AUX IN 대체 구성을 참고하세요.

## 입문 레슨

**레슨**에서 재생을 누르면 60 BPM 기준 4/4 한 마디를 센 뒤 기본 8비트 두 마디를 반복합니다. 채점은 카운트인이 끝난 뒤 시작합니다. 악보의 세로로 정렬된 음표를 함께 치세요. 닫힌 하이햇은 8분음표, 킥은 1·3박, 스네어는 2·4박입니다.

**재생 설정**에서 시작 마디와 연주 구간, 템포를 선택할 수 있습니다. 재생 중에 BPM을 적용하면 선택 마디와 반복 구간을 유지한 채 카운트인부터 다시 시작합니다. 장비 없이 확인할 때는 정지 상태에서 **Mock 자동 타격**을 켜거나 **Mock 패드**를 누르세요. USB 점수와 Mock 점수는 따로 집계됩니다.

## 프로젝트 문서

- [요구사항](docs/REQUIREMENTS.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [단계별 계획](docs/ROADMAP.md)
- [공통 악보 형식 초안](docs/SCORE_FORMAT.md)
- [기초 커리큘럼 초안](docs/LEARNING_CURRICULUM.md)
- [하드웨어 테스트 절차와 결과](docs/HARDWARE_TEST.md)
- [개발 지침](AGENTS.md)
