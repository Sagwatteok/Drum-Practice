# Drum Practice 개발 지침

- Kotlin, Jetpack Compose, ViewModel과 StateFlow를 사용하고 단일 앱 모듈을 유지한다.
- USB MIDI 입력과 오디오 출력을 분리한다. 전자드럼 자체 음원을 사용하며 앱은 타격에 드럼 소리를 생성하지 않는다.
- Android MIDI 콜백 시각과 수신 시각을 `System.nanoTime()` 계열로 처리한다. UI 프레임을 채점 시간으로 쓰지 않는다.
- 하드웨어 미검증 매핑과 지연 수치를 확정하지 않는다. 진단 로그에서 Mock과 USB 출처를 표시한다.
- 핵심 연습은 오프라인으로 동작한다. 새 런타임 의존성을 추가하기 전에 공식 호환성과 라이선스를 확인한다.
- 코드 변경 후 관련 단위·계측 테스트와 빌드를 실행한다. 실기기 결과는 `docs/HARDWARE_TEST.md`에 기기와 재현 조건을 기록한다.
