# P2_Plan_1 — 담당 B: 백엔드·CV·생성 (7일 상세 로드맵)

> **기반 문서(우선순위 순):** `AGENTS.md` · `P1_Plan_1.md` · `docs/감도_GAMDO_기능명세서_v1.0_3.md` · `docs/감도_GAMDO_DB스키마_v2.0.md` · `docs/감도_GAMDO_PRD_v1.0_2.md` · `docs/MOODFRAME_2인_개발_로드맵.md`
> **역할:** 스타일 구조·구도 점수·가이드 로직(온디바이스 Kotlin 모듈), FastAPI 서버, 편집 작업 큐, 생성형 편집 파이프라인, 결과 검증
> **최종 산출물:** ① 앱에 탑재되는 로직 모듈 4개(Kotlin) ② 레퍼런스 분석·생성 복구가 실동작하는 서버

---

## 0. 전제와 규칙 (반드시 읽고 시작)

### 0.1 확정 전제 (로드맵 문서와의 차이 포함 — 이것이 최종)

| 항목 | 확정 내용 |
|---|---|
| 서버 스택 | **FastAPI + SQLite(3테이블) + 로컬 디스크 + DB 폴링 워커.** 로드맵의 PostgreSQL·Redis·S3는 확장 시 도입(스키마 v2.0 §7 경로 준비됨) — 7일 범위에서 세우지 않는다 |
| 서버 API | **3개(+폴링)뿐:** `GET /presets`, `POST /references/analyze`, `POST /edit-jobs` + `GET /edit-jobs/{id}`. 로드맵의 `/style-profiles/onboarding` `/captures/analyze` `/feedback`은 **온디바이스 Kotlin 모듈로 대체**(로그인 없음·로컬 우선 결정) |
| 생성형 공급자 | `GenerativeEditProvider` 인터페이스 뒤에 배치(교체 가능). 1순위 자체 GPU(ComfyUI: LaMa+FLUX.1 Fill), Day 1 품질 비교로 확정 |
| 생성 기능 범위 | **객체 제거 또는 여백 확장 중 최소 1개 완성.** 권장 순서: 객체 제거(LaMa) 먼저 → 여백 확장(FLUX Fill)은 여력 시 |
| B의 특수 임무 | 가이드·프로필 로직은 서버가 아니라 **순수 Kotlin 모듈**로 작성해 A에게 전달한다(UI·Android API 의존 금지, JVM 단위 테스트 동반). 알고리즘 소유는 B, 통합은 A |
| 가이드 방식 | **시각 오버레이 전용** — 텍스트 안내·자동 촬영·일치도 게이지 없음(제품 결정). matchScore는 KPI 로그 전용 내부 값 |

### 0.2 완료(Done)의 정의

- "코드 작성"이 아니라 **"사용자가 해당 흐름을 실기기에서 끝까지 수행 가능"** + **"단위 테스트/검증 스크립트 통과"**.
- A에게 주는 모듈은 단위 테스트 없이 전달 금지.

### 0.3 매일 반복 (고정 일과)

- [ ] (매일 오전, 15분) A와 오늘의 "당일 데모 완료 기준" 확정
- [ ] (매일 저녁) 실기기 통합 테스트 3회 참여 — 서버 로그를 열어두고 관찰
- [ ] (매일 저녁, 30분) A와 프리셋 파라미터 공동 튜닝 — `presets.json` 값 수정·커밋(스키마 변경 금지)

### 0.4 판정 규칙

- **Day 5 종료 시** 생성 API가 불안정하면: 고정 더미 이미지로 속이지 말고 **생성 기능을 기능 플래그로 숨기고** 기본 보정 데모를 완성한다.
- **Day 6부터** 새 기능 추가 금지.

### 0.5 현재 기준 실행 보드 (2026-07-24, 이 순서로 착수)

현재 앱에는 `DetectionResult`(얼굴·포즈 정규화 좌표), `TiltReading`, `AnalysisStats`, CameraX 프리뷰와 기본 `CameraOverlay`가 있다. 내비게이션은 이미 `onboarding → camera → album → result`이며 카메라가 홈이다. 따라서 P2는 화면이나 별도 홈을 만들지 않고, 아래 계약을 지키는 순수 모듈·서버만 전달한다.

| 순서 | P2가 만들 정확한 경로 | A가 연결할 기존 지점 | 통합 완료 기준 |
|---|---|---|---|
| 1 | `gamdo-server/app/main.py`, `gamdo-server/app/db.py`, `gamdo-server/app/routes/{presets,references,edit_jobs}.py`, `gamdo-server/migrations/001_initial.sql` | 앱의 네트워크 클라이언트 | 실기기에서 `/presets` 200, `/edit-jobs`가 queued→fallback 또는 done으로 전이 |
| 2 | `app/src/main/java/com/gamdo/app/detect/FrameFeatureCalculator.kt`와 `app/src/test/java/com/gamdo/app/detect/FrameFeatureCalculatorTest.kt` | `detect/Detections.kt`, `detect/FrameAnalyzer.kt`, `sensor/TiltSensor.kt` | 합성 입력 10케이스 통과 후 프리뷰 디버그 값이 0~1/도 단위 범위를 벗어나지 않음 |
| 3 | `app/src/main/java/com/gamdo/app/guide/AlignmentEngine.kt`, `app/src/main/assets/guide_config.json`, `app/src/test/java/com/gamdo/app/guide/AlignmentEngineTest.kt` | `ui/camera/CameraOverlay.kt` | 목표 프레임·실루엣·수평선만 렌더되고, aligned일 때만 세이지 색으로 전환 |
| 4 | `app/src/main/java/com/gamdo/app/detect/ProblemDiagnoser.kt`, `app/src/test/java/com/gamdo/app/detect/ProblemDiagnoserTest.kt` | 앨범에서 선택한 사진의 기본 보정 흐름 | 기울어짐·노출·흐림 의심을 내부 진단하고 기본 보정 결과까지 도달 |
| 5 | `app/src/main/java/com/gamdo/app/data/ProfileEngine.kt`, `app/src/main/assets/cards.json`, 해당 JVM 테스트 | 온보딩 카드 선택 → “내 감도 저장” | 추천 상위 3종이 카메라의 스타일 스트립 기본 순서에 반영되고, 별도 요약/홈 화면은 만들지 않음 |

**모듈 경계는 먼저 고정한다.** `FrameFeatureCalculator`는 Android·ML Kit 타입을 받지 않는다. A의 어댑터가 `DetectionResult`·`TiltReading`·밝기 평균·흔들림 값을 `FrameFeatureInput`으로 변환하고, P2 모듈은 `primaryPersonBox`, `primaryFaceBox`, `personAreaRatio`, `headroomRatio`, `sideMargins`, `tiltDeg`, `brightnessMean`, `backlight`, `lowLight`, `poseConfidence`만 포함한 `FrameFeatures`를 반환한다. 다중 인물 처리는 방어용 주 피사체 선택일 뿐, UI 기능으로 노출하지 않는다.

`AlignmentEngine`의 계약은 `FrameFeatures + StyleTarget + GuideConfig → OverlayState(targetFrame, silhouette, horizonY, visible, aligned)`로 한정한다. 내부 `matchScore`는 로그/튜닝용 별도 `GuideMetrics`에만 두며 UI 모델과 `OverlayState`에 넣지 않는다. 텍스트 지시, 화살표, 게이지, 자동 셔터를 추가하는 필드는 만들지 않는다.

`ProblemDiagnoser` 역시 `Bitmap`을 직접 받지 않는다. A가 이미지에서 추출한 `ImageMetrics`(기울기·휘도 히스토그램·라플라시안 분산·여백)를 전달하면 P2 모듈은 코드·심각도·수치만 반환한다. 사용자 문구는 A의 UI 계층에서 일상 언어로 매핑하고, 수치·전문 용어는 디버그에만 둔다.

서버의 Day 1 스텁은 고정 결과 이미지를 반환하지 않는다. `/edit-jobs`는 개발 중에도 실제 상태 전이만 검증하고, 생성 미구현 시 `fallback`으로 종료해 앱의 로컬 기본 보정 결과를 유지한다. `dummy:true`나 샘플 결과를 사용자에게 보여 주는 경로는 만들지 않는다. API 계약의 원본은 실행 중인 FastAPI OpenAPI(`/openapi.json`)이며, `gamdo-server/tests/`에 4개 엔드포인트 계약 테스트를 둔다.

---

### 0.6 구현 현황 (2026-07-25 최신 감사)

| 작업 축 | 현재 상태 | 검증 근거 | 남은 판정 |
|---|---|---|---|
| 온디바이스 가이드 | `FrameFeatureCalculator`·`MatchScoreCalculator`·`AlignmentEngine`·`ProblemDiagnoser` 구현 | Android JVM 테스트 통과 | 실기기에서 오버레이 튐·정렬 색 전환 확인 |
| 카메라 연결 | `guide_config.json` 로딩, CameraOverlay 목표 프레임·실루엣 연결 | Debug APK 빌드·단위 테스트 통과 | 연결 기기 없음 |
| 레퍼런스 분석 | 무저장 `POST /references/analyze`, 팔레트·히스토그램·Target/Color 변환 구현 | 계약 테스트 + 합성 10장 벤치마크(최대 약 136ms) | 실제 사진 10장·MediaPipe 보강 |
| 편집 큐·보관 | SQLite 큐, 워커, EXIF 스트립, 입력 즉시 purge, 결과 24h·job 메타 7일 purge 구현 | 서버 워커 purge/timeout/fallback 테스트 통과 | 운영 워커 기동·실제 전달 확인 |
| 생성·검증 | ComfyUI 업로드/프롬프트 어댑터, 후보 검증 경계·fallback·요청/이미지/마스크 제한 구현 | 공급자 미설정 fallback·검증 테스트 통과 | GPU·LaMa·InsightFace 배포 및 실제 후보 검증 |
| 개인화·지표 | `ProfileEngine`, v1 카드 메타 16장, 로컬 KPI 집계 스크립트 구현 | Android JVM·카드 검증·SQLite fixture 테스트 통과 | 실제 카드 에셋·Room/UI 통합·실기기 지표 수집 |

---

## Day 1 — 서버 골격 + 생성 공급자 확정 + 계약 고정

**당일 데모 완료 기준: A가 스타일을 선택해 사진을 찍고, `/edit-jobs` 상태 전이 검증 뒤 로컬 결과 화면까지 연결된다.**

### 1-1. FastAPI 서버 골격

- [x] 프로젝트 생성: `gamdo-server/` — FastAPI + uvicorn + sqlite3 직접 + Pillow <!-- 2026-07-24: FastAPI 테스트 통과 -->
- [x] SQLite 초기화 — **DB 스키마 v2.0 §4 그대로 3테이블**: `edit_jobs`, `edit_job_files`, `schema_migrations`. DDL 파일 `migrations/001_initial.sql`
- [x] 저장 디렉토리: `storage/inputs/`, `storage/results/`, `storage/tmp/`
- [x] `X-Device-Id` 헤더 미들웨어(없으면 400), 에러 응답 규격 `{code, message, retryable}`
- [ ] 앱에서 접근 가능한 네트워크 구성 확인(같은 Wi-Fi, 방화벽) — **데모 환경에서도 유효한 방식으로**(핫스팟 대비)
- 완료 기준: 실기기 앱에서 `GET /presets` 200 응답

### 1-2. 프리셋 6종 초안 + 정적 서빙

- [x] `presets.json` 작성 — 스키마는 기능명세서 M3-01, **6종**: Clean Social(정돈 배경·삼분할·따뜻한 색감), Candid Feed(즉흥 프레이밍·자연스러운 자세·약한 입자감), Bright Review(중앙 피사체·밝은 노출·선명), Soft Film(넓은 배경·중심 이탈 허용·낮은 대비·페이드·입자), Casual Portrait(멀리서 촬영·자연스러운 시선·배경 포함), Night Street(조명 강조·그림자 유지·높은 색 대비)
- [x] 각 프리셋에 앱 계약(`subjectPosition`) 기준 composition(subjectScaleRange, headroomRange, horizonPosition, cameraPitchRange)과 color(exposureBias, colorTemperature, contrast, saturation, grain, vignette, fade) 초기값 기입 — `scripts/validate_presets.py`로 6종 스키마 검증 완료(근거 사진 튜닝은 외부 콘텐츠 작업 대기)
- [x] `GET /presets` — 정적 파일 서빙 + ETag. 동일 파일을 A에게 전달(앱 번들 폴백) <!-- 서버 계약 테스트 통과 -->
- 완료 기준: JSON 스키마 검증 스크립트 통과, A 앱에서 6종 로드 확인

### 1-3. /edit-jobs 계약 스텁 (A의 결과 화면 연결용)

- [x] `POST /edit-jobs`: 요청 저장 후 `202 {jobId, status:"queued"}` 반환
- [x] `GET /edit-jobs/{id}`: 호출 2회째부터 `fallback` 반환. 생성 결과 이미지는 만들지 않으며 앱은 로컬 기본 보정 결과를 그대로 유지
- [x] 서버 계약 테스트: queued→processing→fallback 전이, 잘못된 jobId 404, 잘못된 요청 422를 `gamdo-server/tests/`에서 검증
- 완료 기준: A의 네트워크 연결 테스트 성공 + 고정 이미지가 결과 화면에 노출되지 않음

### 1-4. 생성형 공급자 품질 비교 → 확정 (오후 최우선)

- [ ] GPU 서버에 ComfyUI headless 설치, 모델 배치: **LaMa**(객체 제거) + **FLUX.1 Fill [dev]**(VRAM 16GB 미만이면 FP8/GGUF 양자화)
- [ ] 비교 테스트: 동일한 "망한 사진" 5장(행인·전봇대 포함)으로 ①ComfyUI(LaMa/FLUX) ②Gemini 이미지 편집 무료 티어 실행
- [ ] 판정 기준표 작성·기록: 얼굴 불변 여부 / 제거 흔적 자연스러움 / 응답 시간 / 호출 제한이 리허설(30회+)을 버티는가
- [ ] **공급자 확정 문서화**(`docs/provider_decision.md`) — 이후 변경 금지
- [x] `GenerativeEditProvider.remove_objects(image, operations, resultCount) -> candidates` 인터페이스와 ComfyUI 어댑터 구현 — 구현체 교체 가능 구조
- [ ] `outpaint(image, direction, ratio) -> candidates` 인터페이스·실제 모델 배포 — 여백 확장 범위 대기
- 완료 기준: 확정 공급자로 객체 제거 1장 성공 샘플 확보

### 1-5. (저녁) A와 인터페이스 계약 고정 — 30분

- [x] `presets.json` 스키마 서명(이후 값 튜닝만 허용) — `scripts/validate_presets.py` 자동 검증 추가
- [x] `FrameFeatures`/`FrameFeatureInput` 필드 명세 확정(`P2_Plan_1.md` §0.5를 구현 계약으로 사용) <!-- Kotlin 구현·JVM 테스트 완료 -->
- [x] `/references/analyze`, `/edit-jobs` 요청·응답 JSON 구현·계약 테스트 <!-- FastAPI OpenAPI 자동 생성, 서버 테스트 통과 -->

---

## Day 2 — 구도를 숫자로 만든다 (특징·점수 모듈)

**당일 데모 완료 기준: A의 프리뷰에 인물 위치·수평 결과가 표시된다(A와 공동).**

### 2-1. FrameFeatureCalculator.kt (순수 Kotlin — 정오까지 A에게 전달)

- [x] 입력: ML Kit 얼굴/포즈 결과(정규화 좌표), 센서 tilt, 프레임 밝기 샘플 / 출력: `FrameFeatures` 데이터 클래스
- [x] 계산 구현: personBox(포즈 랜드마크 외접), personCenter, personAreaRatio, headroom(얼굴 상단↔프레임 상단), sideMargins, tiltDeg, brightnessMean, backlightFlag(얼굴 영역 대비 배경 밝기 비 1.8배 이상), lowLightFlag, poseConfidence
- [x] 다중 인물 시 주 피사체 규칙: 면적 최대 + 중앙 근접 가중(1인 우선 범위지만 방어 코드)
- [x] JVM 단위 테스트 10케이스+ (합성 좌표 입력 → 기대값): 중앙 인물, 좌측 치우침, 큰 얼굴, headroom 과다, 역광 등
- 완료 기준: 테스트 전부 통과, A 통합 후 실기기 값이 상식적으로 동작

### 2-2. matchScore 계산기 (FrameFeatureCalculator에 포함)

- [x] 로드맵 §4.2 공식 그대로 구현(설명 가능성 우선):
  `matchScore = 0.35*composition + 0.25*subjectScale + 0.15*headroom + 0.15*horizon + 0.10*lighting`
- [x] 각 항은 0~1 정규화: 목표 범위 내=1, 범위 밖은 거리 비례 감쇠(선형, 컷오프 2배 거리)
- [x] `StyleTarget` 변환기: `presets.json`의 composition → 목표값 객체(A의 AlignmentEngine 입력)
- [x] 단위 테스트: 프리셋 6종 × 장면 4종 조합의 점수 스냅샷 테스트
- 완료 기준: 같은 프레임에 프리셋을 바꾸면 점수가 다르게 나온다(테스트로 증명)

### 2-3. 서버: 레퍼런스 분석 파이프라인 착수 (Day 5 완성 목표의 절반)

- [ ] Python 분석 스택 셋업: MediaPipe(포즈·얼굴) + Pillow/OpenCV(팔레트·수평·히스토그램)
- [ ] 분석 함수 v1: 인물 수, 인물 박스, 얼굴 크기, 인물 점유율, headroom, 수평 추정, 주조색 팔레트(k-means 5색), 색온도 추정, 명암 히스토그램
- [x] 테스트 이미지 10장으로 결과 JSON 눈검증 — `scripts/reference_benchmark.py` 합성 10장 전부 통과, 최대 5초 기준 통과
- 완료 기준: 10장 전부 예외 없이 구조화 JSON 출력

---

## Day 3 — 오버레이를 결정하는 두뇌 (AlignmentEngine)

**당일 데모 완료 기준: 실기기에서 목표 구도 오버레이가 안정 표시되고 사용자가 맞춰서 수동 촬영한다(A와 공동).**

> **제품 결정 반영:** 텍스트 안내·자동 촬영·일치도 게이지는 만들지 않는다. B의 두뇌 모듈은 "무엇을 말할지"가 아니라 **"오버레이를 어디에, 얼마나 안정적으로 그릴지"** 를 결정한다.

### 3-1. AlignmentEngine.kt (순수 Kotlin — 정오까지 A에게 전달)

- [x] 입력: `FrameFeatures` + `StyleTarget` + `GuideConfig` / 출력: `OverlayState(targetFrame: RectN, silhouette: SilhouetteSpec, horizonLine: Float, visible: Boolean, aligned: Boolean)`. `matchScore`는 별도 내부 `GuideMetrics`에만 기록(UI 표시 금지 계약)
- [x] 목표 프레임 산출: 프리셋 composition(anchor·scaleRange·headroomRange)을 현재 장면(개방 공간·인물 위치)에 투영해 실현 가능한 목표 영역 계산 <!-- 현재는 프리셋 기반, 장면 구조 스캔은 후속 -->
- [x] 안정화 구현: 오버레이 좌표 이동평균(윈도 5프레임) + 재계산 히스테리시스(장면 대폭 변화 시에만 목표 갱신, 예: 전역 이동량 임계 초과) + 신뢰도 미달 시 마지막 안정값 유지 + 지속 불안정 시 `visible=false`
- [x] 인물 진입 판정: 인물 박스가 목표 프레임과 IoU 임계(기본 0.7) 이상이면 `aligned=true`(오버레이 색 전환용 — 유일한 피드백)
- [x] **`guide_config.json`**: 이동평균 윈도·IoU 임계·재계산 임계 등 전부 외부화(현장 튜닝용) — 기본값 명시
- [x] 단위 테스트 4종: 중앙 장면→기대 목표 좌표 / 인물 진입→aligned 전환 / 흔들리는 입력→좌표 분산 임계 이하(안정화 증명) / 신뢰도 미달→visible 유지 로직
- 완료 기준: 테스트 4종 통과 + 실기기에서 오버레이 깜빡임 없음(A와 저녁 판정)

### 3-2. 오버레이 안정성 공동 튜닝

- [ ] A와 함께 실기기에서 이동평균·히스테리시스 값 1차 튜닝(30분) — 좌표 튐/지연 트레이드오프 기록
- [ ] **오후 6시 공동 판정**: 동적 오버레이 불안정 시 정적 프리셋 프레임으로 다운그레이드 결정(`P1_Plan_1.md` §0.4)
- 완료 기준: 판정 회의록 1줄 기록(동적 유지/정적 다운그레이드)

### 3-3. 서버: 편집 작업 큐 실구현 착수

- [x] 업로드 수신(multipart) → **EXIF 위치 정보 스트립** → `storage/inputs/` 저장 → `edit_jobs`/`edit_job_files` 기록
- [x] 워커 프로세스: `edit_jobs` 폴링(status='queued', 1초) → 순차 처리(동시 1건) → 상태 갱신(processing→validating→done) <!-- `python -m app.worker`; 실제 공급자 미설정 시 fallback -->
- [x] 상태 스텁을 `GenerativeEditProvider` 호출로 교체할 자리 마련(인터페이스 연결)
- 완료 기준: 업로드→큐→상태 전이→fallback 또는 실제 결과까지 로그로 추적 가능

---

## Day 4 — 편집 파이프라인이 진짜가 된다

**당일 데모 완료 기준: 원본·기본 보정·스타일 보정 비교가 가능하다(A 주도 — B는 진단 모듈·서버 실구현).**

### 4-1. ProblemDiagnoser.kt (순수 Kotlin — 정오까지 A에게 전달)

- [x] 입력: A 어댑터가 만든 `ImageMetrics`(축소 이미지의 휘도·기울기·라플라시안 분산·여백) + `FrameFeatures`(있으면) / 출력: `List<Problem>` — `TILT`, `UNDEREXPOSED`, `OVEREXPOSED`, `BLUR_SUSPECT`, `EXCESS_MARGIN`, `BACKLIGHT`. Android `Bitmap` 타입에 의존하지 않음
- [x] 각 Problem은 코드·심각도·내부 수치만 포함. 사용자 표시 문구는 A UI에서 일상 언어로 매핑하고 전문 용어·수치 노출은 하지 않음
- [x] 단위 테스트: 문제 유형별 합성 수치 6케이스 → 기대 진단 <!-- 실제 이미지 어댑터·UI 연결은 A 통합 대기 -->
- 완료 기준: 테스트 통과, A의 사진 살리기 화면에 진단 칩 표시

### 4-2. 객체 제거 파이프라인 (생성 기능 1순위 — 실구현)

- [x] ComfyUI 워크플로 JSON 템플릿: LaMa 객체 제거(마스크 입력), 파라미터 주입 함수 — `gamdo-server/workflows/lama_remove_objects_api.json`, seed별 후보 큐잉
- [ ] **자동 마스크 생성**: 서버 측 인물 감지(MediaPipe)로 "주 피사체가 아닌 사람" 후보 마스크 산출 + 클라이언트 수동 마스크 병합 지원
- [ ] 큰 영역(프레임의 15% 초과) 제거 시 FLUX.1 Fill로 승격하는 분기
- [x] 워커에 연결: `remove_objects` operation 처리 → 결과 후보 2개(시드 2개) 생성 — SSH 터널 경유 CAMP-2 ComfyUI E2E 확인
- 완료 기준: 행인 있는 테스트 사진 5장 중 4장 이상에서 육안 합격 결과

### 4-3. 결과 검증기

- [x] InsightFace 임베딩: 편집 전후 주 피사체 얼굴 거리 계산, **임계 초과 시 해당 후보 폐기** — `InsightFaceVerifier` 구현 및 CAMP-2 `buffalo_l` CUDA provider 로드 확인. 동일인 테스트 셋 캘리브레이션은 대기
- [ ] 얼굴 보호 마스크: 편집 마스크와 얼굴 박스 교차 시 얼굴 영역 제외(팽창 마진 10%)
- [ ] 휴리스틱 검사: 결과 인물 수 ≠ 기대 인물 수 → 폐기, 극단 색상 변화(히스토그램 거리) → 폐기
- [x] 전 후보 폐기·공급자 미준비 시: `status='fallback'` + failReason 기록 (앱은 기본 보정 유지 — A와 계약된 동작)
- [x] 임베딩은 **메모리에서만 사용 후 폐기** — DB·파일 저장 금지(validation_json에 거리값만) <!-- 스키마·검증 인터페이스에서 영구 저장 경로 없음. InsightFace 실제 구현 대기 -->
- 완료 기준: 얼굴이 바뀐 결과가 후보에 포함되지 않음(의도적 변형 샘플로 검증)

### 4-4. 보관 정책 구현

- [x] job 종료 시 입력 파일 `purge_after=now`, 결과는 `delivered_at`+24h — 결과 응답 시 delivered/purge 예약 및 삭제 배치 테스트 완료
- [x] 삭제 배치(1분 주기): purge 대상 파일 삭제 + `purged_at` 기록 <!-- 워커 tick에서 purge 처리; 실제 1분 서비스 기동 검증 대기 -->
- 완료 기준: job 완료 1분 후 `storage/inputs/`가 비어 있음을 확인하는 테스트 스크립트 통과

---

## Day 5 — 레퍼런스 분석 완성 + 생성 실서비스 전환

**당일 데모 완료 기준: 레퍼런스 오버레이 가이드 또는 생성 복구 중 최소 하나가 실서버로 동작한다.**

### 5-1. POST /references/analyze 완성

- [x] Day 2-3 분석 함수를 API로 노출: multipart 수신 → 분석 → 응답(기능명세서 §10.2 스키마: analysis + targetComposition + colorTarget) → **임시 파일 즉시 삭제**(DB 기록 없음) <!-- Pillow 메모리 분석 구현 -->
- [x] 응답 시간 5초 이내(초과 시 분석 해상도 축소), 타임아웃·비인물 사진(인물 0명) 응답 처리 <!-- 소형 테스트 이미지 계약 테스트; 10장 성능 검증은 대기 -->
- [x] targetComposition 변환: 레퍼런스의 인물 배치·비율 → StyleTarget 형식(A의 AlignmentEngine이 그대로 소비)
- [x] colorTarget 변환: 팔레트·색온도 → A의 스타일 단계 파라미터 매핑표
- 완료 기준: A의 레퍼런스 모드에서 분석→실루엣 오버레이 표시가 실동작(저녁 통합 테스트)

### 5-2. /edit-jobs 실서비스 전환

- [ ] Day 1의 fallback 상태 스텁을 실제 파이프라인으로 교체하고, 실제 생성 결과가 있을 때만 `done`으로 전이 — provider/워크플로 연결 완료, FastAPI 프로세스↔CAMP-2 네트워크 연결은 대기
- [x] 진행 상태 세분화: `progress_stage` 갱신(removing→validating), 폴링 응답에 포함
- [ ] (여력 시) 여백 확장 operation: FLUX.1 Fill 아웃페인팅 — 상한 원본의 30%, 방향별(top/left/right)
- [x] 동시 요청 방어: 디바이스당 진행 중 job 1개 제한(초과 시 409)
- 완료 기준: 실기기에서 업로드→처리→후보 2개 수신→"AI 생성 보완" 뱃지 표시까지 관찰

### 5-3. (오후 6시) A와 공동 판정

- [ ] 생성 안정성 판정: 연속 5회 요청 중 4회 이상 합격 결과 → 유지 / 미달 → **§0.4 규칙: 기능 플래그로 숨기고 기본 보정 데모 확정**
- [ ] 판정 결과 기록

---

## Day 6 — 신뢰성·개인화 마감

**당일 데모 완료 기준: 네트워크·생성 실패 상황에서도 기본 보정 결과가 항상 제공된다.**

### 6-1. ProfileEngine.kt (순수 Kotlin — 정오까지 A에게 전달)

- [x] 온보딩 프로필 생성: 카드 특성 벡터(`cards.json`) 가중 평균 + 차원별 확신도(분산 기반) → composition/color 분리 프로필
- [x] 추천 스타일: 프리셋 6종과 프로필 벡터 거리 → 정렬 후 상위 3종 반환. 반환값은 카메라 스타일 스트립의 기본 순서에만 반영하며, 별도 요약/내 스타일 화면을 만들지 않음
- [x] 피드백 반영: 색감 관련 선택지 → 구도/색감 프로필 분리 반영(지수이동평균 α=0.3), 색감 불만은 color만 조정
- [x] 단위 테스트: 상이한 카드 선택 2세트 → 상이한 추천 / 색감 불만 → colorTemperature 보정치 변화
- 완료 기준: 테스트 통과, A의 온보딩·피드백 화면에서 실동작

### 6-2. cards.json + 온보딩 카드 에셋 마감

- [x] 카드 메타데이터 16장과 8개 차원 커버 구조 작성 — `app/src/main/assets/cards.json`, `scripts/validate_cards.py` 검증
- [ ] 실제 카드 이미지 15~20장 최종 선정(Unsplash/Pexels 라이선스 확인 기록) — 외부 콘텐츠 작업 대기
- [x] 각 카드 특성 벡터 기입(§5.1 CardFeature 스키마) — v1 범위·값 검증 완료
- 완료 기준: 커버 매트릭스에 빈 차원 없음, A에게 에셋+JSON 전달

### 6-3. 서버 신뢰성 마감

- [x] 실패 대체 총점검: 워커 stale job(처리 5분 초과) → `failed`, provider 미준비 → `fallback` 로컬 테스트 완료 — GPU 미응답 실환경 검증은 외부 환경 대기
- [x] 요청 제한: 디바이스당 시간당 job 10건, 진행 중 job 1건, 이미지 최대 12MB·최대 변 4096px, 편집 영역 30% 제한 구현 및 검증
- [x] 로그 정리: job 처리 시간·상태·purge 대기 파일 집계 스크립트(`gamdo-server/scripts/job_stats.py`) 추가
- [x] API 키·자격 증명이 앱/서버 소스에 없는지 패턴 스캔 완료(환경 변수 주입 구조 유지)
- 완료 기준: 워커 강제 종료→재시작 테스트에서 job이 유실 없이 failed/재개 처리

### 6-4. 이벤트·피드백 저장 확인 (로컬)

- [ ] A와 함께: `feedback`·`session_guides`·`events` 로컬 기록이 쌓이는지 확인(데모 후 지표 산출용)
- [x] 지표 추출 스크립트(`gamdo-server/scripts/local_metrics.py`) 준비 — 첫 촬영·저장·피드백·가이드 해결률 산출 및 SQLite fixture 테스트
- 완료 기준: 통합 테스트 3회 후 스크립트가 지표 3종을 출력

---

## Day 7 — 안정화·검증·발표 준비

**당일 데모 완료 기준: 데모 시나리오 A·B를 처음부터 끝까지 재현(A와 공동). 백업 영상·발표 자료 완료.**

### 7-1. 서버 안정화 (오전)

- [ ] 리허설 부하 확인: 연속 job 10건 처리 시간·메모리 관찰, GPU 워밍업(첫 요청 지연 제거 — 모델 사전 로드)
- [ ] 데모 환경 네트워크 리허설: 발표 장소 조건(핫스팟) 시뮬레이션, 서버·GPU 기동 스크립트 원커맨드화(`run_demo.sh`)
- [ ] 테스트 이미지 검증 일괄 실행: 준비된 세트 전체(망한 사진 10, 레퍼런스 10)로 최종 회귀 확인

### 7-2. 리허설 지원 (오후)

- [ ] A의 리허설 3회에 서버 사이드 배석 — 실패 시 로그 즉시 분석, guide_config·임계값 현장 조정
- [ ] 백업 플랜 최종화: 생성 실패 시 데모 진행 스크립트(기본 보정까지만), 네트워크 전멸 시 오프라인 데모 경로
- [ ] 성공 시연 영상 녹화 담당(화면 녹화 + 외부 촬영 2벌)

### 7-3. 발표 자료 (저녁)

- [ ] 발표 슬라이드: 문제 정의 → 시연(라이브) → 아키텍처 1장 → 개인정보 설계 1장 → 다음 단계(컷 목록 활용)
- [ ] 지표 슬라이드: Day 6 스크립트 산출값(첫 촬영 완료율 등) 삽입
- [ ] 로드맵 §11 최종 완료 기준 6항목 점검을 A와 공동 수행·기록

---

## 부록 A. 담당 B의 컷라인 (밀리면 이 순서로 자른다)

1. 여백 확장(아웃페인팅) — 객체 제거만으로 데모 성립
2. 서버 자동 마스크(클라이언트 수동 마스크만 사용)
3. 휴리스틱 검증 중 색상 변화 검사(얼굴 임베딩 검증은 유지)
4. 지표 추출 스크립트(수기 집계로 대체)
5. 생성 기능 전체(§0.4 — Day 5 판정에 따라, 최후)

**끝까지 지키는 것:** presets 6종, FrameFeatureCalculator, AlignmentEngine, 레퍼런스 분석, 객체 제거 1기능, 얼굴 검증·폴백, 업로드 자동 삭제.

## 부록 B. A에게 전달하는 산출물 일정 (`P1_Plan_1.md` 부록 B와 동일 — 지연 시 즉시 공유)

| 시점 | 전달물 | 완료 조건 |
|---|---|---|
| Day 1 저녁 | `gamdo-server/` 골격, `presets.json` 6종, `/edit-jobs` 상태 스텁, `/openapi.json` | A 앱에서 `/presets` 호출 성공, 스텁은 fallback만 반환 |
| Day 2 정오 | `detect/FrameFeatureCalculator.kt` + `FrameFeatureCalculatorTest.kt` | 테스트 10케이스 전부 통과 |
| Day 3 정오 | `guide/AlignmentEngine.kt`, `assets/guide_config.json`, `AlignmentEngineTest.kt` | 오버레이 좌표 테스트 4종 전부 통과 |
| Day 4 정오 | `detect/ProblemDiagnoser.kt` + `ProblemDiagnoserTest.kt` | 테스트 6케이스 전부 통과 |
| Day 5 정오 | `/references/analyze` 실서버 + `/edit-jobs` 실서버 | 실기기 왕복 성공, 생성 실패는 fallback |
| Day 6 정오 | `data/ProfileEngine.kt`, `assets/cards.json`, 카드 에셋·테스트 | 테스트 통과 + 카메라 스타일 스트립 순서 반영 |

## 부록 C. GPU·모델 준비물 (Day 1 오전에 확인)

- [ ] GPU 서버 VRAM 용량 확인 → FLUX.1 Fill 원본/양자화 결정 기록
- [ ] 모델 다운로드: LaMa, FLUX.1 Fill [dev](비상업 라이선스 — 상용 전환 시 교체 대상임을 provider_decision.md에 명기), InsightFace 모델
- [ ] ComfyUI headless 기동 스크립트, FastAPI에서 호출 가능한 내부 주소 확인
- [ ] 비교용 Gemini API 키 발급(무료 티어) — 비교 테스트 후 사용 여부 결정

---

## 작업 기록

### 2026-07-25 — 문서 상태 동기화

- `AGENTS.md` §8, 기능명세서 M11·M12·M14, DB 스키마 v2.0의 현황을 현재 P2 작업 트리와 동기화했다.
- 완료 표시는 로컬 코드·자동 테스트로 확인한 범위로 한정했다. 실기기 시각 검증, 앱↔서버 왕복, GPU 생성·InsightFace, 실제 카드 이미지·라이선스는 대기 상태로 유지한다.
- P1 계획 파일은 수정하지 않았다.

### 2026-07-25 — 로컬 독립 작업 진행

- `gamdo-server/scripts/validate_presets.py`: 6종 프리셋 필수 composition/color 필드와 범위 자동 검증.
- `gamdo-server/scripts/reference_benchmark.py`: 외부 파일·서버 없이 합성 레퍼런스 10장 분석 및 5초 기준 측정. 10/10 통과(최대 약 136ms).
- 결과 파일은 클라이언트 전달 시점에 `delivered_at`과 24시간 후 `purge_after`를 기록하고, 기존 삭제 배치로 제거되도록 연결. 서버 테스트로 파일 삭제까지 확인.
- 워커에 stale `processing/validating` job 복구(`processing_timeout`)와 fallback 테스트를 추가.
- `/edit-jobs`에 디바이스별 동시/시간당 요청, 업로드 크기·해상도, 편집 영역 제한을 추가.
- `scripts/job_stats.py` 및 자격 증명 패턴 스캔으로 로컬 운영 점검 경로 추가.
- 검증: `python -m pytest -q` 15 passed, 프리셋 검증 통과, 레퍼런스 10/10 통과. GPU·실기기 검증은 수행하지 않음.

### 2026-07-25 — P2 개인화·운영 지표 작업

- `app/src/main/java/com/gamdo/app/data/ProfileEngine.kt` 추가: 카드 벡터 평균·분산 확신도, composition/color 분리, 프리셋 추천 상위 3종, 색감 피드백 EMA 반영.
- `app/src/test/java/com/gamdo/app/data/ProfileEngineTest.kt` 추가: 상이한 카드 추천, 충돌 카드 확신도, 색감 피드백 분리 테스트.
- `app/src/main/assets/cards.json`에 v1 카드 메타데이터 16장 추가. 실제 이미지 파일과 라이선스 확인은 외부 콘텐츠 작업으로 남김. `gamdo-server/scripts/validate_cards.py` 통과.
- 서버 job 메타데이터 7일 purge(`Database.purge_old_job_metadata`)와 worker 테스트 추가. 파일 purge와 별도로 terminal job/file 행을 삭제한다.
- `gamdo-server/scripts/local_metrics.py`와 SQLite fixture 테스트 추가.
- 검증: 서버 `17 passed`, Android `:app:testDebugUnitTest` BUILD SUCCESSFUL.

### 2026-07-24 — P2 가이드 연결 작업

- 제품 오너 승인 후 P1 담당 영역 파일을 수정함.
- 수정 파일: `app/src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt`, `app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt`
- 변경 내용: `FrameFeatureCalculator → AlignmentEngine → OverlayProjection` 연결, `guide_config.json` 로딩, 목표 프레임·실루엣 표시, 정렬 시 흰색에서 세이지 색으로 전환.
- 기존 수동 셔터·권한·내비게이션 흐름은 변경하지 않음. 텍스트 지시·화살표·일치도 게이지·자동 촬영은 추가하지 않음.
- 검증: `:app:testDebugUnitTest` 통과. 실기기 시각 검증은 APK 설치 후 별도 진행.

### 2026-07-24 — 5단계 구현 감사

- 완료: `matchScore`·`StyleTarget` 변환, 카메라 오버레이 연결, 메모리 기반 레퍼런스 분석, 편집 큐 워커·입력 purge, `GenerativeEditProvider`·ComfyUI HTTP 어댑터·검증 fallback.
- 자동 검증: Android `:app:testDebugUnitTest` 및 서버 `python -m pytest` 통과.
- 외부 검증 대기: 연결된 Android 실기기가 없어 오버레이 시각 검증 미실행.
- 외부 검증 대기: GPU/ComfyUI와 LaMa·InsightFace 모델이 준비되지 않아 실제 객체 제거·얼굴 검증 성공 경로 미실행. 공급자 미설정 시 결과를 만들지 않고 `fallback`으로 종료하는 것은 테스트 완료.
