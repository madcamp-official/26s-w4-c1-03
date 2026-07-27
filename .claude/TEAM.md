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

### Day 1~3 착수 판정 (2026-07-26) — 오너 결정 1건 + 리드 결정 2건

**minSdk 26 → 29 (오너 결정, "지원 기기 줄어들어도 상관없어").** API 26~28의 MediaStore 무음 실패를 권한 분기로 우회하는 대신 **문제를 소멸**시킨다 — API 29+는 scoped storage라 `WRITE_EXTERNAL_STORAGE`가 불필요하다. 부수 효과로 `CaptureRepository.kt:391·401`의 `SDK_INT >= Q` 분기가 항상 참이 되어 레거시 경로가 죽고, 매니페스트의 `WRITE_EXTERNAL_STORAGE`(maxSdkVersion=28) 선언도 무의미해진다. `READ_EXTERNAL_STORAGE`(maxSdkVersion=32)는 29~32 구간에 여전히 필요하니 남긴다.
⚠️ **단, "실패를 삼키는" 절반은 그대로 남는다** — 용량 부족·MediaProvider null은 API와 무관하다. `runCatching`이 삼키는 대신 정직하게 알리도록 함께 고친다. R5("실패를 사용자에게 노출하지 않는다")는 **생성 실패**에 대한 규칙이지 저장 실패에 대한 규칙이 아니다.
→ `AGENTS.md`와 `P1_Plan_1.md §1-1`의 "minSdk 26" 서술을 리드가 갱신해야 한다.

**세션 중 스타일 변경은 영속화하지 않는다 (세션 한정).** `app_settings.style_preset_id`는 D4가 규정한 **개인화 프로필**이고 온보딩 카드 선택의 산물이다. 세션 중 잠깐 바꾼 값으로 덮어쓰면 §6-2 완료 기준("서로 다른 카드 2세트가 스타일 스트립 상위 순서를 다르게 만듦")이 검증 불가능해진다. 사용자가 카메라에서 고른 값은 세션 상태로만 들고, 앱을 다시 켜면 온보딩 프로필로 돌아간다.

**탭 포커스 시각 피드백(초점 링)은 만들지 않는다.** §3-2가 카메라 화면에 "브래킷 + 실루엣 + 수평선 **셋만**"으로 못박았고 링은 네 번째 시각 요소다. D2의 금지 목록(안내 문구·화살표·게이지·자동촬영)에 문자 그대로 있지는 않지만 §3-2의 "셋만"에 걸린다. 피드백은 이미 존재한다 — 프리뷰가 흐려졌다 다시 잡히는 racking이 그것이고, 기기 확인 절차도 그것을 판정 기준으로 삼는다.

### wave 1 판정 (2026-07-25)

**`captures.conditions_json` 기울기 계약 — 확정.**
키는 **`tiltDeg`** (camelCase 한 가지만. local-edit의 `tilt_deg` 폴백은 남겨도 되지만 생산자는 camelCase로만 쓴다).
값은 **`TiltSensor.TiltReading.rollDeg` 원본을 변형 없이** 넣는다.
**⚠️ 정정 (guide-capture 지적 반영).** 이 판정의 최초 근거 2번("`CameraOverlay`가 `roll`만큼 회전시키고 실기기 확인됨")은 **틀렸다.** 실제 코드는 `CameraOverlay.kt:95`의 `rotate(degrees = -rollDeg)`이다. 결론(레벨링 부호)은 바뀌지 않지만 **오버레이 쪽에 실제 버그가 있다.**

유도 (한 번만 제대로):
- `TiltSensor`: `roll = atan2(gx, gy)`. `TYPE_GRAVITY`는 월드 up을 기기 축으로 보고한다. 기기를 **시계방향 θ** 회전하면 월드 up의 기기 좌표 성분은 `(-sinθ, cosθ)` → **`rollDeg = -θ`**.
- 카메라가 시계방향 θ 회전하면 **장면은 프레임 안에서 반시계 θ로 보인다.** Compose `rotate()`는 양수가 시계방향이므로, 이미지 속 수평선의 각도 = `-θ` = **`rollDeg`**.

→ **두 부호는 같지 않다. 서로 반대여야 한다.**

| 용도 | 올바른 값 | 현재 코드 | 판정 |
|---|---|---|---|
| **오버레이 수평선을 실제 수평에 겹쳐 그리기** (`CameraOverlay`) | `rotate(rollDeg)` | `rotate(-rollDeg)` | ❌ **반전됨 — wave 2에 guide-capture가 수정** |
| **촬영 결과의 수평을 되돌리기** (`GeometryPlan.levelingRotationDeg`) | `-tiltDeg` | `-tiltDeg` | ✅ **맞다. 건드리지 마라** |

하나는 장면의 각도를 **따라 그리는** 것이고 다른 하나는 그 각도를 **상쇄하는** 것이라 부호가 반대인 게 정상이다.

**⚠️ 기기 없이도 확정할 수 있는 사실 — 지금 둘 중 하나는 반드시 틀렸다.**

프레임 안 수평선의 각도를 `A`(시계방향 양수)라 하자. 센서 규약이 무엇이든:
- 오버레이는 그 선을 **따라 그려야** 하므로 `rotate(A)`
- 레벨링은 그 각도를 **상쇄해야** 하므로 `rotate(-A)`

`rollDeg = A`든 `rollDeg = -A`든 무관하게 **오버레이 회전 = −(레벨링 회전)**이 항상 성립한다. 그런데 현재 코드는 `CameraOverlay` = `-rollDeg`, `levelingRotationDeg` = `-tiltDeg`로 **둘 다 `-roll`이다. 같은 부호다.** 따라서 **센서 규약과 무관하게 정확히 하나가 틀렸다.** 기기 검증은 "둘 다 맞는지"가 아니라 **"어느 쪽이 틀렸는지"**를 가리는 절차다.

위 유도(`rollDeg = -θ`)가 맞다면 틀린 쪽은 **오버레이**다. 유도가 반대라면 틀린 쪽은 **레벨링**이다.

**리드 직접 편집 기록 (경계 예외):** `camera/TiltSensor.kt`의 `TiltReading` KDoc을 리드가 직접 정정했다(주석만, 로직·테스트 무변경, `compileDebugKotlin` 재확인 통과). guide-capture 소유 파일이지만, 잘못된 결합 주장이 "단일 진실 공급원"으로 적혀 있고 local-edit의 KDoc이 그곳을 가리키고 있어 두 에이전트가 그 지침대로 **양쪽을 함께 뒤집을 위험**이 있었다. 메시지 전달이 두 번 엇갈린 이력이 있어 파일에 직접 반영했다. guide-capture는 wave 2에서 이 KDoc을 자기 소유로 다시 인수한다.

**따라서 `GeometryPlan.kt` KDoc에 local-edit이 적은 "오버레이가 기기 검증을 통과하면 내 `-tiltDeg`도 함께 뒤집힌다"는 서술은 잘못이다 — 두 부호는 함께 움직이지 않고 항상 반대다.** 그 KDoc은 wave 2에 local-edit이 정정한다. 같은 오해를 guide-capture도 공유하고 있으니 양쪽에 전달할 것. **어느 쪽도 "맞추려고" 상대에 동기화시키지 마라.**

⚠️ 전부 계산으로 얻은 결론이고 기기 검증 전이다. **DONE-DEVICE 2건 추가**:
1. 기기를 시계방향으로 눈에 띄게 기울여 촬영 → 결과 화면 기본 보정에서 수평이 **되돌아오는지**(더 기울어지면 부호 반대). 틀리면 기울기가 두 배가 된다.
2. 창틀·책상 모서리 등 **진짜 수평선이 보이는 장면**에서 오버레이 수평선이 그것과 **겹치는지**. 반전된 수평선도 기울이면 같이 기울고 수평에서 초록이 되므로, 진짜 수평과 대조하지 않으면 육안으로 안 걸린다 — §2-5의 "실기기 확인" 이력이 이걸 통과시켰을 가능성이 높다.

**⚠️ `conditions_json`은 아직 생산자가 없다.** `CaptureSnapshot()` 기본값(`"{}"`)만 흘러가고 있어 `tiltDeg`는 항상 0이고 **§4-1의 수평 보정 단계는 한 번도 실행된 적이 없다.** 생산자 배선은 guide-capture의 §3-3(wave 3)이다. 그때까지 §4-1 기하 단계는 "코드는 있으나 미가동" 상태로 판정하라.

**상단 바 = wave 2에 한 번에 짓는다.** §3-2의 미착수 2건(오버레이 on/off 토글, 스타일 이름+변경 버튼)과 reference-net의 §5-1 레퍼런스 진입점이 **전부 같은 자리**를 놓고 대기 중이다. 스타일 스트립(§6-2)은 onboarding-polish 축소로 무기한 연기됐으니 그걸 기다리면 §3-2가 영원히 안 닫힌다. → guide-capture가 wave 2에 상단 바를 한 번에 짓고 세 요소를 함께 넣는다. 같은 파일을 세 번 흔드는 것보다 싸다. 업로드 고지 문구는 리드가 확정해 전달한다.

**승인 — 되돌리지 않는다:**
- 얼굴 박스·인물 중심점을 `BuildConfig.DEBUG` + HUD 토글 이중 게이트 뒤로 이동(guide-capture). §3-2가 "브래킷+실루엣+수평선 셋만"이므로 raw 감지 표시는 개발 어포던스가 맞다. 부수 효과로 매 프레임 깜빡임이 프로덕트 경로에서 사라졌다.
- `GuideLime(#CDD69A)` 삭제 → `Sage` 수렴(guide-capture). D11-5 잔여물이었다.
- **`blurStrength`를 계획·기록만 하고 렌더하지 않음**(local-edit). 마스크 없는 스무딩을 인물에 거는 것은 D8-1 "피부 매끄럽게" 표면 그 자체다. §4-1 스타일 목록에도 없다. 프리셋이 들고는 있으나 의도적으로 미적용인 필드로 남긴다 → 담당 B 통지 대상.
- `LocalEditor.render`의 `OutOfMemoryError` catch(local-edit). `Error` catch는 통상 잘못이나 여기선 실패한 할당이 이미 도달 불가이고, 사이트가 단일하며(`Bitmap.createBitmap`), `RESOLUTION_LADDER`로 유계이고 바닥에서 rethrow한다. 기기가 없어 사전 정확 사이징이 불가능한 상황의 정당한 완화다.
- `guide_config.json` v1 flat → v2 네임스페이스 전환(guide-capture). v1 하위호환 유지 + `P2ValueDumpTest` 무영향 확인됨.

**`scoring` 네임스페이스 = 비워 둔 채로 유지.** `MatchScoreCalculator`의 §4.2 가중치가 컴파일타임 상수이고 생성자 seam이 없어 외부화하려면 B 파일 로직 수정이 필요하다. 값을 적어 두면 "설정한 것처럼 보이는데 안 먹는" 함정이 되므로 비워 둔 판단이 옳다 → 담당 B 미결로 승격. §3-3에서 matchScore를 기록할 때 재판단.

### `conditions_json` 키 세트 확정 (wave 3에 guide-capture가 쓴다)

local-edit이 소비처 관점에서 답했고 채택한다.

| 키 | 판정 | 근거 |
|---|---|---|
| `tiltDeg` | **필수** | 저장된 픽셀에서 복원 불가능한 유일한 값. 상수 `CaptureSnapshot.KEY_TILT_DEG`로 고정(오타 시 무음 0°가 아니라 컴파일 에러) |
| **`personBox`** | **추가 — 최우선** | local-edit 목록에 없었으나 **가장 값이 크다.** 이게 없어서 지금 3가지가 죽어 있다: §4-1 "인물 중심 유지" 크롭(프레임 중앙으로 폴백), `EXCESS_MARGIN`(여백 항상 0), `BACKLIGHT` 픽셀 경로(비율 항상 null). ML Kit은 셔터 시점에 이미 돌았고, 저장된 비트맵에서 재검출하면 200~400ms를 쓰면서 사용자가 본 오버레이와 불일치할 수 있다 |
| `brightnessMean` | **넣지 마라** | local-edit이 저장된 JPEG에서 직접 측정한다. 셔터 시점 프리뷰 프레임은 노출·해상도·프레이밍이 달라 정확도가 낮고, 같은 수치의 진실 공급원이 둘이 된다 |
| `lowLightFlag` / `backlightFlag` | `FrameFeatures`로 | 진단기의 두 번째 입력 채널이지 `ImageMetrics`가 아니다 |
| `shake` | 소비처 없음 | |

**`FrameFeatures.aspectRatio` = 종결.** "실제 소비처가 생기면 재에스컬레이션"으로 파킹했는데, local-edit이 자신이 그 소비처인지 확인한 결과 **아니다** — 셔터가 비율을 픽셀에 굽기 때문이다. 재개하지 말고 닫는다.

### 승인 — `CameraController` 캡처 FOV 강제 (wave 2 착수 즉시, 첫 항목)

`camera/CameraController.kt`가 **분석·프리뷰만** 4:3으로 강제하고 `imageCaptureResolutionSelector`는 설정하지 않는다. 파일 주석은 "프리뷰 FOV를 분석에 맞춰 오버레이 좌표를 정합"이라 적혀 있는데 세 번째 use case인 캡처가 빠졌다.

**승인한다.** guide-capture가 wave 2 첫 항목으로 처리하라(같은 파일의 기존 패턴에 `RATIO_4_3_FALLBACK_AUTO_STRATEGY` 한 블록 추가).

근거:
1. **`subject` 계약을 막고 있다.** 분석 좌표 → 저장 파일 좌표 변환은 두 FOV가 같아야 선형 대응이 성립한다. 그런데 `subject`는 방금 `conditions_json`에서 **가장 값이 큰 키**로 확정됐다(죽어 있는 기능 3개를 켠다). 여기서 막히면 그게 다 밀린다.
2. **WYSIWYG는 데모 품질 문제다.** 사용자가 프리뷰에서 잡은 화각과 저장된 사진의 화각이 다르면 §7-1/§7-2 이전에 §1-5의 완료 기준부터 흔들린다.
3. **위험이 낮다 — 관측된 동작을 코드로 굳히는 것에 가깝다.** §1-5 진행 메모의 실기기 기록이 "원본 3024×3780 = 정확히 4:5"인데, 3024×3780은 3024×**4032**(정확히 4:3) 센서 출력을 4:5로 센터크롭한 결과다. 즉 SM-G970N에서 캡처 기본값이 이미 4:3이었다는 증거다. 강제는 그 우연을 계약으로 바꾸는 것이다.

guide-capture가 스스로 하지 않고 물어본 판단(실기기 촬영 해상도·화각에 닿고 전후 비교 불가, wave 1 범위 밖)은 옳았다.

### `conditions_json` JSON 계약 — 확정본

```json
{ "tiltDeg": -2.4,
  "subject": { "left": 0.21, "top": 0.09, "right": 0.79, "bottom": 0.94 } }
```

**⚠️ 재정정 — `lowLightFlag`·`backlightFlag`도 뺀다. 최종 키는 `tiltDeg` + `subject` 둘뿐이다.**
local-edit이 소비 여부를 코드로 확인한 결과:
- `ProblemDiagnoser.diagnose`가 `frameFeatures`에서 읽는 필드는 **`backlightFlag` 단 하나**(123행)이고, 그마저 `metrics.backlightRatio`(픽셀 경로)와의 **OR**다. 픽셀 경로는 `subject`만 있으면 동작한다.
- **`lowLightFlag`는 `diagnose`가 아예 읽지 않는다.** `FrameFeatures`에 정의돼 있고 `FrameFeatureCalculator`가 채우지만, UNDEREXPOSED(69행)는 순수하게 `brightnessMean`/`shadowClipRatio`로만 판정하고 그 둘은 local-edit이 픽셀에서 잰다.
→ `subject`가 도착하면 local-edit은 `frameFeatures = null`을 영구히 넘길 수 있다.

**그 결과 §5의 "부분 채움 취약 이음매"는 완화가 아니라 소멸했다** — 13필드 중 12개를 자리채움으로 넘길 일이 없어진다. **담당 B 미결 목록에 올리지 마라.** guide-capture가 올리려던 에스컬레이션은 철회한다.

**단, guide-capture는 자기 KPI 필드(`brightnessMean`·`shake`·플래그 2개)를 같은 문서에 얹어도 된다** — `parse()`가 모르는 키를 무시하므로 안전하고, 그 필드들의 **독자는 guide-capture 자신**이다. 원칙에 어긋나지 않는다. 위 2키는 **local-edit과의 계약**이지 문서 전체 스키마가 아니다.

**`tiltDeg`는 `Float?`다 (판정 상향).** guide-capture가 "`tiltDeg = 0f`는 '기록 없음'과 '정확히 수평'을 구분 못 한다"고 지적하며 "안전하게 저하되니 타입은 그대로 두자"고 했고 나도 그렇게 기록했었다. **local-edit이 옳게 반박했다** — guide-capture의 §3-3 계획이 "센서가 한 번도 보고 안 하면 키 생략"인데, 읽는 타입이 `Float = 0f`면 **키를 생략하나 `0.0`을 쓰나 파싱 결과가 같아서 그 정직함이 도착 즉시 버려진다.** 안전한 저하가 아니라 정보 손실이다. 그리고 이건 `subject`에서 이미 합의한 논리(`0,0,0,0`이 "박스 없음"을 대신하면 안 된다)와 같은데 한쪽에만 적용돼 있었다.
→ `tiltDeg: Float?`, null이면 `encode`에서 생략. null→0 붕괴는 `tiltDegOrZero` 접근자 한 곳에만 가둔다. 동작은 불변(모름과 진짜 0 둘 다 회전 없음), 정보만 늘어난다.

**파싱 격리 (버그 수정).** 기존 `parse`가 두 필드를 하나의 `runCatching`으로 감싸고 있어 **`tiltDeg`가 깨지면 멀쩡한 `subject`까지 통째로 버려졌다.** 다른 에이전트가 쓴 문서라 한 필드의 버그가 무관한 기능을 꺼버리는 구조였다. 필드별 독립 파싱으로 수정, 비유한(non-finite) tilt는 경계에서 거부.

**`data → edit` 순환은 local-edit이 자기가 만든 부분을 되돌렸다.** `CaptureSnapshot.conditionsJsonOf()`가 `encodeToString()`으로 가는 한 줄 포워더였고 그게 `data`가 `edit`을 import하던 유일한 이유였다 — 삭제하고 지침은 KDoc 산문으로 남겼다. 남은 `data → edit`은 wave 0부터 있던 `EditStep`뿐이다.
**`SubjectBox`를 `data/`로 올리자는 방향은 보류한다.** local-edit이 타당하게 반박했다 — `horizontalMargins`·`backlightRatio`·`computeImageMetrics`가 쓰는 **이미지 분석 타입**이라, 계층 정리를 이유로 옮기는 것은 부수효과로 처리할 일이 아니라 별도 안건으로 다뤄야 한다.

**기록된 트레이드오프 — 패키지 방향.** §3-3이 착지하면 `ui/camera → edit` 엣지가 새로 생기고, `data/CaptureRepository.kt`가 이미 `edit.CaptureConditions`를 import해 `data ↔ edit` 순환이 있다. **지금 고치지 않는다** — 테스트 끝난 코드를 웨이브 중간에 흔드는 비용이 이득보다 크고 PKG-1 위반도 아니다(최상위 구조 불변). 나중에 정리한다면 `CaptureConditions` + `SubjectBox`를 `data/`로 올리는 것이 순환까지 끊는 방향이다.

원칙 확립: **소비처가 실제로 읽는 키만 계약에 넣는다.** 읽는 곳 없는 키를 위해 상수를 만들면 "읽는 사람 없는 데이터를 쓰는" 상태가 되고, 그건 `brightnessMean`에서 막은 진실 공급원 이중화와 같은 문제다.

**전달 수단도 상수가 아니라 타입이다.** local-edit이 `edit/CaptureConditions.kt`를 만들었다 — `@Serializable` 타입 + `encodeToString()`(guide-capture 측) / `parse()`(local-edit 측). **양쪽 다 JSON 키 문자열을 타이핑하지 않는다**(중첩된 `left/top/right/bottom`까지). 왕복 테스트가 있어 한쪽에서 이름을 바꾸면 컴파일이 깨지거나 테스트가 깨진다. 이 이음매는 **조용히** 실패하는 종류라(오타 = "기울기 없음, 인물 없음"으로 읽혀 정상 사진과 구분 불가) 기기 없이 쓸 수 있는 유일한 방어다.
동작도 고정됐다: 인물 미검출 시 키 생략(단언됨), 퇴화 박스(`0,0,0,0`·역전·필드 누락)는 클램프가 아니라 **드롭**해서 `null`이 "모른다"를 계속 의미하게, 모르는 키는 무시(KPI 필드가 같은 문서를 공유 가능), 깨진 JSON은 결과 화면을 실패시키지 않고 `NONE`으로 강등.
- guide-capture 초안에서 **`brightnessMean`과 `shake`를 뺐다** — 위 키 세트 판정대로. `brightnessMean`은 local-edit이 저장 JPEG에서 직접 재는 게 더 정확하고 진실 공급원이 둘이 되며, `shake`는 소비처가 없다.
- `lowLightFlag`/`backlightFlag`는 **남긴다.** 이 둘은 `ImageMetrics`가 아니라 `FrameFeatures`에 속하는 값이 맞고, `conditions_json`이 바로 셔터 시점 `FrameFeatures`를 편집 경로로 나르는 수단이다.
- `subject`는 **guide-capture가 저장 파일 좌표계로 변환해서** 싣는다. 분석 FOV 강제·미러링·센터크롭이 전부 그쪽 사정이라 local-edit이 그 크롭 수학을 복제할 이유가 없다.
- **인물 미검출 시 키 자체를 생략**한다. `0,0,0,0`은 유효한 박스처럼 생겨서 쓰레기 크롭을 만든다.
- 키는 `CaptureSnapshot`의 상수를 통해 쓴다(`KEY_TILT_DEG` 방식) — 오타가 무음 0이 아니라 컴파일 에러가 되도록.

### wave 3 적립 — 항상 켜진 last-features 홀더

guide-capture가 얼굴 박스·중심점을 DEBUG 뒤로 옮기면서 **릴리스 경로에서 `FrameFeatures`를 붙들고 있는 곳이 0곳**이 됐다(`_guideDebug`가 유일한데 `BuildConfig.DEBUG` 게이트). §3-3이 셔터 순간 스냅샷을 요구하므로 wave 3에 `CameraViewModel`에 항상 켜진 홀더를 추가해야 한다. **D2-5 저촉 없음** — §9가 matchScore를 "KPI 로그 전용"으로 규정하므로 계산·저장은 요구사항이고 금지되는 것은 표시다. 지금 깨지는 것은 없다.

### wave 1 라이브 버그 1건 (local-edit 자체 발견·수정)

`CameraScreen.onShutter`가 저장 전에 `centerCropToRatio`를 적용해 JPEG이 이미 4:5 또는 1:1인데, `LocalEditor.plan`이 비율을 **프리셋의 `composition.targetAspectRatio`**(폴백 4:5)로 기본값을 잡고 있었다. → **1:1로 찍은 사진이 결과 화면에서 조용히 4:5로 재크롭**되어 가로 20%가 잘려나갔다(2000×2000 → 1600×2000). 프리셋 미선택 시 모든 정사각 촬영에 발생. **기기·센서 불필요, 현재 빌드에서 재현되는 실제 버그였다.**
수정: 비율 기본값을 사진 자신의 비율(`EditAspect.nearest`)로. 프리셋의 `composition`은 **촬영 가이드**이지 이미 프레이밍된 사진을 다시 자를 근거가 아니다. 회귀 테스트 8건 고정.
부수 성과: `LocalEditor.plan()`이 JVM 테스트 가능함을 발견했다(`Bitmap`을 시그니처에 이름만 올릴 뿐 `plan()`은 만지지 않아 throwing stub renderer로 구동됨). 검증 가능 표면이 순수 함수를 넘어 오케스트레이터까지 닿았고, 이 버그가 살던 자리가 정확히 거기였다.

### 규약 — 심볼 삭제는 교차 에이전트 이벤트다 (실제 사고 기반)

**Kotlin KDoc 링크는 깨져도 컴파일이 통과한다.** local-edit이 `conditionsJsonOf`와 `KEY_TILT_DEG`를 삭제했을 때 guide-capture의 `TiltReading` KDoc이 계속 그 심볼을 가리키고 있었는데 `compileDebugKotlin`은 내내 그린이었다. grep으로만 발견됐고, 아니었으면 **wave 3에서 그 지침을 따라간 사람이 없는 심볼을 찾고 있었을 것**이다.

→ **public 심볼을 지우기 전에 트리를 grep하고, 그 심볼을 참조하는 파일의 소유자에게 통지하라.**

이게 중요한 이유: **빌드도, 테스트도, 감사자의 diff 리뷰도 이걸 못 잡는다.** 현재 우리가 가진 모든 게이트를 빠져나간다. 양쪽 에이전트가 사후에 자기 파일의 KDoc 심볼 참조를 전수 스윕했고(guide-capture 26개, local-edit 잔재 0건) 지금은 깨끗하다.

### wave 1 팀 축소 = 3+1 (2026-07-25)

**실기기 확보가 당분간 어렵다는 오너 확정**에 따라 §7-1 위험이 현실화됐다 — 4명 전원이 DONE-JVM 천장에서 멈추면 미검증 코드가 4배속으로 쌓인다. 편집자를 3명으로 줄인다.

**뺀 쪽: onboarding-polish-agent.** 기기 부재 때문만이 아니라 **콘텐츠 차단이 겹쳤기 때문**이다 — `cards.json`이 참조하는 카드 이미지 16장이 리포지토리에 없어 §6-2가 그라데이션 플레이스홀더를 벗어날 수 없고, §7-2 폴리싱·§7-3 시연 모드는 육안 판정이다. wave 0에서 `CardRepository`·`PresetProfileMapper` 인프라를 이미 완성해 뒀으므로, **카드 에셋이 도착하면 즉시 재투입**한다(재작업 없음).

남긴 3명의 근거: guide-capture는 §0.4 판정 하네스가 기기 대체 수단이라 최우선, local-edit은 순수 연산층이 이 프로젝트에서 가장 JVM 검증 가능한 축, reference-net은 §5-1이 로그로 완료 판정 가능하고 D8-5 blocker 해소가 여기 걸려 있다.

`spec-test-auditor`는 웨이브 종료 시에만 투입한다(wave 0에서 동시 실행 시 측정이 무의미해지고 before 스냅샷이 오염됨을 실측).

### 담당 B 미결 4건

1. **`ProfileEngine.recommend()` 켈빈 정규화 버그** — 거리 합산이 `colorTemperature`(4600~6200K)와 0~1 차원을 정규화 없이 더해 색온도가 순위를 지배(실측 501.3 중 500.0). 구도 5차원 기여도 ≈ 0. 어댑터에서 수정 불가(`applyFeedback`의 `-350f` 켈빈 델타가 깨짐) → B 모듈 수정 필요. 카나리아 테스트가 `PresetProfileMapperTest`에 있음
2. **카드 이미지 16장** — `cards/card_01.jpg`~`card_16.jpg`, 3:4, JPEG, 긴 변 1024px, 200~400KB. 라이선스 확인 포함
3. **자동 행인 마스크 · FLUX.1 Fill** — §5-3 실제 생성 경로
4. **matchScore 이름 충돌 정리** — `AlignmentEngine`의 IoU와 §4.2 가중 점수가 같은 이름을 씀

---

## 9. 기기 검증 회차 기록

### 9-1. 2026-07-27 · SM-G970N (Android 12, 1080×2280 @480dpi)

기기가 붙은 첫 회차. **JVM 277테스트가 전부 통과한 상태에서 결함 4건이 나왔고, 그중 하나는 앱의 중심 기능이 완전히 죽어 있던 것이다.**

| # | 결함 | 판정 |
|---|---|---|
| 1 | 셔터가 메인 스레드 밖에서 `takePicture()` 호출 → **촬영 3/3 실패** | 수정 |
| 2 | 상단 바 중앙 칩이 시작 구역을 덮어 **HUD 칩 입력 탈취** | 수정 |
| 3 | 스타일 픽커가 pane을 줄이는데 SurfaceView가 안 따라와 **프리뷰 140px 누출** | 수정 |
| 4 | `pointerInput`의 `aspect` 키 재시작이 **비율 전환 후 첫 탭을 삼킴** | 수정 |
| 5 | 기동 후 **첫 프리뷰 제스처 유실**(Press 미수신, Release만 수신) | 미해결·기록 |

### 9-2. 리드 판정: `📱` 표시는 만료된다

`P1_Plan_1.md`의 §1-5 촬영은 `[x]`에 "✅ 실기기: 촬영 원본 4:5·세로 정상, MediaStore 내보내기 확인"이 붙어 있었다. **그 근거는 참이었고, 그 뒤에 들어간 회귀로 기능이 죽었다.** 마지막 정상 촬영 파일 타임스탬프가 7/25 22:24이므로 회귀는 그 이후다.

따라서 **실기기 근거는 그 커밋 시점의 사실이지 현재의 사실이 아니다.** 앞으로:

1. `📱`가 붙은 항목도 **해당 경로의 코드가 바뀌면 재검증 대상**으로 되돌린다. 특히 스레드 컨텍스트·레이아웃 구조 변경은 JVM 테스트가 원리적으로 못 잡는다.
2. 웨이브 종료 시 `spec-test-auditor`의 "테스트 통과"는 **기기 검증을 대체하지 않는다.** 이번 회차의 결함 5건 중 JVM에서 잡을 수 있었던 것은 0건이다.
3. 기기가 붙어 있는 동안에는 **셔터→저장→갤러리 왕복을 회차마다 다시 돌린다.** 30초면 되고, 이번에 그게 죽어 있는 걸 잡았다.

### 9-3. 기기 검증 기법 (다음 회차에 재사용)

- **밝기 밴드 측정이 눈보다 정확하다.** 스크린샷 한 열의 휘도를 훑어 DARK/LIT 구간 경계를 뽑으면 px 단위로 나온다. 프리뷰 누출 결함은 이걸로 "중심 일치 + 차이 = 스트립 높이 140px"까지 특정했다. 눈으로는 "뭔가 잘렸다"까지가 한계였다.
  - ⚠️ **함정**: 레터박스 바가 배경과 같은 차콜이면 밝기로는 안 잡힌다. 4:5에서 "바가 0px"이라고 오판했다가 `pane=1080x1500` 로그를 보고 정정했다(실제 75px). **밴드 측정은 pane 크기 로그와 교차 검증할 것.**
- **CameraX 자체 로그로 수락/거부를 가르지 말 것.** `Camera2CaptureRequestBuilder: createCaptureRequest`는 AE/AWB로도 뜬다 — 레터박스 탭에서 위양성이 나왔다. 앱 코드에 판정 전용 로그(`tapFocus ... -> REJECTED`)를 심는 편이 빠르고 확실하다.
- **사람 없이 확인 가능한 신호를 찾아라.** 스타일 전환은 피사체가 없어도 `matchScore`가 0.33→0.36으로 움직인다 — 선택이 AlignmentEngine까지 닿았다는 증거가 된다.
- **adb로 못 미는 것**: 기울기(수평선 부호), 인물(브래킷·실루엣 색 전환·얼굴 박스), 초점 racking. 이건 손이 필요하다.
