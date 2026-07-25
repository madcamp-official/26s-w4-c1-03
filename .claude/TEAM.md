# GAMDO P1 에이전트 팀 운영 가이드

> 대상: 팀 리드(메인 세션). 범위: **P1(담당 A / 모바일) 잔여 작업 전용.**
> `gamdo-server` 변경은 이 팀의 범위 밖이며, 필요하면 에이전트가 리드에게 에스컬레이션한다.

---

## 1. 팀 구성

| 에이전트 | 모델 | effort | 담당 P1 섹션 | 성격 |
|---|---|---|---|---|
| `guide-capture-agent` 🔵 | **opus-5** | max | §2-4 · §3-1 · §3-2 · §3-3 · §6-1(카메라) · §7-1 | 카메라·오버레이·KPI. **wave 0 파운데이션 담당** |
| `local-edit-agent` 🟠 | **opus-5** | max | §4-1 · §4-2 · §4-3 | 로컬 보정·결과 화면. `edit/`는 완전 그린필드 |
| `reference-net-agent` 🟣 | sonnet-5 | max | §1-6 · §5-1 · §5-2 · §5-3 · §6-1(네트워크) | 서버 접점 전부 |
| `onboarding-polish-agent` 🟢 | sonnet-5 | xhigh | §6-2 · §6-3 · §7-2 · §7-3(토글까지) | 온보딩·피드백·폴리싱 |
| `spec-test-auditor` 🔴 | sonnet-5 | max | (없음 — 잔여 15개 섹션 전체 감사) | **소스 read-only.** 빌드·테스트 유일 실행자 |

**분할 원칙: 기능 수직선.** 각자 사용자가 보는 흐름 하나를 끝까지 소유한다. `AGENTS.md §7-1`의 done 정의("실기기에서 흐름을 끝까지 수행 가능")가 레이어가 아니라 흐름 단위라서다.

**모델 배분 근거:** 수치·성능·아키텍처 결정이 걸린 두 축(가이드 오버레이 안정화 + 성능 30FPS, 로컬 보정 알고리즘 + 2초 예산)만 opus-5. 나머지는 이미 완성된 로직·계약을 배선하는 정형 작업이라 sonnet-5로 충분하다. `reference-net`은 blocker 규칙을 가장 많이 지나므로 모델은 낮추되 effort를 max로 올려 보상했다.

---

## 2. ⚠️ effort는 팀 모드에서 적용되지 않는다

정의 파일의 프론트매터 중 **팀원(teammate)으로 스폰될 때 반영되는 것은 `tools`와 `model`뿐**이다.

> "Teammates inherit the lead's effort level" — [agent-teams.md](https://code.claude.com/docs/en/agent-teams.md)

| 필드 | 서브에이전트 | 팀원 |
|---|---|---|
| `model`, `tools` | ✅ | ✅ |
| `effort` | ✅ | ❌ 리드 것을 상속 |
| `skills`, `mcpServers` | ✅ | ❌ (문서 명시) |
| `permissionMode`, `maxTurns`, `color`, `hooks` | ✅ | 문서 침묵 (미반영 추정) |

**실무 대응:**
1. 리드 세션의 effort를 팀의 하한선으로 삼는다. `xhigh` 이상을 유지할 것.
2. `effort: max`가 실제로 필요한 국소 작업(오버레이 안정화 하네스 설계, LocalEditor 알고리즘 선택)은 **팀원에게 맡기지 말고 리드가 같은 정의 파일을 서브에이전트로 호출**한다. 서브에이전트는 `effort`가 정상 반영된다.
3. 프론트매터의 `effort`는 그대로 둔다 — 팀원일 땐 무시되고 서브에이전트일 땐 살아나므로 손해가 없다.

참고: `effort: ultracode`는 존재하지 않는 값이다. 프론트매터 최댓값은 `max`이고, `ultracode`는 세션 레벨(`/effort ultracode`)이나 프롬프트 키워드로만 동작한다.

---

## 3. 스폰 전에 리드가 끝낼 것

에이전트가 선의로 고르면 그대로 blocker 위반이 되는 항목들이다. **미결정 상태로 스폰하지 말 것.**

### 3.1 컷라인 결정 4건

| # | 항목 | 충돌 내용 | 권고 |
|---|---|---|---|
| Ⅰ | §6-2 `MIN_PICKS` | 코드는 3([OnboardingScreen.kt:47](../app/src/main/java/com/gamdo/app/ui/onboarding/OnboardingScreen.kt:47)), 플랜은 5 | 택1 후 문구까지 통일 |
| Ⅱ | §6-3 "내 스타일 화면" | D11 및 §6-2 "별도 요약 화면 없음"과 정면 충돌. 부록 A 컷라인 4번 | **만들지 않음** |
| Ⅲ | §5-2 목표 실루엣 모드 토글 | 부록 A 컷라인 1번 | 컷 권고 |
| Ⅳ | §4-2 스타일 강도 슬라이더 | 부록 A 컷라인 2번 | 컷 권고 |

### 3.2 R4 유예 발행

`P1_Plan_1.md §0.4`의 "Day 6부터 신규 기능 금지"는 달력 기준이다. 지금 Day 4~6 기능이 통째로 미착수라 팀 작업 **대부분이 형식상 신규 기능**이 된다. 유예를 명시하지 않으면 감사자가 전 작업을 위반으로 올린다.

> **R4 유예:** R4는 `P1_Plan_1.md`에 없는 신규 기능에만 적용한다. 플랜에 명시된 미완료 섹션 구현은 R4 위반이 아니다.

### 3.3 실기기 일정 확정

**이 팀 구성의 정당화가 여기에 걸려 있다.** 수직 분할의 배당은 "각자 자기 데모 경로를 끝까지 검증"인데, 기기가 없으면 4명 전원이 DONE-JVM 천장에서 멈추고 미검증 코드만 4배속으로 쌓인다. 에뮬레이터는 대안이 아니다(ML Kit 얼굴 감지가 가상 씬에서 제대로 안 돌고, FPS·발열 수치가 무의미).

기기 일정이 불확실하면 **3+1로 축소**를 검토할 것.

### 3.4 담당 B에 밀어야 할 것

- 온보딩 카드 이미지 **16장** (`assets/cards/card_01.jpg`~`card_16.jpg` — 리포지토리에 전무, `cards.json`은 이미 참조 중) + 라이선스 확인
- `/references/analyze`, `/edit-jobs` 실서버 안정 가동
- 자동 행인 마스크, FLUX.1 Fill
- `assets/presets.json`이 임시 폴백인지 확정본인지 서명
- 데모용 "망한 사진 10장" 세트 (§4-1 완료 기준의 입력 데이터)

---

## 4. 스폰 명령

정의 파일은 이미 `.claude/agents/`에 있다. 리드에게 이렇게 지시한다:

```
.claude/agents/ 의 정의로 팀을 스폰해줘.
guide-capture-agent, local-edit-agent, reference-net-agent, onboarding-polish-agent,
spec-test-auditor 다섯 개를 각각의 agent type으로 팀원으로 띄우고, wave 0을 시작해.

전원 공지:
1. 기준선 = HEAD 37b4689, :app:testDebugUnitTest 35 tests / 0 failures / 9 클래스 그린.
2. R4 유예: R4는 P1_Plan_1.md 범위 밖 신규 기능에만 적용한다.
3. 컷라인 확정: (§3.1 표의 결정을 여기 적어 전달)
4. 빌드 토큰: 풀 assembleDebug와 전체 테스트는 spec-test-auditor만. 구현 4명은
   :app:compileDebugKotlin 과 --tests 좁은 필터만. clean 은 전원 금지.
   JAVA_HOME 은 반드시 gradle 호출과 같은 명령 안에.
5. 공유 예외 2파일(data/AppContainer.kt, GamdoApplication.kt): 편집 직전 재-Read,
   추가만, 알파벳순, 남의 프로퍼티 무접촉.
6. 커밋·푸시 전원 금지. 제안 커밋 메시지만 제출.
7. 실기기 없음 → DONE-JVM 까지만 판정하고 DONE-DEVICE 체크리스트를 적립.
   더미로 완료를 위장하는 것은 AGENTS.md §7-6 위반.
```

---

## 5. 웨이브 순서

| 웨이브 | 내용 | 병렬도 |
|---|---|---|
| **wave 0** | 이음매 확정. guide-capture: 잔여 10개 DAO 4파일 일괄 신설 + `GamdoDatabase.kt` 등록(이후 동결), `CameraScreen.kt` → host + `CameraViewModel.kt` + 슬롯 분해. local-edit: `ResultScreen.kt` → host + 슬롯 2개, RenderEffect/AGSL/OpenCV 스파이크. reference-net: 로컬 서버 4개 엔드포인트 실호출 검증(편집 없음). onboarding-polish: `CardRepository.kt` + 에스컬레이션 2건 | 4 |
| **wave 1** | 수직선 내부 병렬, 최대 처리량. §2-4→3-1→3-2 / §4-1→4-2 / §5-1 / §6-2. **파일 교차가 설계상 0이어야 한다** — 교차 발생 시 경계 설계 오류이므로 즉시 리드 보고 | 4 |
| **wave 2** | 합류. 유일한 진짜 직렬 구간. 슬롯 배선 3건: reference-net→guide-capture(`ReferenceOverlayLayer`), reference-net→local-edit(`GenerativeRestorePanel`), onboarding-polish→local-edit(피드백 시트) | ~2 |
| **wave 3** | 마감. §3-3 KPI → §6-1 → §7-1 성능 / §4-3 / §5-3 플래그 / §7-2 | 4 |
| **wave D** | **기기 연결 시점에만.** 적립된 DONE-DEVICE 체크리스트 소진. 기기 1대라 시분할 직렬. 순서 고정: §3-2 오버레이 안정성(§0.4 go/no-go) → §4-1 2초 예산 → §3-3 DB 스냅샷 → §5-3 폴백 → §6-1 | 1 |

각 웨이브 종료 시 `spec-test-auditor`가 감사 + 그린 확인 → 리드가 묶어서 커밋.

---

## 6. 리드 수칙 (위임 불가)

1. **커밋은 리드 단독.** 4명은 제안 메시지만 제출. 웨이브 종료 후 그린 확인하고 묶어서 커밋한다. 웨이브 중간 커밋은 다른 에이전트의 미완 편집을 함께 삼킨다.
2. **커밋 전 `git diff`로 경계 침범 확인.** 공유 트리라 소유권 위반은 diff에서만 드러난다.
3. **`P1_Plan_1.md` 체크박스 `[x]` 전환은 리드 전용.** §7-1의 done은 실기기 수행 가능이므로 DONE-JVM만으로 체크하지 않는다. `AGENTS.md`·`docs/**` 수정도 리드 전용.
4. **§0.4 go/no-go 2건은 리드 판정.** Day 3 오버레이 안정성(불안정 → 정적 프리셋 프레임으로 다운그레이드), Day 5 생성 안정성(불안정 → 기능 플래그로 숨김). 에이전트에게 판정을 위임하지 말고 **수치만 받는다.**
5. **§0.3 매일 일과는 전부 리드 몫.** 아침 B와 데모 기준 확정, 저녁 실기기 3회 연속 통합 테스트, 저녁 `presets.json` 공동 튜닝. §7-3 리허설·녹화·소품, §7-4 판정·APK 2부도 위임 불가.
6. **핸드오프 중재가 주업.** wave 2의 배선 3건에서 요청이 큐에 쌓이면 우선순위를 정한다. 슬롯 시그니처 재협상은 최소 한 번 일어난다고 가정할 것.
7. **§6-1은 직접 검수.** 5개 장애 상황이 두 에이전트로 갈려 있어 문구 톤과 폴백 동작이 제각각 나온다. 이 분할의 최대 약점.
8. **§5-1·§5-3 diff는 눈으로 훑을 것.** 되돌리기 가장 어려운 규칙(EXIF 위치 스트립, 자동 업로드 금지, 실패 미노출, 더미 위장 금지)을 가장 낮은 모델이 담당한다.
9. **`presets.json` 이중 관리는 수동.** 앱 번들본과 서버본 동기화는 P1/P2 경계라 이 팀이 못 고친다. 감사자가 바이트 동일성을 보고하면 리드가 맞춘다.

---

## 7. 알려진 위험

1. **기기 부재가 팀 구성의 정당화를 무력화한다.** 오늘만 보면 레이어별 수평 분할이 충돌은 적고 검증 성과는 같다. DONE-JVM/DONE-DEVICE 2단계 판정이 완화책이지만, 기기가 끝까지 없으면 이 팀은 충돌 비용만 지불한다.
2. **§4-1은 구조적으로 검증 불가.** androidTest 소스셋이 없고 Robolectric 미도입이라 `Bitmap`·`RenderEffect` 코드가 JVM에서 한 줄도 안 돈다. 순수 코틀린 연산부와 렌더러부의 경계를 강제하고 연산부에만 테스트를 붙이게 했다. Robolectric 도입은 리드 승인 사항(빌드 시간 비용).
3. **슬롯 계약은 충돌을 지연으로 바꿀 뿐이다.** sonnet 둘이 opus 둘의 응답을 기다리는 구조라 실효 병렬도는 4가 아니라 ~2.5.
4. **§5-3은 성실히 해도 산출물이 0에 수렴할 수 있다.** B의 자동 마스크·FLUX.1 Fill 미해결 → 규칙대로면 기능 플래그 off. UI·폴링·폴백까지는 확보하고, 대기 발생 시 §6-1이나 §5-1로 부하를 옮긴다.
5. **공유 트리 단일 실패점.** 한 명이 컴파일을 깨면 나머지 3명의 gradle이 막힌다. 빌드 토큰과 `clean` 금지가 완화책이지만 웨이브 중간은 무방비.
6. **소유 경계가 아직 없는 파일에 그어져 있다.** `CameraViewModel.kt`, `LocalEditor.kt`, `CardRepository.kt`, DAO 4종 등이 신규 파일이라 남의 디렉터리에 생기면 `forbiddenPaths`가 무력화된다. wave 0의 "신규 파일 경로 사전 등록"이 방어선이고, 규율 기반이라 가장 먼저 깨진다.
7. **감사자가 4명분 diff의 단일 큐.** read-only라 "발견 → 리드 → 소유자 → 재감사" 3홉 루프가 돈다.
8. **콘텐츠 차단 2건은 에이전트로 해결 불가.** 카드 이미지 16장과 데모용 사진 세트는 사람이 치워야 한다.
9. **SendMessage는 전달이 보장되지 않는다.** 완료된 에이전트를 깨워 보내면 다른 메시지와 경합해 판정이 유실될 수 있다(wave 0에서 실측: guide-capture에 2회 엇갈림). **구속력 있는 판정은 반드시 아래 §8에 기록하고 다음 웨이브 스폰 프롬프트에 실어라.** 메시지는 통지용이지 기록용이 아니다.

---

## 8. 리드 판정 기록 (구속력 있음 — 재론 불가)

> 스폰 프롬프트에 반드시 실어 전달할 것. 메시지 전달에 의존하지 마라(§7-9).

### wave 0 컷라인

| # | 판정 | 근거 |
|---|---|---|
| Ⅰ | §6-2 `MIN_PICKS` = **5** | 문서 우선순위상 플랜 > 코드. `ProfileEngine`이 차원별 분산으로 확신도를 계산해 3표본은 §6-2 완료 기준을 못 만듦 |
| Ⅱ | §6-3 "내 스타일 화면" = **만들지 않음** | D11 및 §6-2 "별도 요약 화면 없음"과 충돌, 부록 A 컷라인 4 |
| Ⅲ | §5-2 목표 실루엣 모드 토글 = **컷** | 부록 A 컷라인 1. 레퍼런스 반투명 원본 오버레이만 유지 |
| Ⅳ | §4-2 스타일 강도 슬라이더 = **컷** | 부록 A 컷라인 2 |
| Ⅴ | **R4 유예** | R4는 `P1_Plan_1.md` 범위 **밖** 신규 기능에만 적용. 플랜에 명시된 미완료 섹션 구현은 R4 위반이 아님 |

### 에스컬레이션 판정

**`sessions.final_match_score` = §4.2 가중 점수(`MatchScoreCalculator`). IoU 아니다.** ← §3-3 블로커
`AGENTS.md §9`가 matchScore를 "KPI 로그 전용"으로 정의하고 계산은 기능명세서 §4.2가 규정한다. `AlignmentEngine.metrics().matchScore`의 IoU는 정렬 판정용 내부 지표로 **이름만 겹친 다른 것**이며 로깅하지 않는다. §3-3에서 `MatchScoreCalculator`를 파이프라인에 배선하고 그 출력만 기록할 것. 호출부에 "이 IoU는 §4.2 matchScore가 아니다" 주석 필수. `AlignmentEngine.kt`(B 파일)는 무접촉.

**`FrameFeatures.aspectRatio` 누락 = 막지 마라.** 4:5 / 1:1은 이미 카메라 UI 토글 상태로 존재하고 `FrameFeatures`에 중복 적재할 실제 소비처가 없다. B에 낮은 우선순위로 올림. 진짜 소비처가 생기면 그때 재에스컬레이션.

**릴리스 HUD 토글 = 지금 고쳐라. R4 해당 없음.** `showHud` 초기값은 `BuildConfig.DEBUG`인데 상태 칩 탭이 무조건 토글돼 릴리스에서 `DebugHud`(ms/fps/drop%)·`TiltBadge`가 노출된다(`GuideDebugBadge`는 이중 게이트라 안전). 칩 토글을 `BuildConfig.DEBUG` 뒤로 게이팅. **신규 기능이 아니라 누수 차단.** §7-2의 "개발자 제스처로만 열림"은 wave 3 몫. 감사 결과 이 누수는 `CameraScreen.kt` 한 곳에 국한(온보딩 쪽은 clean).

**`PresetProfileMapper` 승격 = 승인. 단 발명 금지.** `ProfileEngine.recommend()` 102행이 `?: value.mean`이라 **키가 없으면 거리 기여가 정확히 0**이다. 즉 생략이 "모른다"의 정확한 표현이고 지어낸 값은 임의로 순위를 가른다. 차원별 MAP / DERIVE / OMIT을 KDoc에 명시할 것. (적용 완료: `framing`·`candidness` 생략)

**갭 A 에러 봉투 DTO = 승인** (§10에 이미 명세된 계약 파싱이라 B 서명 불필요). **갭 B presets ETag = 보류** (플랜에 없는 최적화). **`assets/presets.json` = 확정본** (서버본과 개행만 다르고 내용 100% 동일, 실호출 검증). **`CachedReferences.paletteJson` 컬럼명 = 동결 유지** (불변규칙 2, KDoc으로 실제 내용 명시).

**`GenerativeTabState.enabled` = R6 기능 플래그로만 구동. 잡 실패로 토글하지 마라.**
reference-net의 해석이 맞다. 두 층위를 섞으면 안 된다 — **R6(기능 숨김)** 은 생성 기능 자체가 불안정할 때 플래그로 통째로 감추는 것이고, **R5(실패 흡수)** 는 기능이 켜진 상태에서 개별 잡이 실패했을 때 "자연스러운 보정만 적용했어요"로 기본 보정 결과를 유지하는 것이다. 잡 하나 실패했다고 탭이 사라지면 사용자에겐 UI가 깜빡인 것으로 보이고, 반대로 기능이 통째로 불가능한데 탭만 열려 있으면 R6 위반이다.
→ `enabled`는 reference-net 소유의 R6 플래그 상수가 결정. 개별 실패는 `reportFallbackToBasic()`으로 처리. `fallbackToBasic=true`일 때 사진 영역의 주인이 host인지 슬롯인지는 local-edit이 결정해 슬롯 계약에 명시할 것.

### 소유권 추가 배정

- **`app/src/main/AndroidManifest.xml` → 리드 소유.** 변경 빈도는 낮은데 앱 전체 폭발 반경(잘못된 authority는 설치 자체를 깨뜨림)을 갖는 파일이라 두 클레임 사이를 중재하는 대신 리드가 쥔다. 필요하면 리드에게 요청하라. (wave 0에서 FileProvider `<provider>` 블록 추가 완료 — `${applicationId}.fileprovider`, `exported=false`, `grantUriPermissions=true`, `@xml/file_paths` 참조. §6-3 [공유] 버튼 차단 해소)
- **`ui/navigation/**` = 공백 아님. guide-capture-agent 소유다.** wave 0에서 세 에이전트가 "소유자 없음"으로 보고했으나 오독이다 — guide-capture의 소유 목록에 있고 나머지 셋은 명시적 금지다. `GamdoNavHost.kt`의 슬롯 배선 요청은 guide-capture로 보내라.
- `app/src/test/java/com/gamdo/app/ui/camera/**` → **guide-capture-agent**
- `data/GuideKpiRepository.kt` → **guide-capture-agent** (§3-3 KPI 쓰기 전용 통로)
- DAO 4파일 이관 완료: `EditDaos.kt`→local-edit / `NetworkDaos.kt`→reference-net / `ProfileDaos.kt`→onboarding-polish. `GamdoDatabase.kt`는 **동결**

### 담당 B 미결 4건

1. **`ProfileEngine.recommend()` 켈빈 정규화 버그** — 거리 합산이 `colorTemperature`(4600~6200K)와 0~1 차원을 정규화 없이 더해 색온도가 순위를 지배(실측 501.3 중 500.0). 구도 5차원 기여도 ≈ 0. 어댑터에서 수정 불가(`applyFeedback`의 `-350f` 켈빈 델타가 깨짐) → B 모듈 수정 필요. 카나리아 테스트가 `PresetProfileMapperTest`에 있음
2. **카드 이미지 16장** — `cards/card_01.jpg`~`card_16.jpg`, 3:4, JPEG, 긴 변 1024px, 200~400KB. 라이선스 확인 포함
3. **자동 행인 마스크 · FLUX.1 Fill** — §5-3 실제 생성 경로
4. **matchScore 이름 충돌 정리** — `AlignmentEngine`의 IoU와 §4.2 가중 점수가 같은 이름을 씀
