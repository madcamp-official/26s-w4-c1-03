# P2_Plan — 담당 B: 백엔드·CV·생성 (7일 상세 로드맵)

> **기반 문서:** MOODFRAME 2인 개발 로드맵 · 감도(GAMDO) PRD v1.0 · 기능명세서 v1.0 · DB 스키마 v2.0
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

---

## Day 1 — 서버 골격 + 생성 공급자 확정 + 계약 고정

**당일 데모 완료 기준: A가 스타일을 선택해 사진을 찍고, 더미 /edit-jobs 호출로 결과 화면까지 연결된다.**

### 1-1. FastAPI 서버 골격

- [ ] 프로젝트 생성: `gamdo-server/` — FastAPI + uvicorn + SQLAlchemy(or sqlite3 직접) + Pillow
- [ ] SQLite 초기화 — **DB 스키마 v2.0 §4 그대로 3테이블**: `edit_jobs`, `edit_job_files`, `schema_migrations`. DDL 파일 `migrations/001_initial.sql`
- [ ] 저장 디렉토리: `storage/inputs/`, `storage/results/`, `storage/tmp/`
- [ ] `X-Device-Id` 헤더 미들웨어(없으면 400), 에러 응답 규격 `{code, message, retryable}`
- [ ] 앱에서 접근 가능한 네트워크 구성 확인(같은 Wi-Fi, 방화벽) — **데모 환경에서도 유효한 방식으로**(핫스팟 대비)
- 완료 기준: 실기기 앱에서 `GET /presets` 200 응답

### 1-2. 프리셋 3종 초안 + 정적 서빙

- [ ] `presets.json` 작성 — 스키마는 기능명세서 M3-01, **3종만**: Clean Social(정돈 배경·삼분할·따뜻한 색감), Soft Film(넓은 배경·중심 이탈 허용·낮은 대비·페이드·입자), Bright Review(중앙 피사체·밝은 노출·선명)
- [ ] 각 프리셋에 composition(subjectScaleRange, subjectAnchorX/Y, headroomRange, horizonPosition, cameraPitchRange)과 color(exposureBias, colorTemperature, contrast, saturation, grain, vignette, fade) 초기값 기입 — 근거 사진 1장씩 첨부(튜닝 기준점)
- [ ] `GET /presets` — 정적 파일 서빙 + ETag. 동일 파일을 A에게 전달(앱 번들 폴백)
- 완료 기준: JSON 스키마 검증 스크립트 통과, A 앱에서 3종 로드 확인

### 1-3. 더미 /edit-jobs (A의 결과 화면 연결용)

- [ ] `POST /edit-jobs`: 요청 저장 후 `202 {jobId, status:"queued"}` 반환
- [ ] `GET /edit-jobs/{id}`: 호출 2회째부터 `done` + 준비된 샘플 결과 이미지 2장 반환(더미임을 응답 필드 `dummy:true`로 명시 — Day 5에 제거)
- 완료 기준: A의 Retrofit 연결 테스트 성공

### 1-4. 생성형 공급자 품질 비교 → 확정 (오후 최우선)

- [ ] GPU 서버에 ComfyUI headless 설치, 모델 배치: **LaMa**(객체 제거) + **FLUX.1 Fill [dev]**(VRAM 16GB 미만이면 FP8/GGUF 양자화)
- [ ] 비교 테스트: 동일한 "망한 사진" 5장(행인·전봇대 포함)으로 ①ComfyUI(LaMa/FLUX) ②Gemini 이미지 편집 무료 티어 실행
- [ ] 판정 기준표 작성·기록: 얼굴 불변 여부 / 제거 흔적 자연스러움 / 응답 시간 / 호출 제한이 리허설(30회+)을 버티는가
- [ ] **공급자 확정 문서화**(`docs/provider_decision.md`) — 이후 변경 금지
- [ ] `GenerativeEditProvider` 인터페이스 정의: `remove_objects(image, masks) -> candidates`, `outpaint(image, direction, ratio) -> candidates` — 구현체 교체 가능 구조
- 완료 기준: 확정 공급자로 객체 제거 1장 성공 샘플 확보

### 1-5. (저녁) A와 인터페이스 계약 고정 — 30분

- [ ] `presets.json` 스키마 서명(이후 값 튜닝만 허용)
- [ ] `FrameFeatures` 필드 명세 확정(Day 2 모듈의 출력 — P1_Plan Day 2-4와 동일 목록)
- [ ] `/references/analyze`, `/edit-jobs` 요청·응답 JSON 확정(기능명세서 §10) — OpenAPI 문서로 고정(`/docs` 자동 생성 확인)

---

## Day 2 — 구도를 숫자로 만든다 (특징·점수 모듈)

**당일 데모 완료 기준: A의 프리뷰에 인물 위치·수평 결과가 표시된다(A와 공동).**

### 2-1. FrameFeatureCalculator.kt (순수 Kotlin — 정오까지 A에게 전달)

- [ ] 입력: ML Kit 얼굴/포즈 결과(정규화 좌표), 센서 tilt, 프레임 밝기 샘플 / 출력: `FrameFeatures` 데이터 클래스
- [ ] 계산 구현: personBox(포즈 랜드마크 외접), personCenter, personAreaRatio, headroom(얼굴 상단↔프레임 상단), sideMargins, tiltDeg, brightnessMean, backlightFlag(얼굴 영역 대비 배경 밝기 비 1.8배 이상), lowLightFlag, poseConfidence
- [ ] 다중 인물 시 주 피사체 규칙: 면적 최대 + 중앙 근접 가중(1인 우선 범위지만 방어 코드)
- [ ] JVM 단위 테스트 10케이스+ (합성 좌표 입력 → 기대값): 중앙 인물, 좌측 치우침, 큰 얼굴, headroom 과다, 역광 등
- 완료 기준: 테스트 전부 통과, A 통합 후 실기기 값이 상식적으로 동작

### 2-2. matchScore 계산기 (FrameFeatureCalculator에 포함)

- [ ] 로드맵 §4.2 공식 그대로 구현(설명 가능성 우선):
  `matchScore = 0.35*composition + 0.25*subjectScale + 0.15*headroom + 0.15*horizon + 0.10*lighting`
- [ ] 각 항은 0~1 정규화: 목표 범위 내=1, 범위 밖은 거리 비례 감쇠(선형, 컷오프 2배 거리)
- [ ] `StyleTarget` 변환기: `presets.json`의 composition → 목표값 객체(A의 GuideEngine 입력)
- [ ] 단위 테스트: 프리셋 3종 × 장면 4종 조합의 점수 스냅샷 테스트
- 완료 기준: 같은 프레임에 프리셋을 바꾸면 점수가 다르게 나온다(테스트로 증명)

### 2-3. 서버: 레퍼런스 분석 파이프라인 착수 (Day 5 완성 목표의 절반)

- [ ] Python 분석 스택 셋업: MediaPipe(포즈·얼굴) + Pillow/OpenCV(팔레트·수평·히스토그램)
- [ ] 분석 함수 v1: 인물 수, 인물 박스, 얼굴 크기, 인물 점유율, headroom, 수평 추정, 주조색 팔레트(k-means 5색), 색온도 추정, 명암 히스토그램
- [ ] 테스트 이미지 10장으로 결과 JSON 눈검증
- 완료 기준: 10장 전부 예외 없이 구조화 JSON 출력

---

## Day 3 — 안내를 결정하는 두뇌 (GuideEngine)

**당일 데모 완료 기준: 실기기에서 단일 안내와 자동 촬영이 동작한다(A와 공동).**

### 3-1. GuideEngine.kt (순수 Kotlin — 정오까지 A에게 전달)

- [ ] 입력: `FrameFeatures` + `StyleTarget` / 출력: `GuideState(currentGuide: Guide?, matchScore: Float, autoShutterReady: Boolean)`
- [ ] **안내 우선순위 체인(로드맵 §4.2 고정):** ① 인물 미검출 → ② 수평(|tilt|>3°) → ③ 인물 위치(anchor 이탈) → ④ 거리/크기(scaleRange 이탈) → ⑤ 여백(headroomRange 이탈) → ⑥ 밝기(역광·저조도)
- [ ] 안정화 구현: 특징값 이동평균(윈도 5프레임) + 히스테리시스(진입 임계 > 해제 임계, 예: 수평 진입 3°/해제 1.5°) + 안내 최소 유지 0.8초
- [ ] 안내 문구 카탈로그(한국어, 전문 용어 금지): `오른쪽으로 한 걸음 이동하세요` `카메라를 조금 낮춰주세요` `피사체와 거리를 벌려주세요` `얼굴 위 여백을 줄여주세요` `휴대폰을 시계 방향으로 돌려주세요` `조금 밝은 곳으로 이동해보세요` + 방향 enum
- [ ] `autoShutterReady`: matchScore ≥ 임계(기본 0.75) AND 흔들림<임계 AND 눈 감김 확률<0.3 — 유지 시간 판정은 A의 컨트롤러 담당
- [ ] **`guide_config.json`**: 모든 임계값·가중치·유지 시간 외부화(현장 튜닝용) — 기본값 명시
- [ ] 시나리오 단위 테스트 6종: 기울임→수평 안내 / 좌측 치우침→이동 / 과다 접근→거리 / headroom 과다→여백 / 역광→밝기 / 전부 충족→안내 없음+ready
- 완료 기준: 테스트 6종 통과 + 실기기에서 안내 깜빡임 없음(A와 저녁 판정)

### 3-2. 자동 촬영 판정 지원

- [ ] A와 함께 실기기에서 임계값 1차 튜닝(30분) — ready 오발동/불발동 케이스 기록
- [ ] **오후 6시 공동 판정**: 자동 촬영 불안정 시 §0.4 규칙 적용 결정
- 완료 기준: 판정 회의록 1줄 기록(안정/병행 전환)

### 3-3. 서버: 편집 작업 큐 실구현 착수

- [ ] 업로드 수신(multipart) → **EXIF 위치 정보 스트립** → `storage/inputs/` 저장 → `edit_jobs`/`edit_job_files` 기록
- [ ] 워커 프로세스: `edit_jobs` 폴링(status='queued', 1초) → 순차 처리(동시 1건) → 상태 갱신(processing→validating→done)
- [ ] 더미 처리부를 GenerativeEditProvider 호출로 교체할 자리 마련(인터페이스 연결)
- 완료 기준: 업로드→큐→상태 전이→더미 결과까지 로그로 추적 가능

---

## Day 4 — 편집 파이프라인이 진짜가 된다

**당일 데모 완료 기준: 원본·기본 보정·스타일 보정 비교가 가능하다(A 주도 — B는 진단 모듈·서버 실구현).**

### 4-1. ProblemDiagnoser.kt (순수 Kotlin — 정오까지 A에게 전달)

- [ ] 입력: 이미지 비트맵(축소본) + FrameFeatures(있으면) / 출력: `List<Problem>` — `TILT(각도)`, `UNDEREXPOSED(EV추정)`, `OVEREXPOSED`, `BLUR_SUSPECT(라플라시안 분산)`, `EXCESS_MARGIN`, `BACKLIGHT`
- [ ] 각 Problem에 사용자 표시 문구 포함: "5.2° 기울어져 있어요" — 전문 용어 금지
- [ ] 단위 테스트: 문제 유형별 샘플 이미지 6장 → 기대 진단
- 완료 기준: 테스트 통과, A의 사진 살리기 화면에 진단 칩 표시

### 4-2. 객체 제거 파이프라인 (생성 기능 1순위 — 실구현)

- [ ] ComfyUI 워크플로 JSON 템플릿: LaMa 객체 제거(마스크 입력), 파라미터 주입 함수
- [ ] **자동 마스크 생성**: 서버 측 인물 감지(MediaPipe)로 "주 피사체가 아닌 사람" 후보 마스크 산출 + 클라이언트 수동 마스크 병합 지원
- [ ] 큰 영역(프레임의 15% 초과) 제거 시 FLUX.1 Fill로 승격하는 분기
- [ ] 워커에 연결: `remove_objects` operation 처리 → 결과 후보 2개(시드 2개) 생성
- 완료 기준: 행인 있는 테스트 사진 5장 중 4장 이상에서 육안 합격 결과

### 4-3. 결과 검증기

- [ ] InsightFace 임베딩: 편집 전후 주 피사체 얼굴 거리 계산, **임계 초과 시 해당 후보 폐기**(임계 초기값은 동일인 테스트 셋으로 캘리브레이션 — 오전 30분)
- [ ] 얼굴 보호 마스크: 편집 마스크와 얼굴 박스 교차 시 얼굴 영역 제외(팽창 마진 10%)
- [ ] 휴리스틱 검사: 결과 인물 수 ≠ 기대 인물 수 → 폐기, 극단 색상 변화(히스토그램 거리) → 폐기
- [ ] 전 후보 폐기 시: `status='fallback'` + failReason 기록 (앱은 기본 보정 유지 — A와 계약된 동작)
- [ ] 임베딩은 **메모리에서만 사용 후 폐기** — DB·파일 저장 금지(validation_json에 거리값만)
- 완료 기준: 얼굴이 바뀐 결과가 후보에 포함되지 않음(의도적 변형 샘플로 검증)

### 4-4. 보관 정책 구현

- [ ] job 종료 시 입력 파일 `purge_after=now`, 결과는 `delivered_at`+24h
- [ ] 삭제 배치(1분 주기): purge 대상 파일 삭제 + `purged_at` 기록
- 완료 기준: job 완료 1분 후 `storage/inputs/`가 비어 있음을 확인하는 테스트 스크립트 통과

---

## Day 5 — 레퍼런스 분석 완성 + 생성 실서비스 전환

**당일 데모 완료 기준: 레퍼런스 안내 또는 생성 복구 중 최소 하나가 실서버로 동작한다.**

### 5-1. POST /references/analyze 완성

- [ ] Day 2-3 분석 함수를 API로 노출: multipart 수신 → 분석 → 응답(기능명세서 §10.2 스키마: analysis + targetComposition + colorTarget) → **임시 파일 즉시 삭제**(DB 기록 없음)
- [ ] 응답 시간 5초 이내(초과 시 분석 해상도 축소), 타임아웃·비인물 사진(인물 0명) 응답 처리
- [ ] targetComposition 변환: 레퍼런스의 인물 배치·비율 → StyleTarget 형식(A의 GuideEngine이 그대로 소비)
- [ ] colorTarget 변환: 팔레트·색온도 → A의 스타일 단계 파라미터 매핑표
- 완료 기준: A의 레퍼런스 모드에서 분석→오버레이→안내가 실동작(저녁 통합 테스트)

### 5-2. /edit-jobs 실서비스 전환

- [ ] 더미 응답 제거(`dummy:true` 삭제), 실제 파이프라인 연결 확인
- [ ] 진행 상태 세분화: `progress_stage` 갱신(removing→validating), 폴링 응답에 포함
- [ ] (여력 시) 여백 확장 operation: FLUX.1 Fill 아웃페인팅 — 상한 원본의 30%, 방향별(top/left/right)
- [ ] 동시 요청 방어: 디바이스당 진행 중 job 1개 제한(초과 시 409)
- 완료 기준: 실기기에서 업로드→처리→후보 2개 수신→"AI 생성 보완" 뱃지 표시까지 관찰

### 5-3. (오후 6시) A와 공동 판정

- [ ] 생성 안정성 판정: 연속 5회 요청 중 4회 이상 합격 결과 → 유지 / 미달 → **§0.4 규칙: 기능 플래그로 숨기고 기본 보정 데모 확정**
- [ ] 판정 결과 기록

---

## Day 6 — 신뢰성·개인화 마감

**당일 데모 완료 기준: 네트워크·생성 실패 상황에서도 기본 보정 결과가 항상 제공된다.**

### 6-1. ProfileEngine.kt (순수 Kotlin — 정오까지 A에게 전달)

- [ ] 온보딩 프로필 생성: 카드 특성 벡터(`cards.json`) 가중 평균 + 차원별 확신도(분산 기반) → composition/color 분리 프로필
- [ ] 요약 문구 생성: 특성값→일상 언어 템플릿("밝은 자연광과 넓은 배경을 좋아하시네요")
- [ ] 추천 스타일: 프리셋 3종과 프로필 벡터 거리 → 정렬
- [ ] 피드백 반영: 5개 선택지 → 구도/색감 프로필 분리 반영(지수이동평균 α=0.3), "색감 별로"는 color만 조정
- [ ] 단위 테스트: 상이한 카드 선택 2세트 → 상이한 요약·추천 / "색감 별로" 2회 → colorTemperature 보정치 변화
- 완료 기준: 테스트 통과, A의 온보딩·피드백 화면에서 실동작

### 6-2. cards.json + 온보딩 카드 에셋 마감

- [ ] 카드 15~20장 최종 선정(Unsplash/Pexels 라이선스 확인 기록), 8개 차원 커버 매트릭스 표 작성
- [ ] 각 카드 특성 벡터 기입(§5.1 CardFeature 스키마) — 눈대중 아닌 기준표(밝기 히스토그램 실측 등)로
- 완료 기준: 커버 매트릭스에 빈 차원 없음, A에게 에셋+JSON 전달

### 6-3. 서버 신뢰성 마감

- [ ] 실패 대체 총점검: 워커 크래시 복구(processing 5분 초과 job → failed), GPU 미응답 타임아웃, 폴백 경로 재확인
- [ ] 요청 제한: 디바이스당 시간당 job 10건, 이미지 최대 해상도 4096px(초과 시 축소), 편집 영역 크기 제한
- [ ] 로그 정리: job 처리 시간·실패 사유 집계 스크립트(`scripts/job_stats.py`) — 데모 후 회고용
- [ ] API 키·자격 증명이 앱에 없는지 최종 확인(서버 환경 변수만)
- 완료 기준: 워커 강제 종료→재시작 테스트에서 job이 유실 없이 failed/재개 처리

### 6-4. 이벤트·피드백 저장 확인 (로컬)

- [ ] A와 함께: `feedback`·`session_guides`·`events` 로컬 기록이 쌓이는지 확인(데모 후 지표 산출용)
- [ ] 지표 추출 스크립트(로컬 DB 파일 → 첫 촬영 완료율·저장률·안내 개선율 계산) 준비
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

**끝까지 지키는 것:** presets 3종, FrameFeatureCalculator, GuideEngine, 레퍼런스 분석, 객체 제거 1기능, 얼굴 검증·폴백, 업로드 자동 삭제.

## 부록 B. A에게 전달하는 산출물 일정 (P1_Plan 부록 B와 동일 — 지연 시 즉시 공유)

| 시점 | 전달물 | 완료 조건 |
|---|---|---|
| Day 1 저녁 | presets.json 3종 + /edit-jobs 더미 + API 계약(OpenAPI) | A 앱에서 호출 성공 |
| Day 2 정오 | FrameFeatureCalculator.kt + 테스트 10케이스 | 테스트 전부 통과 |
| Day 3 정오 | GuideEngine.kt + guide_config.json + 시나리오 테스트 6종 | 테스트 전부 통과 |
| Day 4 정오 | ProblemDiagnoser.kt + 테스트 6케이스 | 테스트 전부 통과 |
| Day 5 정오 | /references/analyze 실서버 + /edit-jobs 실서버 | 실기기 왕복 성공 |
| Day 6 정오 | ProfileEngine.kt + cards.json + 카드 에셋 | 테스트 통과 + 커버 매트릭스 완성 |

## 부록 C. GPU·모델 준비물 (Day 1 오전에 확인)

- [ ] GPU 서버 VRAM 용량 확인 → FLUX.1 Fill 원본/양자화 결정 기록
- [ ] 모델 다운로드: LaMa, FLUX.1 Fill [dev](비상업 라이선스 — 상용 전환 시 교체 대상임을 provider_decision.md에 명기), InsightFace 모델
- [ ] ComfyUI headless 기동 스크립트, FastAPI에서 호출 가능한 내부 주소 확인
- [ ] 비교용 Gemini API 키 발급(무료 티어) — 비교 테스트 후 사용 여부 결정
