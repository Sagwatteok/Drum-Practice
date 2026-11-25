# 아키텍처

단일 Android 앱 모듈에서 Compose UI는 ViewModel의 StateFlow를 구독한다. 영구 매핑은 Preferences DataStore에 저장하고 최근 연주 진단은 화면 세션의 메모리에 둔다.

```text
DTX402 USB MIDI → MidiManager → MidiOutputPort → MidiReceiver
    → USB 처리 스레드의 스트림 파서 → ViewModel → 진단 / 자유 연주 / MIDI Learn
Mock 바이트 → 별도 스트림 파서 ───────────────┘

PCM 테스트음 → AudioTrack → Android 미디어 출력 → 선택 시 DTX402 USB 오디오
실제 드럼 음원 → DTX402 PHONES/OUTPUT
```

- MIDI는 MIDI 1.0 바이트 스트림을 사용한다. 수신 시간을 콜백에서 바로 읽고 포트별 파서가 분할 메시지·러닝 상태·인터리브 실시간 바이트를 처리한다. Note On과 Velocity 0의 조합은 Note Off로 해석한다.
- 타임스탬프가 양수이고 수신보다 미래가 아닐 때 소스 시각을 사용한다. 나머지는 수신 시각으로 대체하고 원본 값은 로그에 남긴다.
- 출력 포트는 Android 관점에서 드럼이 보내는 방향이다. 케이블 재연결은 동일한 장치·포트 식별자가 하나일 때 자동 시도한다. 수동 해제는 재연결 대기를 취소한다. 선호 포트는 현재 앱 세션에 유지한다.
- 최근 100개 진단 패킷은 파싱 이후 UI 상태에 보관한다. MIDI 시각→수신 및 수신→처리 통계는 관측 구간의 표준편차를 제공한다. 장비 전체의 소리 전달 지연은 별도 계측이 필요하다.
- 자유 연주는 USB와 Mock의 횟수와 최근 타격을 별도로 집계한다. 같은 출처에서 마지막 수신 시각 기준 45 ms 안에 들어온 패드를 함께 표시한다. 드럼 세트는 마지막 입력 묶음을 220 ms 동안 강조한다. 두 시간값은 화면 표시에만 적용한다.
- AudioTrack의 실제 라우팅이 USB로 표시될 때 들어온 USB Note On만 동시 입력 카운터에 포함한다. 실제 헤드폰에서 들리는지는 청취로 확인한다.
- 메트로놈은 48 kHz PCM을 별도 작업에서 스트리밍한다. 카운트인, 박자 표시, MIDI 채점은 같은 오디오 프레임 계획을 사용한다. `AudioTrack.getTimestamp()`의 프레임과 단조 증가 시각을 연결하고, 타임스탬프를 읽을 수 없는 경로에서는 재생 헤드 샘플로 대체한다. 화면 갱신 시각은 채점에 쓰지 않는다.
- USB와 Mock은 각각 채점한다. 동시 타격은 악기별로 소비하고, 같은 악기의 판정창이 겹치면 Perfect 후보를 우선한 뒤 앞선 미적중 노트를 선택한다. USB 포트를 분리하면 기존 점수를 보존하고 재연결 시 그 이후 기대 노트부터 다시 집계한다.
- 재생 중 BPM이나 반복 설정을 바꾸면 현재 설정의 시작 마디로 이동해 카운트인하고 새 채점을 시작한다. 오디오 경로가 바뀌면 재생을 종료한다. 오프셋 보정과 실기기 출력 지연 측정은 후속 검증 대상으로 남아 있다.

참고 문서 [Android MIDI](https://developer.android.com/reference/android/media/midi/package-summary), [오디오 지연](https://source.android.com/docs/core/audio/latency/app), [AGP 9.1 호환성](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
