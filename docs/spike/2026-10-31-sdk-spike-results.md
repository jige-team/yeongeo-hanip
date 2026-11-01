# Pleos SDK 검증 스파이크 결과 (2026-10-31)

- 대상 아이디어: 빨간불 한마디(정차 중 영어 말하기 연습)
- 코드: `spike/` (Compose + 클린 아키텍처 + MVI, 버리는 코드)
- 환경
  - Pleos Connect Emulator 2.1.0 (API 34, arm64), MacBook Air M2 8GB
  - SDK: Vehicle 2.3.2, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3
- 검증 방법: 에뮬레이터 + Pleos Connect Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/ → Emulation Controls)

## 결과 요약

| 항목 | 결과 | 사용 API / 비고 |
|---|---|---|
| 기어 읽기 | ✅ | `vehicle.getGear()` `getCurrentGearSelectionState`, `registerGearSelection(GearSelectionListener)`. 퍼미션 `pleos.car.permission.CAR_DRIVE` |
| 브레이크 페달 | ✅ | `vehicle.getBrake()` `getCurrentBrakePedalStatus`, `registerBrakePedalStatus`. 콘솔 슬라이더를 움직이면 true/false로 즉시 들어옴. 퍼미션 `CAR_DRIVE` |
| 차량 속도 | ✅ (Pleos SDK 아님) | **Pleos SDK에는 속도 API가 없다.** FusedLocation의 `LocationData`에도 속도 필드가 없다(위도, 경도, 고도, heading, confidence, timestamp). AAOS 표준 `CarPropertyManager` `PERF_VEHICLE_SPEED`로 읽힌다. 퍼미션 `android.car.permission.CAR_SPEED`(런타임), `useLibrary("android.car")` |
| **정차 감지 → 자동 듣기 → 출발 시 중단** | ✅ | D단 + 브레이크 → 2.3초 뒤 STOPPED → STT 자동 시작. 브레이크를 떼면 **0.36초** 만에 MOVING → STT 중단. P단 → PARKED |
| TTS 영어 / 한국어 | ✅ 재생 | ON_DEVICE, `setLocale(Locale.US / KOREA)`, onStart → onDone 이벤트 수신. **발음 품질은 사람이 직접 들어 봐야 한다** |
| TTS 목소리 선택 | 🔍 | `setSpeakerType(String)` API가 있다. 어떤 값이 유효한지는 미확인 |
| STT 영어 인식 | ✅ (부분) | ON_DEVICE en-US, 앱 등록 없이 동작. Mac 스피커 소리를 Mac 마이크로 다시 받는 방식이라 "Let's circle back to this later" → **"to circle"**만 인식됨. 사람 목소리로 다시 확인 필요 |
| STT 동작 특성 | 참고 | `request()` 후 Ready까지 0.5~4.4초. **한 번 말하면 EPD가 발화 끝을 감지해 자동 종료**(1회성). 문장마다 `request()`를 다시 불러야 한다 |
| 창문 제어 | ✅ | `checkWindowPositionCapabilities` 범위 **0..10**. `setWindowPosition(ROW_1_LEFT, 5 / 0)` 성공. 퍼미션 `CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` |
| 공조 풍량 | ❌ | 운전석 단독(area 0x1)은 미지원. Capability가 알려 준 영역(areaId 5, 범위 1..8)으로 set하면 `PropertyAsyncError error code 2`. 콘솔상 공조는 ON 상태였다. 원인 미확인. 핵심 기능이 아니므로 보류 |

## 기획에 주는 영향
- **핵심 루프(정차 → 말하기 → 출발 → 중단)는 SDK로 구현 가능하다.** 기획서 3번과 5번을 그대로 유지한다.
- 기획서 5번의 SDK 표를 수정한다.
  - 속도는 FusedLocation이 아니라 AAOS `CarPropertyManager`다.
  - 정차 판단은 Brake와 Gear로 충분하다. 속도는 보조로만 쓴다.
- STT가 1회성이라서, "빨간불 한 번 = 한 문장"이라는 설계와 잘 맞는다.
- 말하기방의 "저소음 공조"는 풍량 제어 문제가 풀리기 전까지 기획에서 빼거나 창문 닫기만 남긴다.

## 남은 확인
1. 사람 목소리로 STT 영어 인식률 확인 (에뮬레이터 Extended Controls → Microphone, 호스트 마이크 켬)
2. TTS 영어 발음 품질을 직접 듣고 판단
3. `setSpeakerType` 유효 값 확인
4. 공조 풍량 실패 원인(error code 2)
5. 스파이크 버그: Activity가 다시 만들어지면서 ViewModel 구독이 두 번 일어나 로그와 STT 요청이 중복된다. 본 개발에서는 SDK 콜백 등록을 Repository 한 곳에서 관리한다.
6. adb 인텐트로 셀프 테스트를 실행하는 기능이 동작하지 않았다(onNewIntent 경로). 본 개발 전에 원인을 볼 필요는 없다.

## 개발 환경 메모
- 8GB Mac에서는 **재부팅 직후 + Android Studio 끄고 + `--no-daemon` 빌드**일 때만 안정적이었다. 재부팅 전에는 스왑 13GB 상태에서 에뮬레이터 시스템 서비스가 반복해서 재시작됐다.
- Kotlin 컴파일은 `kotlin.compiler.execution.strategy=in-process`로 메모리를 아꼈다.
