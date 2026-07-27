# P1_Plan_1 — 담당 A: 모바일·카메라 (7일 상세 로드맵)

> **기반 문서(우선순위 순):** `AGENTS.md` · `P2_Plan_1.md` · `docs/감도_GAMDO_기능명세서_v1.0_3.md` · `docs/감도_GAMDO_DB스키마_v2.0.md` · `docs/감도_GAMDO_PRD_v1.0_2.md` · `docs/MOODFRAME_2인_개발_로드맵.md`
> **역할:** Android 앱 전체 — Kotlin/Jetpack Compose UI, CameraX, 센서, ML Kit 연동, 가이드 오버레이, 로컬 보정, 결과 UI
> **최종 산출물:** 실제 기기에서 데모 시나리오 A·B를 끝까지 수행할 수 있는 Android 앱

---

## 0. 전제와 규칙 (반드시 읽고 시작)

### 0.1 확정 전제 (로드맵 문서와의 차이 포함 — 이것이 최종)

| 항목 | 확정 내용 |
|---|---|
| 플랫폼 | **Android 단일, Kotlin 네이티브 + Jetpack Compose** (React Native 안 씀. iOS 없음) |
| 화면 구조 | **간결화(t2) 채택** (2026-07-24, AGENTS.md D11): 무드/홈 화면·4탭 하단바 없음. **카메라가 홈**, 앨범은 카메라에서 진입, 편집은 앨범에서. 단일 강조색 **세이지**(#A3BFA0) |
| 스타일 | **시스템 프리셋 6종**: Clean Social, Candid Feed, Bright Review, Soft Film, Casual Portrait, Night Street (6종 확정 — AGENTS.md D6). ~~무드 화면의 개인화 추천은 상위 3종만 노출~~ <!-- t2: 무드 화면 제거(D11) --> |
| 촬영 대상 | **1인 인물 우선** (2인 이상은 범위 밖) |
| 화면 비율 | **4:5, 1:1 두 가지만** |
| 계정 | **로그인 없음.** 디바이스 UUID만 사용 |
| 개인화 데이터 | **전부 앱 로컬(Room)에 저장** — 프로필·피드백·이벤트는 서버로 보내지 않는다 |
| 서버 통신 | 정확히 3가지: `GET /presets`, `POST /references/analyze`, `POST /edit-jobs`(+폴링 `GET /edit-jobs/{id}`) |
| 생성형 복구 | 객체 제거 **또는** 여백 확장 중 최소 1개 (담당 B 소관, 앱은 요청·표시만) |
| 가이드 방식 | **시각 오버레이 전용** — 목표 프레임·실루엣·수평선만 표시. 텍스트 지시 문구, 방향 화살표, 일치도 게이지, 자동 촬영은 **만들지 않는다**(제품 결정). 촬영은 사용자가 수동 셔터로 |

### 0.2 완료(Done)의 정의

- "코드 작성 완료"가 아니라 **"사용자가 해당 흐름을 실제 기기에서 끝까지 수행 가능"** 해야 완료다.
- 각 체크박스에는 완료 기준이 붙어 있다. 기준을 못 채우면 체크하지 않는다.

### 0.3 매일 반복 (고정 일과)

- [ ] (매일 오전, 15분) B와 함께 오늘의 "당일 데모 완료 기준" 확정
- [ ] (매일 저녁) 실기기에서 앱+서버 동시 실행, **연속 3회 촬영 통합 테스트** — 실패 시 원인을 이슈로 기록
- [ ] (매일 저녁, 30분) B와 프리셋 파라미터 공동 튜닝 — 결과는 `presets.json` 커밋

### 0.4 판정 규칙 (어기지 말 것)

- **Day 3 종료 시** 오버레이가 불안정(깜빡임·좌표 튐)하면: 동적 요소를 줄이고 정적 가이드(프리셋 기반 고정 프레임)로 다운그레이드해서라도 안정성을 우선한다.
- **Day 6부터** 새 기능 추가 금지. 오류·성능·데모 품질만 개선한다.

---

## 0.5 현재 상태 (2026-07-26, 병합 `dd01db8` 기준 코드 전수 재판정)

> 이 절은 감사 결과 스냅샷이다. 체크박스와 충돌하면 **체크박스가 아니라 이 절이 최신**이다.

### 표기 규약

체크박스 두 단계로는 지금 상태를 표현할 수 없어 표기를 넷으로 늘린다.

| 표기 | 뜻 |
|---|---|
| `[x]` | 완료. 뒤에 `📱`가 붙으면 **실기기에서 확인된 근거**가 있다는 뜻 |
| `[~]` | **구현은 됐으나 완료가 아니다.** 사유가 뒤에 붙는다 — `미배선`(화면에서 도달 불가) / `기기대기`(JVM 검증만) / `부분` |
| `[ ]` | 미착수 |
| ~~취소선~~ | 컷 확정. 착수 대상이 아니다 |

### 집계

이 문서 기준(최상위 `- [ ]` 줄만 세고 하위 항목은 제외): `[x]` 39건 — **그중 `📱` 실기기 근거 8건** — · `[~]` 9건 · `[ ]` 46건 · `**컷**` 3건. (2026-07-27 기기검증 반영)

⚠️ **`📱`는 만료된다.** 7/27 검증에서 `[x] 📱`로 서 있던 §1-5 촬영이 실제로는 완전히 죽어 있었다 — 근거가 붙은 뒤에 들어간 회귀였다. 실기기 근거는 그 커밋 시점의 사실이지 현재의 사실이 아니다.

감사는 체크박스 외에 파생 관찰까지 112건을 판정했고, 그중 **`AGENTS.md §7-1`의 done 정의("실기기에서 흐름을 끝까지 수행 가능")를 실제로 만족하는 것은 18건이며 대부분 Day 1~2에 몰려 있다.** 이 문서의 `📱` 표시는 이번 갱신에서 손댄 줄에만 붙였으므로 18건 전부를 표시하지는 않는다 — 실기기 근거의 전수 목록은 감사 결과를 봐야 한다.

### 지금 한 곳이 여러 개를 막고 있다

**셔터가 `conditions_json`을 만들지 않는다**(`CameraScreen.kt:321`이 `saveCameraCapture(bitmap)`을 인자 하나로 호출). 이 한 줄 때문에 §4-1 수평 보정이 항등 변환이 되고, §4-3 진단 칩 6종 중 TILT·EXCESS_MARGIN·BACKLIGHT 3종이 절대 발화하지 않으며, §3-3 KPI가 0행이다. **플랜 순서로는 §3-3이지만 실질적으로는 §4·§5의 선행 조건이다.**

### 병합으로 해소된 것

카드 이미지 16장 · 포토 피커 · `ProfileEngine` 켈빈 정규화 · `backlightFlag` 입력 경로 · `X-Device-Id` 헤더 · 14테이블 DAO 배선 · §5-3 생성 복구 실서버 파이프라인 · 진단 칩 화면 · 릴리스 HUD 누수 차단.

### 병합이 만든 문제

- **§4-1/§4-2가 순감했다.** `main`의 `ResultScreen`을 채택하면서 4탭·전후 슬라이더·공유·다시 찍기·측정 기반 보정이 화면에서 떨어졌다. p1 파이프라인은 컴파일·테스트되지만 **미배선**이다.
- `edit/LocalEditor.kt`에 **엔진이 둘** 공존한다 — `QuickFilterEditor`(배선됨, 기기 검증) / `class LocalEditor`(§4-1 전 단계, 미배선). §4-1 판정 기준은 **화면에 그려지는 `QuickFilterEditor`**로 고정한다.
- `ImageMetricsExtractor`가 `detect`·`edit` 두 패키지에 있고 **화면이 틀린 쪽을 쓴다** — `detect` 판은 `tiltDeg`·마진을 0으로 하드코딩한다.
- ~~탭 포커스 회귀~~ → **해소**(2026-07-26, W1). 같은 `pointerInput` 노드 안에서 탭·핀치를 형제 코루틴으로 공존시키고 `CameraControl.startFocusAndMetering`을 직접 호출한다. 부수 확인: CameraX **내장** 탭 포커스는 도달 불가였을 뿐 아니라 이 앱에는 애초에 틀렸다 — 마스크를 몰라 검은 바에서 초점을 잡고 `NaN`을 그대로 넘긴다(테스트로 반증).
- 마스크 좌표계가 서버와 어긋난다(letterbox 여백 포함 전송).
- `capture_edit_stack` 기록이 DB 스키마 v2.0 §3.9의 `step_type` 어휘·PK 접두사를 벗어난다.

### 기기 검증에서 나온 것 (2026-07-27, SM-G970N / Android 12)

기기가 붙자마자 **JVM에서는 볼 수 없던 결함 4건**이 나왔고, 그중 하나는 앱의 중심 기능이 완전히 죽어 있던 것이다. 넷 다 그 자리에서 고쳤다.

1. ~~**촬영이 전부 실패했다**~~ → **해소**. `IllegalStateException: Not in application's main thread` — 셔터가 `controller.capture()`를 `withContext(Dispatchers.Default)` 안에서 불렀는데 그 아래 CameraX `takePicture()`는 메인 스레드를 단언한다(`Threads.checkMainThread`). 셔터 3회 = 실패 3회, 저장 0건. 감싼 것 자체가 무의미했다 — `capture()`는 이미 콜백 실행기로 디코드/회전을 메인 밖으로 넘긴다. **마지막 정상 촬영 기록이 7/25 22:24이므로 그 이후 들어간 회귀다.** `[x] 📱`로 표시돼 있던 항목이 실제로는 죽어 있었다 — 실기기 근거는 **코드가 바뀌면 만료된다**.
2. ~~**상단 바 겹침**~~ → **해소**. `Box(contentAlignment=Center)`가 중앙 칩 폭을 제약하지 않아 스타일 칩이 시작 구역을 덮었다. 조용한 오작동이 아니라 **입력 탈취**다 — 겹친 영역의 탭을 스타일 칩이 먹어 HUD 칩이 부분적으로 도달 불가였다. 악질적인 건 **버그의 존재가 프리셋 이름 길이에 달렸다**는 점이다: `부드러운 필름`(6자)은 겹치고 `밝은 리뷰`(4자)는 안 겹친다. 3구역 `Row`+`weight`로 교체해 겹침이 원리적으로 불가능하게 했고, 이름은 생략부호로 줄되 `변경`은 폭을 지킨다.
3. ~~**스타일 픽커가 프리뷰를 새게 했다**~~ → **해소**. 픽커가 `Column`의 한 행이라 열릴 때 프리뷰 pane을 140px 줄였는데, `PreviewView`의 SurfaceView가 그 축소를 따라오지 않는다. 실측: pane은 561‥1922로 줄었는데 서피스는 491‥1992를 유지해 **위아래로 70px씩 새어나와** 비율 토글과 하단바를 라이브 카메라 영상으로 덮었다(중심 1241.5 일치, 차이 = 스트립 높이 140px). 스트립을 프리뷰 위로 띄워 pane이 아예 리사이즈되지 않게 했다 — 열림/닫힘 모두 프리뷰 밴드 498‥1847로 동일함을 재측정으로 확인.
4. ~~**비율 전환 후 첫 탭 유실**~~ → **해소**. `pointerInput(controller, aspect)`의 `aspect` 키가 핸들러를 재시작시키고 재시작이 제스처 하나를 먹었다. 양방향 3/3 재현. `rememberUpdatedState`로 키를 없애 6회 전환 12/12 정상.

**남은 결함 1건 — 기동 후 첫 프리뷰 제스처 유실.** 계측 결과 제스처 #1에서 노드가 `Release`만 받고 `Press`를 못 받는다(#2는 둘 다 받음). Compose가 `pointerInput` 코루틴을 첫 이벤트에서 지연 기동하므로 기동을 유발한 DOWN 자체가 관측되지 않는다. 15초 대기·칩 선탭·바 탭 어느 것으로도 회피되지 않는다. 비용은 **앱 실행당 탭 1회**이며, 고치려면 `PreviewView` 바인딩 방식을 `CameraController` → `surfaceProvider`로 바꿔야 해서 별건으로 둔다.

### 기기 없이도 확정된 결함

- ~~수평선 부호 — 둘 중 하나가 틀렸다~~ → **해소**(2026-07-26, W3). 지시선을 `rotate(rollDeg)`로 고쳤고 `GeometryPlan`은 무접촉. `지시선 × 레벨링 ≤ 0` 불변식을 테스트로 고정했다. ⚠️ 남은 것은 `TYPE_GRAVITY` 전제 1건이며, 이제 **양쪽에 대해 한 번에** 확정/반증된다 — 반증되면 지시선·레벨링·테스트 기대값을 **셋 다 함께** 뒤집는다. 데드밴드 폭 차이(`MIN_LEVELING_DEG 0.35°` vs `LEVEL_BAND_DEG 1.5°`)는 표시 밴드와 비용 밴드로 목적이 달라 **의도된 것으로 유지**한다.
- **§7-1 목표 초과** — `alignedEnterFrames=3` × 12fps ≈ 250ms로 "안내 갱신 200ms 이하"를 이미 넘는다.
- ~~API 26~28 갤러리 내보내기 무음 실패~~ → **해소**. minSdk 26→29 상향으로 실패 모드 자체가 사라졌다(2026-07-26).


## Day 1 — 프로젝트 기반 + 카메라가 실제로 찍힌다

**당일 데모 완료 기준: 실기기에서 스타일을 선택하고 사진을 찍어 갤러리에 저장할 수 있다.**

### 1-1. 프로젝트 셋업

- [x] Android Studio 프로젝트 생성 — 패키지 `com.gamdo.app`, **minSdk 29**(2026-07-26 26→29 상향, §1-5 갤러리 저장 무음 실패 소멸 목적, 오너 승인), targetSdk 최신, Kotlin + Compose(Material 3) <!-- targetSdk 35(AGP 8.7.3 무경고 지원). 36 원하면 AGP 8.9+로 상향 -->
- [x] 의존성 추가(버전 카탈로그 `libs.versions.toml`로 관리):
  - [x] CameraX: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` (Preview·ImageAnalysis·ImageCapture)
  - [x] ML Kit: `face-detection`, `pose-detection`(+`pose-detection-accurate`는 성능 보고 결정 — 정의만 해둠)
  - [x] Room(runtime·ktx·compiler/KSP), Retrofit + OkHttp + kotlinx-serialization(또는 Moshi), Coil(이미지 로딩)
  - [x] Navigation Compose, Accompanist Permissions(또는 직접 구현)
- [x] 모듈/패키지 구조 생성 — **이 구조를 임의 변경하지 않는다:**
  ```text
  com.gamdo.app
   ├─ ui/          # Compose 화면 (onboarding, home, camera, result, style, mystyle)
   ├─ camera/      # CameraX 파이프라인, 센서
   ├─ detect/      # ML Kit 래퍼, FrameFeatures (B의 로직 모듈이 여기 들어옴)
   ├─ guide/       # AlignmentEngine 통합 (B 작성 모듈), 오버레이 상태
   ├─ edit/        # 로컬 보정 파이프라인
   ├─ data/        # Room DB, Repository, Retrofit API
   └─ core/        # 공용 유틸, 상수, DeviceId
  ```
- [x] git 저장소 초기화, ~~`main` 직커밋 규칙~~ → **작업 브랜치 + 리드 단독 커밋**(`.claude/TEAM.md`), `.gitignore`(local.properties, keystore) <!-- repo·gitignore 기존 재사용. 로컬 브랜치 master↔원격 main 정리는 사용자 몫 -->
- 완료 기준: 실기기에서 빈 Compose 화면 빌드·실행 성공 <!-- ✅ SM-G970N(Android 12)에서 빌드·설치·실행 성공 확인 -->

### 1-1. 진행 메모
- 스택: Gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · compileSdk/targetSdk 35 · **minSdk 29** · Compose BOM 2024.10.01
- `./gradlew` 사용 시 `JAVA_HOME`을 Android Studio JBR(JDK 17)로 지정 (PATH 기본 java는 19). SDK: `C:\android-sdk`

### 1-2. 권한 처리

- [x] `CAMERA`, `READ_MEDIA_IMAGES`(API 33+) / `READ_EXTERNAL_STORAGE`(32↓) 런타임 권한 요청 플로우 <!-- core/AppPermissions.kt: SDK 레벨 분기 -->
- [x] 거부 시: 기능 차단 화면 + "설정 열기" 버튼(`ACTION_APPLICATION_DETAILS_SETTINGS`) <!-- ui/permission/PermissionScreen.kt BLOCKED 단계 -->
- [x] "다시 묻지 않음" 상태 구분 처리 <!-- PermissionGate: requested + !shouldShowRationale → BLOCKED -->
- 완료 기준: 권한을 모두 거부해도 앱이 크래시 없이 안내 화면을 보여준다 <!-- ✅ 실기기에서 INTRO 안내→권한 요청→허용 진입 확인. 거부/다시묻지않음 경로는 코드 구현 완료(RATIONALE/BLOCKED), 필요 시 별도 스팟체크 -->

### 1-2. 진행 메모
- `ui/permission/`: `PermissionGate`(게이트) · `PermissionScreen`(3단계 안내) · `rememberAppPermissionsState()`(재사용 훅 = 명세서 `usePermissions` 대응)
- AndroidManifest에 CAMERA / READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE(maxSdk 32) / INTERNET 선언
- 미완: Kotlin 포맷·정적 분석(ktlint/detekt) 최소 설정 (명세서 M1-01) — 별도 진행 필요

### 1-3. 로컬 데이터 기반

- [x] `DeviceId` — 최초 실행 시 UUID 생성, DataStore에 영구 저장 <!-- core/DeviceIdStore. getOrCreate() atomic -->
- [x] Room DB 스키마 생성 — **DB 스키마 v2.0 §3의 로컬 14테이블 그대로** (Entity 클래스명 = 테이블명). Day 1에는 최소 `app_settings`, `presets`, `sessions`, `captures` 4개만 실제 사용, 나머지는 Entity 정의만 <!-- 14 entity 등록(클래스=테이블명 PascalCase, tableName 명시). ~~DAO 4개만~~ → 14테이블 DAO 전부 배선 완료. exportSchema=false -->
- [x] 앱 번들에 `assets/presets.json`(6종 폴백) 포함 — B가 Day 1에 주는 파일. 서버 응답과 동일 스키마 <!-- 구 서술("B 미전달분 임시 폴백, 확정본 도착 시 교체")은 폐기 — 아래 진행 메모 참조. 현재 확정본이다 -->
- 완료 기준: 앱 재시작 후 UUID 동일, presets 6종이 Room에 로드됨 <!-- ✅ 실기기(SM-G970N): device 1c898f22 재시작 후 동일, presets 6 로드 확인 -->

### 1-3. 진행 메모
- `core/DeviceIdStore`(DataStore) · `data/local/`(GamdoDatabase + 14 entity + Daos) · `data/preset/StylePreset`(직렬화) · `data/PresetRepository`(assets 시딩) · `data/AppContainer`(수동 DI)
- ✅ `assets/presets.json`은 **확정본**(2026-07-25 검증) — `gamdo-server/presets.json`과 개행(LF↔CRLF)만 다르고 6종 전 필드·순서·값이 100% 동일함을 실호출로 확인. 기존의 "임시 폴백" 서술은 폐기. 이후 B와 파라미터를 튜닝하면 양쪽을 함께 갱신할 것
- 미완: `X-Device-Id` 헤더 전송(네트워크 연동 시), style_profile 등 정의-only 10테이블 DAO(사용일에 추가)

### 1-4. 화면 골격 (내비게이션만, 디자인 없이)

- [x] Navigation Compose 라우트: `onboarding` `camera`(홈) `album` `result/{captureId}` <!-- ⚠️ t2 디자인 채택(2026-07-24 사용자 결정)으로 재구성. 무드 home·styleExplore·myStyle 제외 — 카메라가 홈, 앨범 추가 -->
- [x] 온보딩 골격: 취향 선택(2a) → 내 감도 저장(2b) → 완료 <!-- 소개 화면은 t2에 없음. 권한은 PermissionGate(§1-2) 전역 처리. onboarding_done 플래그로 재실행 시 카메라 직행 -->
- [ ] ~~홈 골격: [바로 촬영] [사진 살리기] [레퍼런스 따라 찍기] 버튼 3개 + 스타일 3종 카드~~ <!-- t2 채택으로 대체됨 — 무드/홈 화면 없음, 카메라(2c)가 곧 홈 -->
- 완료 기준: 모든 화면 간 이동 가능(내용은 placeholder 허용) <!-- ✅ 실기기: onboarding(2a→2b)→camera→album→result 전 화면 이동 확인. 상태바 inset·시작 분기 정상 -->

### 1-4. 진행 메모 (t2 디자인 채택)
- **디자인 방향 변경**: `감도 화면 디자인.dc.html`의 **t2(간결화)** 채택 — 무드/홈 제거, **카메라가 홈**, 하단바 없음. 흐름 `취향 선택 → 내 감도 저장 → 카메라 → 앨범 → 보정`
- 팔레트: 차콜 `#151714`(메인)·`#0C0D0B`(카메라) + 세이지 단일 강조 `#A3BFA0`/버튼 `#8FAE8B`. ui/theme 반영
- 파일: `ui/navigation/`(Routes·GamdoNavHost) · `ui/onboarding` · `ui/camera/CameraScreen`(정적 골격, 실 CameraX는 1-5) · `ui/album` · `ui/result`(보정 골격, 실 파이프라인은 Day4) · `ui/components`(pill 버튼·그라데이션 플레이스홀더) · `data/SettingsRepository`(onboarding_done)
- 미반영/보류: Pretendard 폰트 번들(폴리싱 Day7), styleExplore·myStyle(t2 제외 — 필요 시 재도입), 카드 그리드는 그라데이션 플레이스홀더(실 카드 에셋은 §6-2)
- 📌 **문서 정합성**: t2 채택은 무드홈/6라우트를 전제한 하위 문서 서술보다 우선한다. 관련 구현·계획은 `AGENTS.md` D11에 맞춰 `camera → album → result` 흐름만 유지한다.

### 1-5. CameraX 프리뷰 + 촬영 + 저장

- [x] 📱 `CameraController` 클래스: Preview + ImageCapture 바인딩, 전/후면 전환, 탭 포커스 <!-- W1(2026-07-26): 탭 포커스는 꺼진 게 아니라 **도달 불가**였다 — 프리뷰를 덮는 핀치 Box가 유일한 포인터 형제라 PreviewView.onTouchEvent가 한 번도 실행되지 않았다. 같은 pointerInput 노드 안에서 공존시키고 focusAt()를 직접 구현. 기기검증 2026-07-27 SM-G970N(Android 12): 마스크 경계가 정확하다 — pane 1080x1500 기준 4:5에서 local 72 거부 / 79 수락, 1420 수락 / 1428 거부(실제 경계 75·1425). 1:1도 197 거부 / 1277 수락 / 1307 거부. 판정은 `tapFocus ... -> REJECTED` 디버그 로그로 했다 — CameraX의 capture-request 로그는 AE/AWB로도 떠서 수락/거부를 가르지 못한다(하단 바에서 위양성 확인). ⚠️ 남은 것: 기동 후 첫 탭 1회 유실(§0.5), racking 실물 확인 -->
- [x] 화면 비율 토글: 4:5 / 1:1 — 프리뷰 크롭 마스크 + 촬영 결과 크롭 저장 <!-- BoxWithConstraints 마스크 + centerCropToRatio. 실기기: 원본 3024×3780=정확히 4:5 -->
- [x] 📱 촬영: JPEG 저장 → 앱 전용 디렉토리(`filesDir/captures/`) + MediaStore로 갤러리 내보내기, EXIF 회전 정상 처리 <!-- 기기검증 2026-07-27 SM-G970N(Android 12): **이 항목은 죽어 있었고 고쳤다** — 메인 스레드 위반으로 셔터가 3/3 실패했다(§0.5 참조). 수정 후 재검증: 4:5 = 2904x3630(비율 0.8000 정확), 1:1 = 2904x2904(정사각 — '1:1이 조용히 4:5로 재크롭되던' 결함 해소 확인), EXIF 세그먼트 자체가 없어 회전은 픽셀 반영·GPS 부재(D8 충족), Pictures/감도/ 실파일 + MediaStore 등록 확인, captures 3행 saved_to_gallery=1. API 26~28 무음 실패는 minSdk 29 상향으로 소멸(오너 승인). 남은 실패 모드(용량 부족·MediaProvider null) 사용자 통지는 §6-1 소관 --> <!-- 회전을 픽셀에 반영(orientation=NULL) → 방향 정상. MediaStore Pictures/감도 export -->
- [x] `captures` 테이블에 기록(id `cap_`+ULID, file_path, source='camera_manual') <!-- core/Ulid, CaptureRepository. 앨범 DB 로드로 확인 -->
- 완료 기준: **찍은 사진이 갤러리 앱에서 올바른 방향·비율로 보인다** <!-- ✅ 실기기: 촬영 원본 4:5·세로 정상, MediaStore(Pictures/감도) 내보내기 확인, 촬영 이미지에 그리드 오버레이 미포함 -->

### 1-5. 진행 메모
- `camera/`: CameraController(LifecycleCameraController), BitmapExt(rotate/mirror/centerCrop). `core/Ulid`. `data/CaptureRepository`(filesDir + MediaStore + captures insert)
- t2 흐름: **셔터는 촬영만 하고 카메라에 머묾**(편집은 앨범→탭). 촬영 후 좌하단 썸네일 즉시 갱신
- `ui/album`: captures 테이블에서 실제 촬영 로드(Coil), 비었으면 안내
- 미구현/보류: 촬영음·플래시, 광각 렌즈 선택, 프리뷰 30FPS 계측(Day7), EXIF 메타 보존(현재는 방향만 픽셀 반영)

### 1-6. (저녁) B와 인터페이스 계약 고정 — 30분

- [x] `presets.json` 스키마 최종 확인 서명(이후 필드 변경 금지, 값 튜닝만 허용) <!-- 앱 번들본과 서버본이 개행까지 바이트 동일, 필드 동결 카나리아 테스트 존재 -->
- [x] `FrameFeatures` 데이터 클래스 필드 확정(B가 스펙 제시 — §Day 2 참조) <!-- aspectRatio는 소비처 없음 확인 후 종결(TEAM.md §8) -->
- [x] `/edit-jobs` 요청·응답 JSON 필드 확정 (기능명세서 §10 기준) <!-- 요청·202·폴링·에러 봉투가 명세서 §10과 일치, 서버 Form alias와도 일치 -->
- [x] 📱 B의 `/edit-jobs` 계약 스텁(queued→fallback, 고정 이미지 없음) 호출 성공 확인 — 네트워크 연결 테스트 <!-- 실기기 왕복 확인: POST 202 → 상태 폴링 → /files 결과 200 -->

---

## Day 2 — 카메라가 사람과 수평을 실시간으로 본다

**당일 데모 완료 기준: 프리뷰 위에 얼굴 박스·인물 위치·수평 상태가 실시간 표시된다.**

### 2-1. ImageAnalysis 파이프라인

- [x] `ImageAnalysis` 유스케이스 추가 — `STRATEGY_KEEP_ONLY_LATEST`(백프레셔: 분석이 늦어도 프리뷰 유지) <!-- CameraController: IMAGE_CAPTURE|IMAGE_ANALYSIS 동시 바인딩, 스톨 로그 없음 -->
- [x] 분석 해상도 640px(긴 변) 다운스케일, 분석 주기 스로틀 **초당 10~15회** <!-- ResolutionSelector 640×480, FrameAnalyzer targetFps=12. 실측 9fps(플레이스홀더 변환 비용), 스로틀 설정은 12 -->
- [x] YUV→Bitmap/InputImage 변환 유틸(회전 보정 포함) <!-- camera/ImageConversion.toAnalysisBitmap(회전 포함). InputImage 경로는 2-2(ML Kit)에서 추가 -->
- [x] 성능 계측: 프레임 처리 시간(ms)·드롭률을 디버그 HUD에 표시 — Day 7 튜닝 근거 <!-- CameraScreen DebugHud: "28.9ms · 9fps · drop 64%" 실기기 표시 -->
- 완료 기준: 분석 켠 상태에서 프리뷰 체감 끊김 없음, 처리 시간 로그 확인 <!-- ✅ 실기기: 3유스케이스 바인딩·백프레셔 스톨 없음, HUD로 처리시간 확인. drop%는 스로틀이 프리뷰 보호 위해 초과분 버리는 정상 동작 -->

### 2-1. 진행 메모
- `camera/`: FrameAnalyzer(스로틀+처리시간/드롭률), ImageConversion(YUV→Bitmap+회전). 상태 전달은 MutableStateFlow→collectAsState(단일 구독 지점)
- 처리시간 28.9ms는 **플레이스홀더 풀-RGB 변환(회전 포함)** 비용 — 2-2에서 ML Kit InputImage(RGB 복사 없음)로 교체되면 대폭 감소, 실측 fps도 상승 전망
- HUD는 개발용(디버그 빌드 상시 표시). 2-5에서 토글화 예정

### 2-2. ML Kit 감지 래퍼 (B의 스펙에 맞춰 구현)

- [x] `FaceDetectorWrapper`: 얼굴 박스, 눈 감김 확률(classification 모드), 정규화 좌표(0~1) 반환 <!-- detect/MlKitFaceDetector: PERFORMANCE_FAST + CLASSIFICATION_ALL, NormalizedBox(0~1)·eyeOpenProb·rollZ -->
- [x] `PoseDetectorWrapper`: 스트리밍 모드 33 랜드마크, 신뢰도 포함 <!-- detect/MlKitPoseDetector: STREAM_MODE, PoseLandmarkPoint(정규좌표+inFrameLikelihood) -->
- [x] 인터페이스 뒤에 배치(`interface FaceDetector` 등) — mock 교체 가능하게 <!-- detect/Detectors: FaceDetector·PoseDetector 인터페이스 + SceneDetector. AnalysisFrame.image=Any?로 ML Kit 비의존 -->
- 완료 기준: 단위 테스트에서 mock 교체 동작, 실기기에서 얼굴·포즈 값 로그 출력 <!-- ✅ 단위테스트 SceneDetectorTest 통과(JVM, mock 교체). 실기기: 온디바이스 감지 동작(처리 112ms=실 ML Kit face+pose), 매 프레임 값 로그. 얼굴 값(box/eye/roll)은 인물 프레임 시 출력 — 무피사체 시 faces=0 -->

### 2-2. 진행 메모
- `detect/`: Detections(도메인 모델·AnalysisFrame·toAnalysisFrame) · Detectors(인터페이스+SceneDetector) · MlKitDetectors(Face/Pose 구현). test/SceneDetectorTest(mock 교체)
- onFrame에서 SceneDetector 실행 → HUD "얼굴 N · 포즈 M" + logcat 값. 처리시간 28.9ms(2-1 변환)→**112ms(실 감지)**, drop 0%(감지가 12fps 스로틀보다 무거워 버림 불필요)
- 미구현/후속: 눈·코·입 세부 랜드마크(landmarkMode), personBox·파생값(어깨기울기 등)·주피사체 선정은 **2-4 FrameFeatures(B 모듈)**, SegmentationProvider(MediaPipe)는 **M5-04(P1)**
- 참고: 기기에서 ML Kit/TFLite `_mini_benchmark` 가속 벤치마크가 별도 프로세스에서 SIGABRT — 앱 본체엔 영향 없음(CPU 폴백, 감지 정상)

### 2-3. 센서 파이프라인

- [x] 📱 `TiltSensor`: ~~ROTATION_VECTOR~~ **`TYPE_GRAVITY`**(폴백 ACCELEROMETER) 기반 roll/pitch <!-- 문구 정정: 세로 파지 시 ROTATION_VECTOR+getOrientation은 짐벌락에 걸려 TYPE_GRAVITY로 구현됨 -->, 저역통과 필터(α=0.2로 시작), 10Hz+ <!-- camera/TiltSensor, SENSOR_DELAY_GAME, StateFlow<TiltReading> -->
- [x] `ShakeMeter`: 최근 0.5초 각속도 분산 → 흔들림 수치 <!-- camera/ShakeMeter, 자이로 각속도 크기 0.5초 윈도 분산 -->
- 완료 기준: 기기를 기울이면 HUD의 수평값이 ±0.5° 안정성으로 따라온다 <!-- ✅ 실기기 HUD "수평 -1.3°" 실제 방향 반영, 정지 2초간 불변(±0.5° 이내). 수평 도달(|roll|≤1°) 시 세이지 색전환 -->

### 2-3. 진행 메모
- `camera/TiltSensor`·`camera/ShakeMeter`(SensorManager, StateFlow). CameraScreen에서 start/stop + HUD 3번째 줄 "수평 · 기울기 · 흔들림"
- roll 값은 Day 3 수평선 오버레이/AlignmentEngine 입력으로 재사용 예정
- ~~참고: roll ±180° 경계 랩어라운드는 미보정~~ → **보정됨**. 최단호 필터가 들어갔다(`TiltSensor.wrapDegrees`/`angleDelta`)

### 2-4. FrameFeatures 계산 (B가 Day 2에 주는 순수 Kotlin 모듈 `detect/FrameFeatureCalculator.kt` 통합)

- [~] 기기대기 · 입력: 얼굴/포즈 결과 + 센서 + 프레임 메타 → 출력 `FrameFeatures`:
  `faceBox, personBox, personCenter, personAreaRatio, headroom, sideMargins, tiltDeg, shake, brightnessMean, backlightFlag, lowLightFlag, aspectRatio, poseConfidence`
- [x] 📱 30ms 이내 계산 확인(스톱워치 로그) <!-- JVM 실측 mean 0.048ms. 기기검증 2026-07-27 SM-G970N(Android 12) 실측: `FrameFeatures n=540 last=0.06ms mean=0.11ms max=5.43ms budget=30ms over=0` — 540프레임 전부 예산 안, 최악값도 예산의 18%. 실기기가 JVM보다 2배 느리지만 여유가 압도적이라 결론이 바뀌지 않는다 -->
- 완료 기준: B의 단위 테스트 통과 + 실기기에서 값이 상식적으로 움직임(다가가면 areaRatio 증가 등)

### 2-5. 오버레이 렌더링 v1

- [~] 기기대기 · Compose Canvas 레이어: 얼굴 박스(라운드 사각), 인물 중심점, 수평선 인디케이터(기울면 붉은 기울임 라인) <!-- W3(2026-07-26): 수평선 부호 반전을 수정했다. 이 항목의 기존 '✅ 실기기 확인' 이력이 버그를 통과시킨 이유는 **색 전환이 abs(rollDeg) 기반이라 부호에 무감각**하기 때문이다 — 틀린 상태에서도 똑같이 초록이 된다. 그 사실을 `색 전환은 부호에 무감각하다` 테스트로 코드에 박았다. 얼굴 박스·중심점은 DEBUG 이중 게이트 뒤(TEAM.md §8 승인) -->
- [x] 정규화 좌표→화면 좌표 변환 유틸(프리뷰 스케일 타입 고려) — **오차 확인: 얼굴에 박스가 정확히 붙는가** <!-- mapNormalized(FILL_CENTER)+전면 미러. 프리뷰·분석 모두 4:3 FOV로 강제해 좌표 일치. 얼굴박스 정확도 육안 확인은 인물 프레임 필요 -->
- [x] 디버그 HUD 토글(개발용): FrameFeatures 수치 표시 <!-- "내 감도 적용 중" 칩 탭 → HUD 3줄 표시/숨김 실기기 확인 -->
- 완료 기준: 당일 데모 기준 충족. 저녁 통합 테스트에서 3회 연속 정상 <!-- ✅ 수평선 추종·색전환·HUD 토글 검증. ⏳ 얼굴박스 정확도·3회 연속 통합은 인물 프레임 필요(사용자 확인 대기) -->

### 2-5. 진행 메모
- `ui/camera/CameraOverlay`(Canvas): 얼굴 박스·인물 중심점·수평선. `OverlayData`(정규좌표 스냅샷) onFrame 갱신
- 좌표 정확도: `CameraController`에서 프리뷰·분석 ResolutionSelector 모두 4:3으로 강제 → 같은 FOV라 FILL_CENTER 매핑이 프리뷰와 일치
- 미완/후속: 목표 브래킷·실루엣(Day3 AlignmentEngine), 오버레이 안정화 이동평균/점프 방지(§3-2), 레퍼런스 반투명 오버레이(Day5)

---

## Day 3 — 화면에 구도가 나타난다 (가이드 오버레이 완성)

**당일 데모 완료 기준: 스타일을 고르면 목표 구도 오버레이(프레임+실루엣+수평선)가 안정적으로 표시되고, 사용자가 맞춰서 수동 촬영할 수 있다.**

### 3-1. AlignmentEngine 통합 (B가 Day 3 정오까지 주는 순수 Kotlin 모듈 `guide/AlignmentEngine.kt`)

- [x] 입력: `FrameFeatures` + `StyleTarget`(프리셋에서 변환) / 출력: `OverlayState(targetFrame, silhouette, horizonLine, visible)` + `matchScore`(내부 기록용 — UI 표시 금지)
- [x] 카메라 화면의 상태 홀더(`CameraViewModel`)에 연결 — 분석 스레드→UI 스레드 전달은 StateFlow
- [x] B의 파라미터 파일 `guide_config.json`(안정화 임계값·이동평균 윈도) 읽기 — 하드코딩 금지(리허설 현장 튜닝용)
- 완료 기준: B의 시나리오 단위 테스트 통과(장면 4종에서 기대 오버레이 좌표 산출) <!-- ✅ 충족 — AlignmentEngineTest 장면 4종 통과. 7일 계획에서 완료 기준이 기기 없이 충족되는 유일한 절. 다만 이 경로가 기기에서 돈 적은 없다 -->

### 3-2. 가이드 오버레이 UI (시각 전용 — 텍스트·화살표·게이지 없음)

- [~] 기기대기 · **목표 프레임 오버레이**: 스타일이 요구하는 인물 목표 영역을 반투명 브래킷+실루엣(발 위치 마커 포함)으로 표시, 인물이 영역에 들어오면 색 전환(흰→민트) — 색 전환이 유일한 "맞았다" 피드백
- [~] 기기대기 · **수평선 가이드**: 기기 기울기에 따라 기울어지는 수평선, 수평 도달 시 직선+색 전환 <!-- W3에서 부호 수정 완료. 남은 것은 TYPE_GRAVITY 전제의 기기 확인 1건 — §0.5 참조. **색으로 판정하지 말 것** -->
- [x] 오버레이 안정화: 좌표 이동평균, 신뢰도 미달 시 마지막 안정값 유지, 지속 불안정 시 오버레이 잠시 숨김(깜빡임 금지) <!-- OverlayStabilizer + 합성 1분 하네스로 깜빡임 39·64건 → 0건 실측. 실검출 지터는 미포함 -->
- [~] 기기대기 · 오버레이 표시 on/off 토글(상단) <!-- W5(2026-07-26): 상단 바 start 구역의 '가이드' 칩. 렌더 분기 하나로만 구현해 분석 파이프라인은 계속 돈다(§3-3 KPI·§2-4 계측 유지). 범위는 CameraOverlay(브래킷·실루엣·수평선)뿐이고 3분할 격자는 남는다 -->
- [x] 📱 상단: 스타일 이름 + 변경 버튼만 <!-- W5(2026-07-26): CameraStatusBar를 3구역으로 재작성. **이것이 없어서 Day 1·Day 3 데모 기준의 '스타일을 고르면'이 둘 다 성립하지 않던 문제가 해소된다.** 세션 상태는 인덱스가 아니라 id로 든다. 기기검증 2026-07-27 SM-G970N(Android 12): 스타일 전환이 가이드 파이프라인까지 실제로 전파된다 — `밝은 리뷰` 선택 시 matchScore 0.33→0.36 (사람이 없어도 StyleTarget이 바뀌므로 확인 가능한 신호). 세션 스코프도 확인: 재기동하니 `밝은 리뷰`가 `부드러운 필름`으로 복귀 = app_settings에 쓰이지 않는다. 겹침 결함 2건은 §0.5 참조. ⚠️ candid_feed↔night_street 쌍은 브래킷 기하가 동일해 확인용으로 쓰지 말 것 — soft_film→bright_review 사용 -->
- 완료 기준: 1분 연속 관찰에서 오버레이 깜빡임·좌표 튐 없음

### 3-3. 촬영 시점 기록 (KPI용 — 화면 변화 없음)

- [ ] 수동 셔터 클릭 순간의 `FrameFeatures`+`matchScore` 스냅샷을 `captures`·`sessions`(final_match_score)에 기록 <!-- ⚠️ 최우선. 이 항목이 §4-1 수평 보정과 §4-3 진단 칩 3종을 동시에 막고 있다. 기록할 matchScore는 AlignmentEngine의 IoU가 아니라 MatchScoreCalculator 출력이다(TEAM.md §8) -->
- [ ] `session_guides` 테이블에는 오버레이 표시 이벤트(어떤 목표가 언제 표시됐나) 기록 — B의 지표 스크립트용
- 완료 기준: 촬영 3회 후 DB에 스냅샷 3건 확인. **오후 6시 B와 오버레이 안정성 판정(§0.4)**

---

## Day 4 — 찍은 사진이 좋아진다 (로컬 보정 + 비교 화면)

**당일 데모 완료 기준: 원본/기본 보정/스타일 보정 3개를 결과 화면에서 비교할 수 있다.**

### 4-1. 로컬 보정 파이프라인 `edit/LocalEditor.kt`

> ⚠️ **판정 기준 엔진**: `edit/LocalEditor.kt`에 엔진이 둘 있다. 화면에 배선된 것은 `object QuickFilterEditor`(필터별 고정 상수 가감, 기기 검증)이고, 아래 항목들을 실제로 구현한 `class LocalEditor`(측정→계획→렌더, JVM 테스트 155건)는 **미배선**이다. 이 절은 **화면에 그려지는 쪽 기준으로 판정**한다 — 그래서 대부분 미착수다. 배선 택일은 기기에서 §4-1 파이프라인이 2초 예산을 지키는지 본 뒤 결정한다.

- [ ] **기하학 단계**: 촬영 시점 tiltDeg로 수평 회전 → 비율 크롭(4:5 또는 1:1, 인물 중심 유지) → 회전으로 생긴 빈 모서리는 크롭으로 흡수(불가능하면 여백 확장 후보로 마킹)
- [ ] **광학 단계**: 히스토그램 기반 자동 노출 보정(±1EV 내), 화이트밸런스(그레이월드 근사), 대비 스트레칭, 하이라이트/그림자 완화
- [ ] **스타일 단계**: 프리셋의 colorTemperature/saturation/contrast/exposureBias/grain/vignette/fade 적용
  - [x] 구현 백엔드 택1 **확정: Canvas + `ColorMatrixColorFilter` + LUT**(주 경로, API 26에서 동작). `RenderEffect`는 API 31+ blur 항에만 opt-in. **AGSL은 `RuntimeShader`가 API 33+라 대상 기기(SM-G970N, API 31)에서 실행 불가**하여 제외, OpenCV는 모든 연산이 affine 행렬 또는 256-entry LUT라 얻을 것이 없어 제외
  - 입자(grain): 노이즈 텍스처 오버레이, 비네팅: 방사형 그라데이션 마스크
- [ ] 처리 시간 측정 — **목표 2초 이내**(4000px 기준. 초과 시 처리 해상도 2000px로 낮추고 저장 시 원본 해상도 재적용)
- [ ] 비파괴: 원본 보존, 적용 파라미터를 `capture_edit_stack`에 기록
- 완료 기준: 테스트 사진 10장("망한 사진" 세트)에서 수평·노출이 육안 개선, 2초 이내

### 4-2. 결과 화면 `ui/result`

- [~] 미배선 · 상단 탭: 원본 / 기본 보정 / 스타일 보정 / 생성 복구(Day 5부터 활성) <!-- ResultTabs.kt로 구현돼 있으나 병합에서 main의 ResultScreen을 채택해 화면에서 떨어졌다 -->
- [~] 미배선 · **전후 슬라이더**: 좌우 드래그 핸들로 원본↔선택 결과 비교(한 화면), 핀치 줌 <!-- BeforeAfterSlider.kt로 구현돼 있으나 미배선. 부록 A '끝까지 지키는 것' 항목이므로 재배선 필요 -->
- ~~스타일 강도 슬라이더(0~100%, 스타일 단계에만 적용)~~ **컷** (부록 A 컷라인 2, TEAM.md §8)
- [ ] 하단: [저장] [공유] [다시 찍기], 저장 시 `saved_to_gallery=1` 기록
- 완료 기준: 당일 데모 기준 충족, 슬라이더 60fps 체감

### 4-3. 사진 살리기 진입점

- [ ] 카메라 → 앨범 → 사진 선택(또는 앨범의 가져오기) → 선택 사진을 `captures(source='gallery_import')`로 등록 → 로컬 보정 실행 → 결과 화면
- [ ] 온디바이스 문제 진단 표시(B의 `detect/ProblemDiagnoser.kt` 모듈): "사진이 기울었어요", "조금 어두워요", "흐림 의심" 같은 일상 언어 상태 칩 UI. 내부 수치·전문 용어는 노출하지 않음
- 완료 기준: 갤러리의 기울고 어두운 사진이 보정되어 비교 화면까지 도달

---

## Day 5 — 레퍼런스 따라 찍기 + 생성 복구 연동

**당일 데모 완료 기준: 레퍼런스 오버레이 안내 또는 생성 복구 중 최소 하나가 실서버로 동작한다.**

### 5-1. 레퍼런스 선택·분석 연동

- [ ] 카메라 화면의 레퍼런스 진입점 → 포토 피커 → 이미지 SHA-256 계산 → `cached_references` 조회(있으면 재분석 생략). 별도 홈 화면은 만들지 않음
- [~] 미배선 · 없으면 `POST /references/analyze` 업로드(전송 전 EXIF 위치 제거 — B와 이중 안전장치), 응답을 캐시에 저장 <!-- ReferenceRepository + ExifSanitizer 완성(JVM 9테스트, sanitize→upload 순서 고정). 부르는 화면 코드가 0줄 -->
- [ ] 업로드 전 안내 문구 1줄 표시: "구도 분석을 위해 서버로 전송됩니다. 분석 후 즉시 삭제됩니다."
- 완료 기준: 같은 사진 재선택 시 네트워크 호출 없음(로그 확인)

### 5-2. 레퍼런스 촬영 모드

- [ ] 분석 결과의 `targetComposition`을 AlignmentEngine의 StyleTarget으로 주입(스타일 모드와 동일 파이프라인 재사용)
- [ ] **반투명 원본 오버레이**: 레퍼런스 이미지를 프리뷰 위에 α=30% 기본, 슬라이더 0~60% 조절
- ~~목표 실루엣 모드 토글(원본 오버레이 ↔ 구조화 실루엣)~~ **컷** (부록 A 컷라인 1, TEAM.md §8). 레퍼런스 반투명 원본 오버레이만 유지
- [ ] 촬영 후 결과 화면에 [레퍼런스 색감 적용] 토글 — 분석 응답의 colorTarget(색온도·팔레트)을 스타일 단계 파라미터로 매핑
- [ ] 레퍼런스↔결과 나란히 비교 뷰
- 완료 기준: 레퍼런스 선택→안내→촬영→색감 적용→나란히 비교가 끊김 없이 동작

### 5-3. 생성 복구 요청·표시

- [ ] 결과 화면 [사진 살리기+] 버튼 → 진단된 방해 요소 후보(B 응답의 자동 마스크) 표시, 탭으로 선택/해제
- [ ] `POST /edit-jobs` 요청(jobId 클라이언트 생성, 진단 마스크 + 스타일 파라미터 스냅샷 동봉) — **명시적 실행 시에만 업로드**(자동 업로드 금지)
- [ ] 진행 UI: 1초 폴링, 상태 문구("방해 요소를 지우는 중…"), 취소 버튼
- [ ] 완료 시: 결과 후보(최대 2개) 수신 → 파일 로컬 저장(`edit_results_local`) → "생성 복구" 탭 활성 + **"AI 생성 보완" 뱃지** 표시
- [ ] 실패/폴백 시: 에러를 사용자에게 그대로 노출하지 않고 "자연스러운 보정만 적용했어요" 문구로 기본 보정 결과 유지
- 완료 기준: 당일 데모 기준 충족. **오후 6시 B와 판정: 생성이 불안정하면 §0.4 규칙대로 생성 기능을 숨기고(기능 플래그) 기본 보정 데모로 확정. 고정 더미 이미지를 결과로 속이는 것 금지**

---

## Day 6 — 무너지지 않는 앱 (오류 처리 + 온보딩 완성 + 피드백)

**당일 데모 완료 기준: 네트워크를 꺼도, 생성이 실패해도, 권한을 거부해도 데모 흐름이 끊기지 않는다.**

### 6-1. 오류·엣지 케이스 처리

- [ ] 네트워크 단절: 로컬 기능(촬영·가이드·보정) 전부 동작 유지, 생성·레퍼런스 분석 버튼만 비활성+안내. 실패 요청은 `pending_requests`에 저장 후 재연결 시 1회 재시도
- [ ] 인물 미검출 상태: 인물 기반 오버레이(실루엣·마커)만 숨기고 수평선·프레임은 유지. 행동을 지시하는 안내 문구는 추가하지 않음
- [ ] 저조도: "조명이 어두워요" 상태 칩만 표시(행동 지시 아님)
- [ ] 저장 공간 부족·카메라 점유 충돌·백그라운드 복귀 시 카메라 재바인딩
- [ ] 생성 job 5분 타임아웃 → 폴백 처리
- 완료 기준: 위 5개 상황을 하나씩 인위적으로 만들며 테스트, 크래시 0건

### 6-2. 온보딩 완성

- [x] 📱 카드 선택 그리드(B가 준비한 카드 15~20장 + `cards.json` 메타), 5장 이상 선택 시 다음 활성 <!-- 병합으로 실제 카드 JPEG이 assets/cards/에 들어왔다. MIN_PICKS=5 반영됨(TEAM.md §8) -->
- [~] 미배선 · B의 온디바이스 프로필 로직(`data/ProfileEngine.kt`) 연결: <!-- ProfileEngine·CardRepository·PresetProfileMapper 전부 완성이나 recommend()를 부르는 프로덕션 코드가 0줄. 현재는 하드코딩 맵이 그 자리를 대신한다 --> 카드 선택→프로필 생성→추천 상위 3종을 카메라 스타일 스트립의 기본 순서로 적용. 별도 요약·내 스타일 화면은 만들지 않음
- [x] 📱 온보딩은 취향 카드 선택 → “내 감도 저장” 두 단계만 유지하고, 완료 후 카메라로 직행·재실행 시 스킵 <!-- ⚠️ 단, 2b '내 감도 저장'의 색 스와치 3개·요약 3줄이 하드코딩이라 어떤 카드를 골라도 동일하다. 이 상태로 시연하면 AGENTS.md §7-6(더미로 속이지 않는다) 판정 대상 -->
- 완료 기준: 신규 설치→첫 촬영 60초 이내(스톱워치 실측), 서로 다른 카드 선택 2세트가 카메라 스타일 스트립의 상위 순서를 다르게 만듦

### 6-3. 피드백 UI + 저장·공유 마감

- [ ] 저장 직후 1탭 피드백 시트: 5개 선택지(이 느낌이 맞아요 / 구도는 좋은데 색감은 별로 / 색감은 좋은데 인위적 / 다음엔 더 자연스럽게 / 이 스타일 저장) — 스킵 가능, 5초 자동 닫힘
- [ ] 선택 → `feedback` 테이블 기록 + B의 ProfileEngine 반영 호출(로컬)
- [ ] "이 스타일 저장" → 현재 파라미터를 개인 프리셋으로 저장(이름 입력)
- [ ] 공유: OS 공유 시트 <!-- 결과 화면에 공유 버튼 없음 -->
- ~~내 스타일 화면(선호 요약·최근 스타일·개인화 초기화)~~ **컷** (D11 및 §6-2 '별도 요약 화면 없음'과 충돌, 부록 A 컷라인 4, TEAM.md §8)
- 완료 기준: "색감은 별로" 2회 후 같은 조건 촬영에서 색감 파라미터가 달라짐을 로그로 확인

---

## Day 7 — 시연 품질 (성능·폴리싱·리허설)

**당일 데모 완료 기준: 데모 시나리오 A·B를 처음부터 끝까지 3회 연속 재현. 백업 영상 확보.**

### 7-1. 성능 조정 (오전)

- [ ] 분석 주기·해상도 최종 튜닝: 프리뷰 30FPS, 안내 갱신 지연 200ms 이하(HUD 계측값으로 판정)
- [ ] 발열 확인: 10분 연속 촬영 후 프레임 저하 측정 — 저하 시 분석 주기 자동 하향(초당 8회)
- [ ] 앱 실행→첫 분석 2초 이내(콜드 스타트 측정), 불필요 로그 제거

### 7-2. 화면 폴리싱 (오전)

- [ ] 차콜 다크 테마 통일, 안내 문구 최종 검수(전문 용어 0건 — "삼분할" 같은 단어 금지)
- [ ] 디버그 HUD 숨김(개발자 제스처로만 열림), 스플래시·아이콘 적용

### 7-3. 시연 모드 (오후)

- [ ] 시연 모드 토글(숨김 설정): 온보딩 리셋 버튼, 데모용 스타일 고정, 네트워크 상태 표시
- [ ] 발표 장소와 비슷한 조명에서 데모 리허설 — 시나리오 A(레퍼런스/스타일 따라 찍기), B(망한 사진 살리기) **각 3회 연속 성공**할 때까지. 실패 시 guide_config 임계값 현장 조정
- [ ] 성공 시연 **전 과정 화면 녹화 영상 확보**(발표 당일 라이브 실패 시 즉시 전환용)
- [ ] 데모용 소품 준비: 망한 사진 3장(기울고 어둡고 행인 있는), 레퍼런스 2장을 기기에 미리 배치

### 7-4. 최종 점검 체크 (저녁, B와 함께)

- [ ] 로드맵 §11 최종 완료 기준 6항목 전부 통과 확인
- [ ] 컷한 기능 목록 정리(발표에서 "다음 단계"로 언급할 것들)
- [ ] 앱 APK 백업 2부(발표 기기 + 예비 기기 설치)

---

## 부록 A. 담당 A의 컷라인 (밀리면 이 순서로 자른다)

1. 레퍼런스 원본 오버레이의 실루엣 모드(원본 반투명만 유지)
2. 스타일 강도 슬라이더
3. 동적 장면 제안(정적 프리셋 프레임으로 다운그레이드 — §0.4)
4. 내 스타일 화면(홈에 요약 한 줄로 대체)

**끝까지 지키는 것:** 목표 프레임·실루엣·수평선 오버레이, 촬영·저장, 로컬 보정 3단계, 전후 슬라이더, 온보딩 카드→추천.

## 부록 B. B에게 받아야 하는 산출물 일정 (지연 시 즉시 에스컬레이션)

| 시점 | 받을 것 | 형태 |
|---|---|---|
| Day 1 저녁 | presets.json 6종, /edit-jobs 계약 스텁, OpenAPI 계약 | JSON + 실행 중 서버 |
| Day 2 정오 | FrameFeatureCalculator.kt + 단위 테스트 | 순수 Kotlin 파일 |
| Day 3 정오 | AlignmentEngine.kt + guide_config.json + 오버레이 좌표 테스트 4종 | 순수 Kotlin 파일 |
| Day 4 정오 | ProblemDiagnoser.kt(온디바이스 진단) | 순수 Kotlin 파일 |
| Day 5 정오 | /references/analyze 실서버, /edit-jobs 실서버(생성 1기능) | 배포된 API |
| Day 6 정오 | ProfileEngine.kt(온보딩 프로필+피드백 반영) + cards.json | 순수 Kotlin 파일 + 에셋 |
