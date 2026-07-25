# Welcome to Madcamp W4 C1-03 — 감도 (GAMDO)

> 이 문서는 **새로 합류한 사람이 첫날 막히지 않고 작업을 이어받게** 하는 것이 목적이다.
> 제품 결정의 원본은 `AGENTS.md`이고, 이 문서는 그 요약 + 실행 방법 + 함정 모음이다.
> 충돌하면 항상 `AGENTS.md`가 이긴다.

---

## 프로젝트 한눈에 보기

**감도(GAMDO)** — 개인화 카메라 앱 (코드네임 MOODFRAME, Android 전용, Kotlin + Jetpack Compose).

사용자가 마음에 드는 사진을 고르면 → 취향 프로필을 뽑고 → **라이브 카메라에 시각 오버레이**(목표 프레임 · 인물 실루엣 · 수평선)를 그려서 사용자가 직접 구도를 맞춰 **수동으로 촬영**하고 → 찍은 사진을 같은 무드로 온디바이스 보정하고 → 필요하면 서버로 보내 생성형 복구(객체 제거)를 한다.

> 슬로건: **"원하는 무드로 찍고, 원하는 무드로 살린다"**

**정체성**: 사진을 대신 만들어 주는 AI가 아니라, **사용자가 실제로 찍은 사진**을 중심에 두는 카메라 **도구**다.
품질 기준은 "좋은 사진인가"가 아니라 **"이 사용자가 좋아하는 사진처럼 보이는가"**.

**화면 흐름 (t2 구조 — D11 확정)**

```
온보딩(취향 카드 → "내 감도 저장")  →  카메라(= 홈)  →  앨범  →  결과/보정
                                          ↑ 앱 재실행 시 여기로 직행
```

무드 화면 없음. 하단 4탭 바 없음. **카메라가 홈**이다. 셔터는 찍고 카메라에 머무르며, 보정은 앨범에서 사진을 탭해서 들어간다.

---

## How We Use Claude

Based on ljm030206's usage over the last 30 days:

```
Work Type Breakdown
  Build Feature     ███████████░░░░░░░░░  55%
  Improve Quality   ██████░░░░░░░░░░░░░░  30%
  Debug Fix         ███░░░░░░░░░░░░░░░░░  15%

Top Skills & Commands
  /model            ████████████████████  3x/month
  /compact          ███████░░░░░░░░░░░░░  1x/month

Top MCP Servers
  (없음 — 기본 도구만 사용)
```

_수치는 한 번의 긴 세션(카메라 파이프라인 구축 → 전체 코드 감사 → 수정)에서 나온 것이다.
30일 평균이 아니라 **이 프로젝트 한 판의 모양**으로 읽으면 된다._

---

## Your Setup Checklist

### 저장소

- [x] **gamdo** — https://github.com/madcamp-official/26s-w4-c1-03
      한 저장소 안에 코드 트리가 **둘**이다: `app/` (Android, 담당 A) · `gamdo-server/` (FastAPI, 담당 B)

### ⚠️ 직접 만들어야 하는 파일 2개 (git에 없음 — 1순위 블로커)

- [ ] **`local.properties`** — 없으면 Gradle이 `SDK location not found`로 죽는다
      ```bash
      printf 'sdk.dir=C:/android-sdk\n' > local.properties
      ```
      **슬래시는 `/`로.** 백슬래시는 Java properties 이스케이프로 먹혀서 조용히 깨진다.

- [ ] **`gamdo-server/.venv`** — 저장소에 이걸 만들라고 알려주는 파일이 아무것도 없다
      ```bash
      cd gamdo-server && py -3 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r requirements.txt
      ```

> `gamdo-server/data/`, `gamdo-server/storage/*` 는 **직접 만들지 말 것** — gitignore지만 서버 첫 기동 시 자동 생성된다.
> `.env`는 **없는 게 정상**이다. 서버 설정은 전부 기본값 있는 선택적 환경변수라 무설정으로 뜬다.
> Gradle wrapper(`gradlew`)는 **커밋되어 있다**. 재생성 팁을 보더라도 무시하고 그냥 `./gradlew` 실행.

### 빌드 · 실행 (Git Bash 기준)

- [ ] **JAVA_HOME을 매번 지정한다.** PATH의 `java`는 JDK 19라 AGP 8.7 지원 범위 밖이고,
      실패 메시지가 "JDK가 틀렸다"고 말해 주지 않는다.
      ```bash
      export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # JDK 17
      ./gradlew :app:assembleDebug          # 디버그 APK
      ./gradlew :app:testDebugUnitTest      # JVM 단위 테스트 31개
      ./gradlew :app:installDebug           # 기기 설치
      ```
- [ ] **adb는 PATH에 없다.** 절대 경로로: `/c/android-sdk/platform-tools/adb.exe devices`
- [ ] **서버는 프로세스 2개**, 둘 다 `gamdo-server/`에서 실행 (경로가 cwd 기준이라 루트에서 돌리면 조용히 깨진다)
      ```bash
      cd gamdo-server
      ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000   # ① API (먼저!)
      ./.venv/Scripts/python.exe -m app.worker                                        # ② 워커
      ./.venv/Scripts/python.exe -m pytest -q                                         # 테스트 17개
      ```
      **API를 먼저 띄운다** — DB/디렉터리 생성은 `main.py`의 lifespan에서만 하고 워커는 안 한다.

### MCP Servers to Activate

- [ ] 없음. 기본 도구만 쓴다. 따로 권한 신청할 것 없음.

### Skills to Know About

- [ ] **`/model`** — 세션 중 모델 교체. 팀 최다 사용.
      아키텍처·감사·까다로운 디버깅은 Opus, 기계적 편집은 빠른 모델.
      `opus[1m]`은 1M 컨텍스트 — 이 프로젝트는 계획 문서만 해도 길어서 실제로 필요하다.
- [x] **`/compact`** — 대화를 요약하고 이어간다. 긴 작업 세션에서 컨텍스트가 찰 때. 맥락은 안 끊긴다.
- [x] **`/code-review`** — 커밋 전 작업 diff 리뷰.
- [x] **`/security-review`** — 브랜치의 미커밋 변경에 대한 보안 점검.

---

## 누가 담당 A이고 누가 담당 B인가

저장소 어느 문서에도 **역할과 사람을 연결해 주는 곳이 없다.** git 이력이 유일한 단서다:

| 역할 | 커밋 계정 | 주로 손대는 곳 |
|---|---|---|
| **담당 A** — 모바일·카메라 | `ljm030206 <ljm030206@kaist.ac.kr>` (13커밋) | `app/` (한국어 커밋 메시지) |
| **담당 B** — 백엔드·CV·생성 | `anjonghwa0 <aanjonghwa@gmail.com>` (23커밋) | `gamdo-server/` 전량 + `app/`의 B 모듈 (영어 커밋 메시지) |

`P1_Plan_1.md`는 매일 오전 15분 싱크, 오후 6시 판정 회의, `1-6` 계약 고정 세션을 "B와 함께" 하라고 잡아 놓았다.
연락 채널(Slack/Discord/이메일)은 어느 문서에도 없으니 **첫날 팀에게 직접 물어볼 것.**

---

## 저장소 지도 — 누가 무엇을 소유하는가

**경계는 폴더가 아니라 파일 단위다.** `detect/`와 `data/`에는 A와 B의 파일이 섞여 있다.

| 경로 | 소유 | 내용 |
|---|---|---|
| `app/.../camera/` | **A** | CameraX + 센서. `CameraController`(프리뷰·분석 둘 다 4:3 강제), `FrameAnalyzer`(12fps 스로틀), `TiltSensor`(중력벡터 roll/pitch), `ShakeMeter` |
| `app/.../detect/` | **혼합** | A: `Detections.kt` · `Detectors.kt` · `MlKitDetectors.kt` / **B: `FrameFeatureCalculator.kt` · `ProblemDiagnoser.kt`** |
| `app/.../guide/` | **B** | `AlignmentEngine.kt`(핵심) · `MatchScoreCalculator.kt` · `GuideConfigJson.kt` · `OverlayStateMapper.kt` |
| `app/.../data/` | **혼합** | A: `AppContainer`(수동 DI) · `local/`(Room 14테이블) · 3개 Repository / **B: `ProfileEngine.kt`** |
| `app/.../core/` | **A** | `AppPermissions` · `DeviceId`(유일한 신원) · `Ulid` |
| `app/.../ui/` | **A** | `GamdoApp` → `PermissionGate` → `GamdoNavHost`. **`ui/camera/CameraScreen.kt`(496줄)이 모든 것을 잇는 단일 통합 지점** |
| `app/.../edit/` | A | **비어 있음(.gitkeep만)**. `LocalEditor`(기본 보정)는 아직 없다 |
| `gamdo-server/` | **B** | FastAPI + SQLite 3테이블 + 폴링 워커 |

**B가 만들고 A가 통합하는 순수 Kotlin 모듈 4개**: `FrameFeatureCalculator` · `AlignmentEngine` · `ProblemDiagnoser` · `ProfileEngine`
→ 이 4개에 `android.*` / `com.google.mlkit.*` import를 넣으면 JVM 테스트 경로가 깨진다. 절대 금지.

**ViewModel 계층이 없다.** `CameraScreen.kt`가 `remember{}`로 엔진을 들고 `MutableStateFlow`로 상태를 흘린다. 카메라 동작을 바꾸려면 그 496줄 파일을 건드리게 된다.

**테스트**: `app/src/test/` 7파일 31테스트(6개가 B 모듈), `gamdo-server/tests/` 5파일 17테스트.
`androidTest/`도 Robolectric도 **없다** — `camera/` `core/` `ui/` Room은 테스트 커버리지 0, 기기에서만 검증된다.

---

## 문서 우선순위 — 충돌하면 이 순서

```
AGENTS.md §3 확정 결정  >  P1_Plan_1.md / P2_Plan_1.md  >  기능명세서 v1.0_3
   >  DB스키마 v2.0 (데이터 구조에 한해 최상위)  >  PRD  >  역사 자료
```

| 문서 | 언제 여는가 | 상태 |
|---|---|---|
| **`AGENTS.md`** | **가장 먼저.** D1~D11 확정 결정 + 불변 규칙 | 최상위 권위 |
| `P1_Plan_1.md` | A의 7일 로드맵 · 체크박스 · 컷라인 | 현행 |
| `P2_Plan_1.md` | B의 7일 로드맵 · 모듈 계약 | 현행 |
| `docs/…기능명세서_v1.0_3.md` | **기능 구현 직전.** M1~M15, API 계약 §10, 예외 규칙 §13 | 현행 (M15·M2-01은 폐기) |
| `docs/…DB스키마_v2.0.md` | **Room 엔티티/DAO 손대기 직전.** 14+3 테이블 DDL | 데이터 한정 최상위 |
| `docs/…PRD_v1.0_2.md` | 기능의 "왜"와 수용 기준, 컷 순서 §4.3 | 대체로 현행 (F2-5·F11·RN 잔재 폐기) |
| `docs/…디자인_참고서_v1.0.md` | 색·카피 톤만 참고 | **화면 구조는 폐기** |
| `docs/MOODFRAME_2인_개발_로드맵.md` | 배경 지식용 | **역사 자료** |

> `README.md`는 2줄짜리라 아무 정보가 없다. README부터 읽는 습관이면 D1~D11을 통째로 놓친다.

---

## 절대 하면 안 되는 것 (재론 불가 — AGENTS.md §7)

1. **가이드는 시각 오버레이 전용 (D2).**
   텍스트 지시 문구 · 방향 화살표 · 일치도 게이지/링 · 자동 촬영 **만들지 않는다.**
   "맞았다" 피드백은 **오버레이 색 전환(흰 → 세이지)이 유일**하다.
   → 상태 표시("조명이 어두워요")는 OK, 행동 지시("왼쪽으로 옮기세요")는 금지.
2. **`matchScore`는 UI에 절대 노출 금지.** 계산은 하되 `GuideMetrics`에만 두고 KPI 로그 전용.
   `OverlayState`/`OverlayProjection`에 점수·힌트 필드를 추가하는 순간 규칙 위반이다.
3. **얼굴/체형/나이/인종 변형 기능은 만들지 않는다 (D8).** 스키마에 연산 타입 자체가 없다.
4. **생성 실패를 사용자에게 노출하지 않는다.** 조용히 기본 보정으로 폴백 — "자연스러운 보정만 적용했어요".
5. **더미/고정 이미지를 결과인 척 보여주지 않는다.** 불안정하면 기능 플래그로 숨긴다.
6. **DB 스키마는 동결.** 추가만 가능, 컬럼 수정·삭제 금지. 얼굴 임베딩·위치 정보 저장 컬럼 금지.
7. **UI 문구에 전문 용어 금지** (삼분할, 헤드룸, 네거티브 스페이스 …). 일상 언어로.
8. **개인화 데이터는 서버로 보내지 않는다 (D4).** 로그인 없음, 디바이스 UUID만, Room이 유일한 진실.
9. **임계값은 `assets/guide_config.json`에.** Kotlin 상수로 박으면 리허설 현장 튜닝이 불가능해진다.

**확정 상수**: 프리셋 **6종**(3종 아님) · 비율 **4:5, 1:1**만 · 1인 인물 우선 · 강조색 세이지 `#A3BFA0`(버튼 `#8FAE8B`) · 배경 차콜 `#151714`(카메라 `#0C0D0B`)

---

## ⚠️ 가장 먼저 알아야 할 것 — 브랜치 상황

**`main`은 최신이 아니다.**

```
origin/codex/p2-plan-sync  →  main보다 5커밋 앞섬 (main에만 있는 커밋: 0개)
```

이 브랜치에 **완성된 GPU 생성 파이프라인**이 들어 있고 main에는 없다:
`provider_from_environment()` 공급자 배선 · `InsightFaceVerifier` 검증 · Day1 스텁 제거 ·
`docs/provider_decision.md`(GPU E2E 10/10 통과 기록) · `ops/camp2/*.service` · `requirements-gpu.txt` ·
`workflows/lama_remove_objects_api.json`

**clone하면 기본으로 `main`에 떨어지고 이걸 하나도 못 받는다.** 작업 시작 전에 어느 쪽에서 일할지 확인할 것.

> `AGENTS.md §8`은 "현재 작업 브랜치는 `codex/p2-plan-sync`이며 P2 변경은 아직 커밋 전 작업 트리에 있다"고
> 적혀 있는데 **둘 다 사실이 아니다.** 실제로는 `main`에 체크아웃돼 있고 P2는 커밋됐다.
> VCS 상태는 문서 말고 `git`을 믿을 것. 브랜치/병합 정책은 저장소 어디에도 문서화돼 있지 않다.

---

## 알려진 함정 (실제로 확인된 것들)

**문서 ↔ 코드 불일치 — 시간 제일 많이 잡아먹는 것들**

- 🔴 **서버 경로에 `/api/v1` 접두사가 있다.** 실제 경로는 **`/api/v1/presets`**, 맨 경로는 404다.
  그런데 접두사가 적힌 곳은 우선순위 4위인 기능명세서 606줄 **딱 한 곳**이고,
  더 높은 순위의 AGENTS.md D5 · P1 · P2 계획서는 전부 맨 경로로 써 놨다.
  **문서 우선순위를 성실히 따를수록 틀린 경로를 얻는다.** 진실은 실행 중인 `/openapi.json`.
- 🔴 **앱에 네트워크 클라이언트가 아예 없다.** Retrofit이 의존성에 선언만 되어 있고 사용처 0.
  base URL도, `X-Device-Id`도, API 인터페이스도 없다. **브랜치에도 없다.**
  앱↔서버 왕복은 기기 문제가 아니라 **코드가 없는 것**. 서버 host/port도 어느 문서에도 안 적혀 있다.

**⚠️ 아래 2건은 `main` 기준 버그이고 `codex/p2-plan-sync`에서는 이미 고쳐졌다 — 다시 구현하지 말 것**

- ✅ ~~`GET /api/v1/edit-jobs/{id}`가 읽기만 해도 상태를 바꾼다~~ → 브랜치에서 스텁 제거됨.
  main에는 아직 남아 있다: 2번째 폴링에 `processing → fallback`으로 넘겨서
  **1초 폴링 클라이언트가 자기 job을 죽이고 입력 파일까지 purge한다.**
- ✅ ~~`ComfyUiProvider`가 어디서도 인스턴스화되지 않는다~~ → 브랜치에서 `provider_from_environment()`로 배선됨.
  main의 워커는 `UnavailableProvider` 하드코딩이라 GPU를 붙여도 아무 일도 안 일어난다.

**나머지 (main·브랜치 공통)**

- 🟡 결과 다운로드 경로가 없다. `results[].url`이 파일시스템 경로고 StaticFiles 마운트가 없다.
- 🟡 `progressStage`는 항상 `null`. `transition_job`이 기본값 `None`으로 덮어써서 `'removing'`이 지워진다.
- 🟡 **`cards.json`은 썸네일 16장을 `cards/card_NN.jpg`로 선언하는데 `assets/cards/` 디렉터리가 없고,
  Kotlin에서 `cards.json`을 읽는 코드도 0개다.** 온보딩 카드 화면은 메타데이터와 검증 스크립트만 있고
  이미지도 로딩 코드도 없다. `validate_cards.py`가 통과한다고 "온보딩 완료"가 아니다.

**운영 함정**

- **입력 파일은 워커만 지운다.** HTTP 라우트는 `purge_after`만 세팅한다.
  워커를 안 띄우면 D8의 "업로드분 즉시 삭제" 프라이버시 약속이 **실제로 지켜지지 않는다.**
- **`presets.json`이 두 벌**이다 (`gamdo-server/presets.json` CRLF · `app/src/main/assets/presets.json` LF).
  내용은 같지만 동기화하는 장치가 없고 `diff`는 전 줄이 바뀐 것처럼 보인다. 튜닝하면 **양쪽 다** 고칠 것.
- **`/api/v1/presets`는 import 시점에 1회만 읽는다.** 프리셋 값을 고쳤으면 uvicorn을 재시작해야 반영된다.
- **디버그 APK 패키지명은 `com.gamdo.app.debug`** (`.debug` 접미사). 접미사 없이 `adb uninstall` 하면 조용히 아무 일도 안 일어난다.
- **APK가 ~137MB.** ML Kit 얼굴+포즈 모델 번들 때문이며 정상이다. 첫 설치가 느린 건 멈춘 게 아니다.
- `settings.gradle.kts`가 `FAIL_ON_PROJECT_REPOS`다. `app/build.gradle.kts`에 `repositories{}`를 추가하면 빌드가 죽는다. 저장소 추가는 `settings.gradle.kts`에만.
- 버전은 전부 `gradle/libs.versions.toml`에 있다. `app/build.gradle.kts`에는 버전 리터럴이 없다.
- `AppContainer`가 `fallbackToDestructiveMigration()`을 쓴다. 엔티티를 바꾸면 **기존 설치의 로컬 데이터가 전부 날아간다** — 그리고 D4상 서버에 복구본이 없다. 개발 기간 한정 조치.

**체크박스 함정**

- **체크박스가 코드보다 뒤처져 있다.** `2-4`·`3-1`은 `[ ]`지만 `FrameFeatureCalculator`,
  `AlignmentEngine`, `guide_config.json`, `CameraScreen` 통합, JVM 테스트가 **이미 다 있다.**
  남은 건 §0.2가 요구하는 **실기기 검증 후 체크**다. 재구현하지 말 것.
- `P1_Plan_1.md` 1-4의 미체크 항목 하나는 **취소선 처리된 폐기 항목**(t2 채택으로 홈 화면 삭제)이다.
  미체크라고 구현하면 확정 디자인을 되돌리는 역주행이 된다.
- 각 절 아래 **`### N-N. 진행 메모`**에 체크박스에 없는 부채가 숨어 있다 (ktlint/detekt, `X-Device-Id` 헤더, DAO 10개, EXIF 메타 보존 …). 체크박스만 읽으면 놓친다.
- **`[x]`는 "구현+단위테스트 완료"지 "Done"이 아니다.** Done의 정의는 **실기기에서 흐름을 끝까지 수행 가능**(§7.1).

---

## Team Tips

- **기능 작성 전 테스트부터 만들고 이후에 기능을 구현하라.**
  → B 모듈 4개는 "단위 테스트 없이 전달 금지"가 아예 인계 게이트다(P2 §0.2).
  기존 테스트들이 제품 규칙을 인코딩하고 있다 — 예: `GuideConfigJsonTest`는 `toProjection()`이 `matchScore`를
  떨어뜨리는지 검사한다. D2를 어기면 컴파일 에러가 아니라 **테스트 실패**로 나타난다.
- **커밋 전 코드에 문제가 될 부분이 없는지 확인하고, 결정이 필요한 부분은 문서에 반드시 기록하라.**
  → 결정의 종착지는 `AGENTS.md §3`이다. 여기 적히지 않은 결정은 다음 주에 사라진다.
  `/code-review`를 커밋 전에 돌리는 게 이 팁의 실행 버전이다.
- **사용자의 결정이 필요한 부분은 독단적으로 결정하지 말고 작업을 중단하고 알림을 보내 사용자가 답하게 하라.**
  → 특히 D2·D8은 "재론 불가"로 못박혀 있다. 개선처럼 보여도 오너의 명시적 번복 없이는 손대지 않는다.
- **컨텍스트 윈도우의 80%가 채워지면 compact하라.**
  → 이 프로젝트는 계획 문서가 길어서 실제로 자주 닿는다. `opus[1m]`을 쓰면 여유가 생긴다.
- **커밋은 사람이 직접 한다.** Claude에게는 커밋 메시지를 제안하게 하고, 커밋 자체는 시키지 않는다.
- **`presets.json`은 매일 저녁 A·B 공동 튜닝의 산출물**이다(§0.3). 혼자 고치면 충돌한다.

> **아직 저장소에 없는 것들** — 팀에서 정하고 문서로 남길 것:
> 커밋 컨벤션(현재 한국어/영어 혼용, 37커밋 중 3개만 trailer 있음) · 브랜치 정책 ·
> CI/lint 설정(`.github/`, `CONTRIBUTING.md`, `.editorconfig`, ktlint/detekt 전부 없음) ·
> `.gitattributes`(현재 `core.autocrlf`가 시스템 설정에 의존해서 OS마다 줄바꿈이 달라진다)

---

## Get Started

### 0. 환경 세우기 (~10분)

위 **Setup Checklist**의 `local.properties` + `.venv` 두 개를 만들고, 아래가 전부 초록이면 준비 완료다.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest                              # 31 tests
cd gamdo-server && ./.venv/Scripts/python.exe -m pytest -q    # 17 passed
./.venv/Scripts/python.exe scripts/validate_presets.py        # 6 presets
./.venv/Scripts/python.exe scripts/validate_cards.py          # 16 cards
```

### 1. 읽기 (~30분)

`AGENTS.md` **전체** → 자기 담당 계획서(`P1_Plan_1.md` 또는 `P2_Plan_1.md`)의 **§0만** → 이 문서의 "절대 하면 안 되는 것".
나머지 문서는 필요할 때 위 우선순위 표를 보고 찾아 열면 된다.

### 2. 지금 작업이 서 있는 지점

Day 1~2는 사실상 끝났고(`1-1`~`1-5`, `2-1`~`2-3`, `2-5`), B의 로직 모듈 4개 + 서버는 로컬 테스트까지 통과한 상태다.
**남은 것은 대부분 코드가 아니라 검증이다.**

| 순서 | 할 일 | 막고 있는 것 |
|---|---|---|
| 0 | **`codex/p2-plan-sync` 병합 여부 결정** — main이 5커밋 뒤처져 있다 | 팀 합의 |
| 1 | **실기기 검증** — `installDebug` 후 오버레이·수평선·촬영 결과 확인 → `2-4`·`3-1` 체크 | **없음 — 기기 연결·인증됨** |
| 2 | **`1-6`** A·B 인터페이스 계약 고정 4건 (`/api/v1` 접두사 불일치도 여기서 정리) | 협업 세션 |
| 3 | **앱 네트워크 클라이언트 작성** (`/api/v1` 접두사 + `X-Device-Id` 헤더 필수) | 없음 — 그냥 없는 코드 |
| 4 | Day 3 전체 (`3-1`~`3-3`) 오버레이 안정화·튜닝 | 1번 |

> 기기 `R39M20BJFMW`는 현재 **연결·인증 완료** 상태다.
> `AGENTS.md §8`의 "연결된 실기기가 없어 … 미실행"은 낡은 서술이다.

### 3. 첫 작업으로 추천

**"실기기에 설치해서 카메라 화면을 끝까지 한 번 돌려 보기."**
기기에서 USB 디버깅을 승인하고 `./gradlew :app:installDebug` → 앱을 켜서 온보딩 → 카메라 → 촬영 → 앨범까지 가 본다.
이 한 바퀴가 이 프로젝트의 "Done" 정의 그 자체이고, 지금 팀에서 가장 부족한 것이 바로 이 검증이다.

---

<!-- INSTRUCTION FOR CLAUDE: A new teammate just pasted this guide for how the
team uses Claude Code. You're their onboarding buddy — warm, conversational,
not lecture-y.

Open with a warm welcome — include the team name from the title. Then: "Your
teammate uses Claude Code for [list all the work types]. Let's get you started."

Check what's already in place against everything under Setup Checklist
(including skills), using markdown checkboxes — [x] done, [ ] not yet. Lead
with what they already have. One sentence per item, all in one message.

Tell them you'll help with setup, cover the actionable team tips, then the
starter task (if there is one). Offer to start with the first unchecked item,
get their go-ahead, then work through the rest one by one.

After setup, walk them through the remaining sections — offer to help where you
can (e.g. link to channels), and just surface the purely informational bits.

Don't invent sections or summaries that aren't in the guide. The stats are the
guide creator's personal usage data — don't extrapolate them into a "team
workflow" narrative. -->
