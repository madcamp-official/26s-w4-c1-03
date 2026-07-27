# 감도(GAMDO) 코드 리뷰 보고서

- **작성일**: 2026-07-27
- **대상**: `main` 브랜치 `e2bca9c` 기준 앱(`app/`) + 서버(`gamdo-server/`) 전체
- **대조 문서**: `P1_Plan_1.md`, `P2_Plan_1.md`, `AGENTS.md`, `docs/감도_GAMDO_DB스키마_v2.0.md`, `docs/감도_기능명세서_v1.0_3.md`, `docs/감도_유저플로우_구현상태.md`
- **방법**: 10개 각도(신규 코드 라인 스캔 / 회귀 감사 / 데이터 흐름 추적 / 언어별 함정 / P2 모듈 계약 / 서버 대조 / DB 스키마 정합 / 기능명세 정합 / 잔여 작업 추출 / 관례·정리)로 병렬 조사 후 별도 검증 에이전트로 재현 확인

---

## 0. 한 줄 결론

가장 큰 문제는 개별 버그가 아니라, **플랜 문서가 "완료·실기기 확인"으로 기록한 두 개의 대표 데모 흐름이 현재 앱에서 도달 불가능하다**는 점이다. 시나리오 A(레퍼런스 따라 찍기)는 진입점이 처음부터 없고, 시나리오 B(사진 살리기)는 커밋 `daafdb7`이 UI를 삭제했다. 그 커밋 메시지 자체가 "진입점만 없어졌다"고 적고 있으나 플랜의 체크박스는 갱신되지 않았다.

### 검증 환경 한계 (먼저 읽을 것)

- `gradlew`가 실행 비트 없이(`git ls-files -s` → `100644`) 커밋되어 있어 `./gradlew`가 **exit code 0으로 실패**한다. CI/백그라운드에서는 아무것도 컴파일하지 않고 초록으로 보인다.
- 이 머신에는 JDK 25.0.2만 설치되어 있고 Gradle 8.9는 JDK 22까지만 지원한다. `JAVA_HOME`이 비어 있고 `local.properties`가 없다.
- 따라서 **Android 빌드·테스트는 이 환경에서 재현 검증하지 못했다.** 플랜에 적힌 "N tests / 0 failed" 주장은 현재 재현 불가다. 아래 앱 관련 결함은 전부 정적·호출그래프 수준의 근거이며, 그것으로 충분한 종류의 결함들이다.
- 서버는 `gamdo-server/app/main.py:24`가 gitignore된 `storage/results`에 임포트 시점 StaticFiles를 마운트해서 **클린 체크아웃에서 pytest가 0건 수집으로 중단**된다. `mkdir -p storage/{results,inputs,tmp}` 후에는 36 passed.

---

## 1. 확인된 결함 (심각도 순, 20건)

각 항목의 `상태`는 `확인`(코드 경로 추적 또는 실행으로 재현 완료) / `유력`(경쟁 조건 등 재현은 안 했으나 근거가 명확) 구분이다.

### 1. 자동 고정 레이아웃이 스타일 프리셋 가이드를 영구 대체 — `확인`

[app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt:225](app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt#L225)

`selectedId?.let { return LayoutTemplateCatalog.resolve(it) }`가 이후 `choose()`를 아예 호출하지 않게 단락시키고, `choose()`(:247)는 얼굴이나 포즈가 있는 프레임이면 무조건 `PORTRAIT_PERSON`을 반환한다. 프로덕션에서 `StyleTarget.layoutTemplateId`는 항상 `null`이라 이 자동 경로가 **모든 세션에서** 동작한다.

12fps에서 5프레임 중 3프레임이면 래치되고, 그 순간 [CameraOverlay.kt:142](app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt#L142)의 `?.takeIf { it.visible && data.layoutGuide?.fixedLayout == null }`가 브래킷·실루엣·발 마커·외곽선을 세션 끝까지 제거한다.

**실패 시나리오**: 카메라를 사람 한 명(D9의 주 피사체)에 향하면 약 250ms 뒤 프리셋의 `subjectPosition`/`subjectScaleRange` 가이드가 사라지고 6개 프리셋 모두 동일한 고정 `0.22–0.88` 사각형으로 바뀐다. `reset()` 외에는 해제 수단이 없고 재탐색 버튼도 없다.

### 2. edit-job 조회에 소유권 검사 없음 (IDOR) — `확인 (라이브 재현)`

[gamdo-server/app/routes/edit_jobs.py:333](gamdo-server/app/routes/edit_jobs.py#L333)

`def get_edit_job(job_id: str, _: str = Depends(require_device_id))`가 의존성을 `_`에 바인딩해 버리고, `get_job(job_id)`는 `device_uuid`로 스코프되지 않는다.

**실패 시나리오 (실제 확인)**: deviceA로 잡 생성 후 `X-Device-Id: ATTACKER-OTHER-DEVICE`로 `GET /api/v1/edit-jobs/job_alpha` 요청 시 200과 전체 본문 및 `/files/<name>` URL 반환. 그 URL은 `main.py:24`가 인증 없이 서빙한다. 공격자의 폴링이 `mark_results_delivered()`(:350)까지 실행시켜 피해자의 24시간 파기 타이머를 시작시킨다. D8 프라이버시 계약 정면 위반.

경로 순회 자체는 안전하다(`../` → 404). 문제는 인가다. 또한 라우트 표면이 D5의 "API 4개뿐"과 달리 `/health`와 `/files` 마운트를 포함해 6개다.

### 3. `UnboundLocalError`가 워커 프로세스를 영구 종료 — `확인 (재현)`

[gamdo-server/app/comfyui_provider.py:166](gamdo-server/app/comfyui_provider.py#L166)

`candidates.append(...)`가 두 for 루프 밖으로 들여쓰기가 빠져 루프 누출 변수 `path`/`index`를 읽는다.

**재현 완료**: `_download_outputs({}, 'pid', 1, 0)`과 `_download_outputs({'9': {'gifs': []}}, 'pid', 1, 0)` 모두 `UnboundLocalError: cannot access local variable 'path'`를 던진다. `_wait_for_history`는 `item.get("outputs")`가 truthy가 되는 즉시 반환하므로(:97) gifs/latents를 내보내는 노드가 이 줄에 도달한다. `worker.py:59`는 `(ProviderNotReady, OSError, TimeoutError, ValueError)`만 잡는데 `UnboundLocalError`는 `NameError` 계열이고, `worker.py:134`에는 try/except가 없어 `run_forever`가 영구 종료된다. 이후 모든 잡이 fallback 전이도 없이 `queued`에 영원히 머물러 D18을 위반한다.

부수적으로 candidate는 항상 하나만 등록되어 :153의 `len(candidates) >= result_count` 가드는 죽은 코드다.

### 4. 사진 살리기 / 생성 복구 흐름에 UI 진입점 없음 — `확인`

[app/src/main/java/com/gamdo/app/data/network/GamdoApiClient.kt:157](app/src/main/java/com/gamdo/app/data/network/GamdoApiClient.kt#L157)

`createEditJob`, `getEditJob`, `downloadResult`, `JobTimeoutPolicy`, `recordDownloadedEditResult` 모두 프로덕션 호출자가 0이다(레포 전체에서 `androidTest`만 호출). 커밋 `daafdb7`이 마스크 드래그와 잡 파이프라인을 삭제했고(`ResultScreen.kt`에서 377줄), 커밋 메시지 자체가 "진입점만 없어졌다"고 적고 있다.

`P2_Plan_1.md:90-94`는 여전히 `- [x] 사진 살리기 모드에서 사용자가 지울 영역을 드래그해 <!-- SM-G970N에서 확인 -->`로 남아 있다.

**결과**: 시연 시나리오 B("망한 사진 살리기") 수행 불가. CAMP-2/ComfyUI/LaMa/InsightFace 백엔드 전체가 APK에서 도달 불가. `edit_results_local`이 영구히 비어 B의 지표 스크립트에 데이터가 없다. P1 §0.5가 경고한 `📱` 만료가 정확히 이 상황이다.

### 5. 레퍼런스 촬영 진입점 없음 → GPS 제거 가드 미실행 — `확인`

[app/src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt:151](app/src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt#L151)

`referenceLayer`/`referenceEntry`/`demoControls`가 기본값 빈 컴포저블로 선언되어 있고, [GamdoNavHost.kt:71-76](app/src/main/java/com/gamdo/app/ui/navigation/GamdoNavHost.kt#L71-L76)이 이 셋 중 무엇도 넘기지 않고 `CameraScreen`을 만든다. 셋 다 빈 화면으로 렌더되고 상단 바에는 폭 0의 빈 공간만 남는다.

main 소스에서 `referenceRepository`는 [AppContainer.kt:58](app/src/main/java/com/gamdo/app/data/AppContainer.kt#L58)의 생성 한 곳에만 등장하고 `resolve()`/`resolveBytes()`/`activate()`는 호출처가 0이다. `ExifSanitizer.sanitizeFile`은 `ReferenceRepository.kt:85`를 통해서만 도달 가능하므로 **D8-5 GPS 제거 가드가 출시 앱에서 한 번도 실행되지 않는다**. `ExifSanitizerTest`는 트래픽이 지나가지 않는 경로에서 통과 중이다.

**결과**: P1이 최종 산출물로 지목한 두 시연 시나리오 중 A는 진입점이 없고 B는 삭제되어, 현재 앱은 어느 쪽도 엔드투엔드로 시연할 수 없다.

### 6. `conditions_json`을 읽는 유일한 소비자가 죽은 코드 — `확인`

[app/src/main/java/com/gamdo/app/edit/ResultEditController.kt:113](app/src/main/java/com/gamdo/app/edit/ResultEditController.kt#L113)

`val conditions = CaptureConditions.parse(conditionsJson)`가 유일한 프로덕션 리더인데, 이것이 들어 있는 `ResultEditController`의 팩토리 `rememberResultEditController()`(:289)는 어디서도 호출되지 않는다. [ResultScreen.kt:135](app/src/main/java/com/gamdo/app/ui/result/ResultScreen.kt#L135)는 색상 패스인 `QuickFilterEditor.apply(it, selectedFilter, adjustments)`만 렌더한다.

**실패 시나리오**: 폰을 8° 기울여 촬영하면 `ShutterSnapshot`이 `{"tiltDeg":-8.0,...}`를 SQLite에 정확히 쓰지만, 사진을 열면 LUT만 적용된다. §4-1 수평 보정은 항등 변환이고 기하/광학 단계는 실행되지 않는다. `LocalEditorPlanTest`, `GeometryPlanTest`, `RenderMatrixTest`, `RenderBudgetTest`가 모두 사용자가 도달할 수 없는 코드에 대해 통과 중이다. `BeforeAfterSlider.kt:66`과 `ResultTabs.kt:117`도 죽은 파일이며, P1 부록 A는 이 슬라이더를 "끝까지 지키는 것"으로 명시하고 있다.

### 7. 진단 칩 6종 전부 죽음, `problems_json`은 항상 `"[]"` — `확인`

[app/src/main/java/com/gamdo/app/detect/ProblemDiagnoser.kt:58](app/src/main/java/com/gamdo/app/detect/ProblemDiagnoser.kt#L58)

main 소스에서 `class ProblemDiagnoser(`는 자기 선언 한 곳에만 나타나고, `diagnose()`의 유일한 호출자는 `app/src/test/.../DiagnosisReachabilityTest.kt`다. 이 테스트의 KDoc은 "프로덕션 체인을 실행한다"고 적혀 있지만 더 이상 사실이 아니다.

`daafdb7`이 칩 행을 삭제해서 P1 §0.5가 말한 3종(TILT/EXCESS_MARGIN/BACKLIGHT)뿐 아니라 UNDEREXPOSED/OVEREXPOSED/BLUR_SUSPECT도 함께 죽었다. `ShutterSnapshot.kt:58-66`은 `problemsJson`을 설정하지 않으므로 `CaptureRepository.kt:72`의 기본값 `"[]"`가 모든 행에 저장된다.

**결과**: 기울고 역광이고 흔들린 사진을 찍어도 아무 피드백이 없는데, 이를 막기 위해 만든 가드 테스트는 초록으로 남는다.

### 8. 홈 화면에서 D2/D13/D17 재론 불가 규칙 4건 위반 — `확인`

[app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt:121](app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt#L121)

- **D2** "고정 레이아웃 슬롯은 채움·비움 상태나 색상 변화를 표시하지 않는다" → :122-124가 `FILLED -> Sage`, `DETECTING -> White α0.72`, `EMPTY -> White α0.9`로 매핑. 결정이 기록된 `668f371` **이후**인 `421f5de`에서 출시됨.
- **D2/D17** 행동 지시 문구 금지 → :213-216이 `Text("피사체를 화면에 보여주세요")` / `"피사체를 잠시 유지해주세요"`를 `BuildConfig.DEBUG` 게이트 없이 TopCenter에 렌더.
- **D17** "작고 방해되지 않는 로딩 표시를 계속 노출" → `ui/` 전체에서 `CircularProgressIndicator` 검색 결과 0건. 금지된 지시 문구가 그 자리를 대신하고 있다.
- **D13** 우측 상단 레이아웃 버튼 + 재탐색 → `layoutTemplateId`는 테스트에서만 할당되어 그런 컨트롤이 존재하지 않는다.

`AGENTS.md` §7-3은 D2·D8을 재론 불가로 지정하고 있으며, `CameraOverlay.kt:62` KDoc은 금지된 문구 바로 위에서 "there is still no arrow, match gauge, or auto-capture"라고 주장한다.

### 9. 전면 카메라 미러가 가이드 기하를 좌우 반전 — `확인`

[app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt:301](app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt#L301)

`mapNormalized` 안의 `val fx = if (data.mirror) 1f - nx else nx`가 `mapRect`가 건드리는 모든 것(`guide.targetFrame`, `silhouetteBounds`)을 뒤집는데, `AlignmentEngine.kt:159`는 미러링되지 않은 `target.subjectAnchorX`에 사각형을 배치하고 `MatchScoreCalculator.kt:62-66`은 `"third_left" -> 1f/3f`를 그대로 통과시킨다. `guide/` 전체에서 전면 렌즈는 한 번도 언급되지 않는다.

**실패 시나리오**: `presets.json`에서 `candid_feed`와 `night_street`가 `third_left`, `soft_film`이 `third_right`다. 전면 렌즈 + `candid_feed`: x=1/3로 저작된 브래킷이 2/3에 그려지고, 사용자가 거기 맞추면 `CameraController.kt:211`의 `if (isFront) bitmap = bitmap.mirroredHorizontally()`가 저장 픽셀을 다시 뒤집어 `third_left` 프리셋인데 피사체가 오른쪽 1/3에 있는 사진이 나온다. `SubjectProjection.kt:101`은 미러링을 명시적으로 처리하고 있어, 가이드 경로만 빠뜨린 것이다.

### 10. 슬롯 매처의 채점·상태 머신이 세 가지로 틀림 — `확인`

[app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt:147](app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt#L147)

**(a) 겹침이 슬롯 면적으로만 정규화됨** — :147의 `intersection / slotArea`는 슬롯 밖으로 삐져나온 부분에 페널티를 전혀 주지 않는다. `PORTRAIT_PERSON`의 `RectN(0.22,0.10,0.78,0.88)`에 대해 `(0,0,1,1)` 사람 박스가 `overlap=1.0`, `centerError=0.01 ≤ 0.14`로 FILLED가 되고, 피사체가 프레임 가장자리에 잘린 촬영에 `aligned=true`가 기록된다.

**(b) 점수 하한 없는 그리디 배정** — :104의 `.maxByOrNull { score(slot, it) }` 후 `available.remove(it)`. `DRINK_PAIR`에서 컵 하나가 오른쪽 슬롯에 완벽히 놓여 있어도 `drink_left`가 먼저 처리되어 `overlap=0`으로 그 컵을 가져가고, `drink_right`는 영원히 채워질 수 없다.

**(c) `filled`가 해제되지 않음** — :115가 `filled[slot.id] = true`를 쓰고 `false`를 쓰는 곳이 없는데, :117은 일치 프레임 하나만으로 `misses`를 0으로 초기화한다. 60프레임 부재 후 스쳐 지나간 한 프레임이 슬롯에 아무것도 없는 상태로 4프레임(~330ms) FILLED를 재무장시킨다. 부수적 off-by-one: :116이 :117의 증가 전에 `misses`를 읽어 실제 가림 허용치는 4가 아니라 5프레임이다.

### 11. 객체 검출 4FPS로 D15 하한 미달 + D12 안정화 게이트 무력화 — `확인`

[app/src/main/java/com/gamdo/app/detect/Detectors.kt:35](app/src/main/java/com/gamdo/app/detect/Detectors.kt#L35)

`refreshEveryFrames = 3`에 유일한 분석기가 `targetFps = 12`([CameraScreen.kt:311](app/src/main/java/com/gamdo/app/ui/camera/CameraScreen.kt#L311))이므로 12÷3 = **4 FPS**로, D15의 8 FPS 하한의 절반이다. `detect()`는 프레임 1,3,6,9…에서만 갱신하고 나머지는 `return lastResult`이므로 임의의 5프레임 창에서 실제 검출은 최대 2개인데 `GuideCandidateStabilizer`는 3회 확인을 요구한다 — D12의 "최근 5프레임 중 3프레임" 게이트가 **재생된 복제본**으로 충족되고 있다.

더해서 [SceneRecognitionPolicy.kt:26](app/src/main/java/com/gamdo/app/detect/SceneRecognitionPolicy.kt#L26)의 `val area = mask?.areaRatio ?: return false`가 세그멘테이션 마스크 없는 객체를 전부 탈락시키고 :27이 하드코딩된 4개 집합 밖 레이블을 전부 거부한다. 둘 다 D12("물체 종류는 선택 정보이며 인식 통과 조건이 아니다", "세그멘테이션은 외곽선 정밀화용이며 필수가 아니다")를 뒤집는다. **D3/D12가 기술하는 일반 레이아웃 경로가 도달 불가이며, 이것이 온디바이스 인식률 저하의 유력한 근본 원인이다.**

### 12. 파괴적 마이그레이션이 로컬 14개 테이블을 전부 지움 — `확인`

[app/src/main/java/com/gamdo/app/data/AppContainer.kt:34](app/src/main/java/com/gamdo/app/data/AppContainer.kt#L34)

로컬이 곧 데이터 원천인 설계(DDL §6: "로컬이 원천이므로 앱 삭제 = 데이터 전체 소실")에서 `.fallbackToDestructiveMigration()`을 `version = 1`, Migration 객체 0개로 쓰고 있다.

**실패 시나리오**: 시연 직전 nullable 컬럼 하나만 추가하면(AGENTS.md §7 규칙 2가 **명시적으로 허용**하는 변경) 다음 실행에서 `style_profile`, 모든 `captures` 행, `capture_edit_stack` 전체, `sessions`/`session_guides` KPI 증거가 사라진다. 크래시도 로그도 프롬프트도 없이, 누군가 테이블이 0행인 걸 발견할 때까지 보이지 않는다. DDL §9-4는 "마이그레이션은 항상 additive"를 요구한다.

### 13. 서버 보존 기간 결함 3종 — `확인 (재현)`

[gamdo-server/app/worker.py:124](gamdo-server/app/worker.py#L124)

**(a)** `self.database.mark_file_purged(row["id"])`가 무조건 실행된다. `save_exif_stripped_input`은 SERVER_ROOT 기준 상대 경로를 저장하는데(`storage.py:33`) `purge_once`는 프로세스 CWD 기준의 맨 `Path(row["storage_path"])`로 해석한다. **재현**: `purge_once()`가 1을 반환하고 `purged_at`을 설정했지만 파일은 디스크에 그대로 남았고, `expired_files()`는 `purged_at IS NULL`로 필터링하므로 다시는 조회되지 않는다 — 사용자 사진은 영구 보존되는데 감사 기록은 D8 준수를 주장한다.

**(b)** `db.py:220`은 `purge_after`를 `mark_results_delivered` 안에서만 설정하고 이 함수는 클라이언트 폴링 성공 시에만 호출된다. 첫 폴링 전에 앱을 종료하면 생성된 사진이 영구 보존되고, 이후 7일 스윕이 DB 행만 삭제해 파일을 고아로 만든다.

**(c)** `db.py:233`이 `processing_timeout`에 `status = 'failed'`를 설정하고 `edit_jobs.py:353`이 이를 그대로 반환한다. 다른 모든 종료 경로는 `'fallback'`을 쓴다 — D8("실패 시 기본 보정 폴백, 사용자에게 실패 미노출")과 D18 위반. 이 경로만 `schedule_input_purge`도 호출하지 않는다.

### 14. 세그멘테이션 타임아웃이 태스크를 취소하지 않음 — `유력`

[app/src/main/java/com/gamdo/app/detect/MlKitDetectors.kt:159](app/src/main/java/com/gamdo/app/detect/MlKitDetectors.kt#L159)

`Tasks.await(segmenter.process(image), 180, TimeUnit.MILLISECONDS)`가 TimeoutException을 던지고 반환하는데 `task.cancel()`이 없고 `runCatching`이 예외를 삼킨다. `Detections.kt:100-110`은 복사 없이 `InputImage.fromMediaImage(media, rotation)`로 참조 기반 InputImage를 만들고, `FrameAnalyzer.kt:48-49`의 `finally { image.close() }`가 media Image를 ImageReader 큐로 반환해 다른 내용으로 다시 채운다.

피사체 세그멘테이션은 번들되지 않은 `play-services-mlkit-subject-segmentation 16.0.0-beta1`이므로 180ms 초과는 이 타임아웃이 존재하는 바로 그 상황이고, 12fps 주기에서 초당 약 2회 발생한다. 증상은 다음 패스의 깨진 마스크, ML Kit 실행기 내부의 `IllegalStateException: Image is already closed`, 또는 회수된 버퍼에 대한 네이티브 읽기다. 나머지 세 검출기는 타임아웃 없이 `Tasks.await`를 호출해 구조적으로 안전하다.

### 15. 피사체가 나가도 마지막 실루엣이 계속 그려짐 — `확인`

[app/src/main/java/com/gamdo/app/detect/Detectors.kt:74](app/src/main/java/com/gamdo/app/detect/Detectors.kt#L74)

`delegate.detect(frame)?.let { lastResult = it }` — null은 절대 캐시를 비우지 않는다. 자체 `reset()`(:81-84)은 테스트에서만 호출되고, `SceneDetector.reset()`(:144)은 stabilizer만 건드리며 `CameraViewModel.onAnalyzerDetached()`는 여기까지 도달하지 않는다.

null은 흔하다: `SegmentationMaskReducer.kt:25`의 `if (occupiedCount < 6) return null`이 말 그대로 피사체가 프레임을 벗어난 경우이고, 모든 타임아웃/미다운로드 모델 경로도 마찬가지다. 이 낡은 마스크는 `SceneProposalEngine.kt:93`의 `val subjectBox = segmented?.bounds ?: personBox ?: objectCandidate?.box`에서 실시간 검출보다 우선하고 :104가 신뢰도를 0.35 게이트 위로 유지한다.

**실패 시나리오**: 사람을 1초간 비춘 뒤 빈 벽으로 팬하면 외곽선이 빈 장면 위에 계속 그려지고 `AlignmentEngine`은 존재하지 않는 피사체에 대해 `visible=true`를 계속 보고한다.

### 16. 자동 선택과 슬롯 매칭의 자격 기준이 모순 — `확인`

[app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt:241](app/src/main/java/com/gamdo/app/guide/FixedLayoutGuide.kt#L241)

:241의 `val drinks = detections.count { it.category == GuideObjectCategory.DRINKWARE }`는 원본 검출을 세는 반면, :103의 match는 `isReliable && confidence >= 0.35f`를 요구한다. 객체의 `isReliable`은 `isGuideEligible && mask != null`(`SceneProposalEngine.kt:141`)이라 세그멘테이션 없이 객체 검출이 돌면 항상 false다.

**실패 시나리오**: 테이블 위 컵 3개, 마스크 없음. `choose()`는 `drinks=3`을 보고 3프레임 뒤 `DRINK_TRIO`로 `selectedId`를 확정하는데 `selectedId`는 `reset()` 외에는 절대 해제되지 않고(:225가 이후 모든 프레임을 단락), 매처는 세 검출을 전부 걸러내 모든 슬롯이 세션 내내 EMPTY로 남는다. 동시에 `CameraOverlay.kt:142`가 레거시 가이드 전체를 억제하므로 사용자에게는 절대 초록으로 변하지 않는 흰 상자 3개만 남고 스타일을 바꾸기 전에는 복구가 불가능하다.

### 17. 사람 슬롯 신뢰도가 눈 뜸 확률에서 채워짐 — `유력`

[app/src/main/java/com/gamdo/app/detect/SceneProposalEngine.kt:129](app/src/main/java/com/gamdo/app/detect/SceneProposalEngine.kt#L129)

:100의 `detectorConfidence`는 `pose?.averageInFrameLikelihood ?: faces.maxOfOrNull { it.leftEyeOpenProbability ?: 0f } ?: 0f`다. 얼굴만 잡히는 경로(포즈 검출 실패 — 상반신/역광 구도에서 흔함)에서는 이 값이 피사체의 눈 뜸 확률이 되고, 얼굴 분류기가 꺼져 있거나 피사체가 눈을 감으면 `0f`다.

인물 스타일에서 얼굴 하나 + `pose == null`이면 `SlotDetection("person", PERSON, box, confidence = 0f, isReliable = false)`가 나온다. `choose`(`FixedLayoutGuide.kt:247`)는 `any { it.category == PERSON }`만 확인해 `PORTRAIT_PERSON`을 영구 확정하지만 `match()`는 두 조건 모두에서 거부한다. 슬롯은 영원히 EMPTY, 레거시 가이드는 억제, 모든 촬영에 `aligned=false`. **눈 깜빡임만으로도 정상 동작하던 인물 가이드가 꺼질 수 있다.**

### 18. `reset()`이 분석 스레드와 동기화 없이 상태를 비움 — `유력`

[app/src/main/java/com/gamdo/app/ui/camera/CameraViewModel.kt:157](app/src/main/java/com/gamdo/app/ui/camera/CameraViewModel.kt#L157)

`setStyleTarget`은 `LaunchedEffect(activePreset)`(`CameraScreen.kt:238`, 메인 디스패처)에서 호출되고 그 안에서 `sceneGuideCoordinator.reset()`이 실행되는데, 동시에 `onFrameAnalyzed` → `FixedLayoutSlotMatcher.match` / `AutoLayoutTemplateResolver.resolve`가 `analysisExecutor`에서 돈다. 새 상태는 평범한 `mutableMapOf`와 `ArrayDeque`로 synchronized도 volatile도 아니다.

12fps에서 사용자가 새 스타일 프리셋을 탭하면 `history.clear()`(LinkedHashMap 구조 변경)가 분석 스레드의 `history.getOrPut(slot.id) { ArrayDeque() }`와 인터리브되거나, `AutoLayoutTemplateResolver.history.clear()`가 :229의 `history.count { it == candidate }`와 인터리브되어 CameraX 분석기 콜백 안에서 `ConcurrentModificationException`이 발생하거나, 의도한 콜드 리셋을 살아남은 `filled`/`selectedId`가 조용히 되살아난다.

### 19. `effectiveVisible`이 true로 고정되어 KPI가 죽음 — `확인`

[app/src/main/java/com/gamdo/app/ui/camera/CameraViewModel.kt:209](app/src/main/java/com/gamdo/app/ui/camera/CameraViewModel.kt#L209)

`val effectiveVisible = fixedLayout != null || projection.visible`인데 `AutoLayoutTemplateResolver`가 `selectedId`를 세션 내내 고정하므로 `fixedLayout`이 이후 모든 프레임에서 non-null이다.

`CameraScreen.kt:288-297`은 `lastFrame.map { it?.visible }.filterNotNull().distinctUntilChanged()`를 수집해 `GUIDE_TARGET_FRAME` / `GUIDE_HIDDEN` 행을 기록한다. 일반 세션의 3프레임쯤에서 레이아웃이 자동 선택되면 `visible`이 다시는 false로 돌아가지 않아 세션당 `GUIDE_TARGET_FRAME` 행이 정확히 하나만 기록되고 `GUIDE_HIDDEN`은 다시는 기록되지 않는다 — §3-3 표시/숨김 전환 지표가 조용히 100% 가시성을 보고한다.

### 20. 슬롯 채움에 모서리 반경 없고 EMPTY/DETECTING 색이 동일 — `확인`

[app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt:126](app/src/main/java/com/gamdo/app/ui/camera/CameraOverlay.kt#L126)

**(a)** `drawRoundRect`에 `cornerRadius`를 넘기지 않아 `CornerRadius.Zero`가 기본값이 되고, 각진 채움이 :131에서 그리는 22.dp 둥근 스트로크 밖으로 삐져나온다.
**(b)** `.copy(alpha = fixed.template.opacity)`가 상태별 알파를 덮어써서 EMPTY(White @ 0.9)와 DETECTING(White @ 0.72)이 둘 다 White @ 0.30이 된다 — 채움만으로는 두 상태를 구분할 수 없다.
**(c)** :131 스트로크에서 알파가 뒤집혀 있어 DETECTING(0.72)이 EMPTY(0.9)보다 흐리게 그려진다. 컵을 슬롯으로 옮기면 슬롯이 밝아지는 대신 눈에 띄게 **어두워졌다가** Sage로 점프해, 중간 피드백 상태가 퇴행처럼 읽힌다.

같은 블록의 :160(`&& it.fixedLayout == null`)과 :183은 :142의 `takeIf` 안에 중첩되어 무조건 true인 죽은 코드이며, 세 가드 중 하나는 다른 조건이었어야 한다는 신호다.

---

## 2. 플랜 문서 대조 결과

### 2-1. 완료로 표시됐으나 실제로는 삭제·미배선

| 문서 위치 | 주장 | 실제 |
|---|---|---|
| `P2_Plan_1.md:90-94` | 사용자 흐름 B 5항목 `[x]` + `<!-- SM-G970N에서 확인 -->` | `daafdb7`이 UI 삭제. 프로덕션 호출자 0 |
| `P2_Plan_1.md:84` | `edit_results_local` 기록 `[x]` | 유일 writer `recordDownloadedEditResult` 호출자 0 → 테이블 영구 공백 |
| `P1_Plan_1.md:73` | "병합으로 해소된 것"에 §5-3 생성 복구·진단 칩 화면 | 두 항목 모두 그 문단 작성 후 `daafdb7`이 삭제 |
| `P1_Plan_1.md:322` | `[x] 📱` 비파괴 | 주석 자체가 "쓰기 전용, 복원 경로 없음" 인정 |
| 2026-07-25 감사 | M13-01 비교 슬라이더 "실기기 확인" | `BeforeAfterSlider.kt:66` 호출자 0 |

`P1_Plan_1.md` §0.5는 "체크박스와 충돌하면 이 절이 최신"이라고 플랜이 지정한 절인데, **현재 가장 부정확한 절**이다.

### 2-2. 반대로 문서가 진척을 과소평가한 지점

- §0.5가 1순위 블로커로 지목한 `CameraScreen.kt:321`의 `saveCameraCapture(bitmap)` 한 인자 호출은 **이미 수정됨**. 현재 `CameraScreen.kt:465`가 `buildCaptureSnapshot(...)` 전체 스냅샷을 넘긴다.
- §0.5의 `capture_edit_stack` `step_type`·PK 접두사 위반도 **해소됨**. `EditStepType`이 DDL §3.9 CHECK의 진부분집합이고 ID는 `"stk_" + Ulid.generate()`다.
- 따라서 `P1_Plan_1.md:302`, `:303`은 `[ ]`로 남아 있지만 실제로는 완료 상태다(`GuideKpiRepository.recordGuideShown`이 `CameraScreen.kt:295,297`에서 호출됨).
- 체크박스 집계도 문서가 말하는 `[x]`39 / `[~]`9 / `[ ]`46이 아니라 실제로는 43/9/43이다.
- 온보딩 스와치도 `ProfilePalette.swatches(...)`가 `OnboardingScreen.kt:174`에서 호출되어 동적이다. 하드코딩으로 남은 것은 요약 3줄뿐.

### 2-3. DB 스키마 v2.0 정합 — 통과한 부분

14개 테이블 전부 스키마와 동일한 이름으로 존재하고(`GamdoDatabase.kt:33-48`), **모든 컬럼의 이름·타입·nullability·기본값이 DDL과 일치**한다. 이름이 바뀌거나 빠지거나 추가된 컬럼은 없다. 얼굴 임베딩과 GPS 값은 어디에도 저장되지 않는다 — `ExifSanitizer`가 31개 `TAG_GPS_*` 태그를 업로드 전에 제거하고 `importFromGallery`는 재인코딩으로 원본 EXIF 블록을 버린다. §7 규칙 2의 프라이버시 조항은 깨끗하다.

---

## 3. 남은 개발해야 할 사항

### 3-1. 진입점 재배선 (실질 병목 3개)

1. **결과 화면에 §4-1 파이프라인 재연결** — `ResultEditController`/`LocalEditor`/`BeforeAfterSlider`/`ResultTabs`가 모두 완성되어 있으나 호출자가 0. 현재 화면은 `QuickFilterEditor`(색상 패스)만 사용.
2. **카메라 화면에 레퍼런스 진입점** — 이것이 없어서 D8-5 GPS 제거 가드가 출시 앱에서 한 번도 실행되지 않음. 사진 피커 → SHA-256 → 캐시 조회까지 연결 필요(`P1_Plan_1.md:352`). 이 항목이 열리기 전까지 :354, :359, :360, :362, :363과 §5-3(368-372) 전체가 도달 불가.
3. **사진 살리기 진입점** — 마스크 드래그와 잡 폴링 UI 복원.

### 3-2. AGENTS.md 규약 미구현

- **D13** 우측 상단 레이아웃 버튼 + 재탐색: `layoutTemplateId`가 테스트에서만 할당되므로 컨트롤 자체가 없음. 자동 선택이 한 번 래치되면 세션 내내 해제 불가.
- **D17** 로딩 표시: `ui/` 전체에 `CircularProgressIndicator` 0건. 금지된 지시 문구가 그 자리를 대신함.
- **AGENTS §4** "임계값·가중치 전부 `guide_config.json` 외부화": `"diagnoser": {}`, `"scoring": {}`가 비어 있고 `toDiagnoserConfig()`는 main에서 호출되지 않음. `SceneGuideCoordinator()`는 config 인자 없이 생성되어 씬 가이드 경로 전체가 코틀린 상수로 고정 — 리허설 현장 튜닝 불가.
- **기능명세서 §13** 예외 규칙 6건 중 4건 미구현. 그중 둘(`PendingRequestRepository`, `JobTimeoutPolicy`)은 클래스와 테스트가 완비된 채 호출자만 없어 이름 기반 감사에는 완료로 보인다.

### 3-3. 스키마 동결 규칙(§7 규칙 2) 위반 — ID 접두사 4곳

| 테이블 | 현재 | 규정 | 파일 |
|---|---|---|---|
| `edit_results_local` | `result_` + 로컬 **생성** | 서버 `res_` **그대로 복사** | `CaptureRepository.kt:365` |
| `session_guides` | `sgd_` | `gid_` | `GuideKpiRepository.kt:97` |
| `pending_requests` | `pnd_` | `req_` | `PendingRequestRepository.kt:56` |
| `card_selections` | `"round-1:$cardId"` 조합 문자열 | `sel_` + ULID | `ProfileRepository.kt:22` |

접두사로 필터링하는 지표 스크립트는 데이터가 가득 찬 테이블에 대해 0행을 반환한다. `edit_results_local`은 접두사보다 **ID를 복사하지 않고 생성한다**는 점이 더 심각하다 — 사용자가 선택한 결과를 서버 아티팩트로 되돌려 매핑할 방법이 없다.

추가로:
- `session_guides.resolved`가 `0` 하드코딩이고 UPDATE 경로가 없어 **가이드 실효성 KPI가 구조적으로 항상 0.0**. 스키마는 `NULL`을 "측정불가"로 예약해 두었는데 측정하지 않은 것을 "미해소"로 기록하고 있다.
- `sessions`는 항상 `mode='style'`, `reference_hash=NULL`, `resolved_style_json="{}"`로 기록(`CameraScreen.kt:272`) — §3.6이 명시한 재현성이 사라진다.
- `feedback`·`consents`는 엔티티와 DAO만 있고 writer가 0. D8이 "스키마 차원에서도 강제됨"이라 주장하는 동의 감사 기록이 한 번도 쓰이지 않는다. `ProfileEngine.applyFeedback`은 단위 테스트만 있고 main에서 호출되지 않아 개인화 루프가 테스트 안에서만 닫힌다.
- 엔티티에 `foreignKeys` 선언이 없어 DDL의 `REFERENCES` 절 8개가 생성 SQLite에 없다(고아 행 가능).
- `ix_captures_created` / `ix_events_type`에 `Index.Order.DESC`가 없어 최신순 쿼리가 인덱스를 역방향 스캔한다(Room 2.6.1은 `orders =` 지원, 한 줄 수정).

### 3-4. 그 외 확인된 결함

- `AlignmentEngine.kt:158`이 픽셀 공간 W:H 비율을 정규화 폭에 곱한다 — 모든 브래킷이 선언된 비율보다 약 25% 좁다. `SceneLayoutGuide.kt:137`에 중복되어 있고 테스트 하네스가 이 결함을 그대로 재현한다.
- `ResultScreen.kt:132`가 슬라이더 눈금마다 13MB 프리뷰 비트맵을 재할당하고 `recycle()`을 하지 않는다 — 드래그 한 번에 약 900MB~2GB 처닝.
- `matchScore`의 15%가 하드코딩 상수(`MatchScoreCalculator.kt:16`의 `observedHorizonPosition: Float = 0.5f`). `SceneStructureAnalyzer`가 실제 추정치를 만들어 두 호출 사이트 모두에서 스코프 안에 있는데 사용되지 않는다.
- `ResultScreen.kt:264`의 `?: edited ?: source`가 풀 해상도 디코드 실패 시 1440px 프리뷰(또는 필터 미적용 원본)를 "갤러리에 저장됨"으로 보고하며 저장한다.
- 편집 엔진이 둘 존재하고(`LocalEditor.kt:82` vs `:195`) 파일 헤더가 아직 "한 번에 하나만 연결하라"고 적고 있다 — 결정이 내려지지 않음.
- `CardRepository`가 죽어 있는데 온보딩이 `cards.json`을 인라인으로 재파싱한다(`OnboardingScreen.kt:107-110`).
- 첫 프리뷰 제스처가 콜드 스타트마다 유실된다(`CameraScreen.kt:840`, 코드 자체가 KNOWN GAP으로 명시).
- 서버: `/edit-jobs` 할당량·중복 ID 검사가 TOCTOU 경쟁(`edit_jobs.py:274`) → `MAX_ACTIVE_JOBS=1` 무력화, 중복 시 500. `/references/analyze`는 용량·픽셀 제한이 전혀 없고 이벤트 루프를 블로킹하며 `except Exception`이 내부 버그를 415로 재라벨링한다. WAL·`busy_timeout` 미설정.
- §7-1의 200ms 목표를 250ms로 초과 중(`alignedEnterFrames=3` × 12fps). 값이 코드와 에셋에 이중화되어 한쪽만 고치면 무효다(에셋이 이김).

### 3-5. 잔여 항목 규모

P1 `[ ]` 43건 / P2 `[ ]` 24건. Day 6-7 하드닝·시연 작업(P1 §6-1 5건, §6-3 4건, §7-1 3건, §7-2 2건, §7-3 4건, §7-4 3건)은 전혀 착수되지 않았다. P1 §0.4는 Day 6부터 신규 기능을 금지하는데, Day 6-7 목록이 손대지 않은 채로 Day 4-5 기능이 계속 추가·삭제되고 있다.

---

## 4. 리허설 전 우선순위

**서버 (필수)**
1. `comfyui_provider.py:166` 들여쓰기 수정 — 워커 영구 종료를 막는다.
2. `edit_jobs.py:333` 소유권 검사 추가 + `/files` 인가 — 남의 사진이 노출된다.
3. `main.py:24` 마운트를 `lifespan` 이후로 이동하거나 `ensure_storage()`를 임포트 시점에 — 클린 체크아웃에서 서버가 뜨지 않는다.

**앱 (필수)**
4. `FixedLayoutGuide.kt:225` 자동 래치 — 6개 프리셋 가이드를 전부 대체해 버린다. 최소한 래치 해제 경로가 필요하다.
5. D2/D13/D17 위반 제거(재론 불가 규칙) — 슬롯 색상 변화, 지시 문구 삭제 + 로딩 표시 추가.
6. `AppContainer.kt:34` 파괴적 마이그레이션 — 코드가 당장 깨지진 않지만, 시연 직전 핫픽스로 컬럼 하나만 추가해도 시연 기기의 KPI 증거가 통째로 사라진다.

**검증 환경 (선행)**
7. `git update-index --chmod=+x gradlew`와 JDK 17 설치 — 이것 없이는 어떤 수정도 검증할 수 없고, 기존의 초록 빌드 보고는 전부 의심해야 한다.

**문서**
8. `P1_Plan_1.md` §0.5와 `P2_Plan_1.md` §0.7의 체크박스를 현재 코드와 재동기화. 특히 `📱` 근거 주석이 `daafdb7` 이전인 항목은 전부 무효 처리.
