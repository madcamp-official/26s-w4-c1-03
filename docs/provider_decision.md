# 생성 공급자 결정 기록

## 2026-07-25 — CAMP-2 P2 실환경 검증

- 1순위 공급자: **ComfyUI headless + LaMa** (`big-lama.pt`)
- 대상 작업: `remove_objects`만 우선 연결
- FLUX.1 Fill: 이번 프로토타입에서는 설치하지 않음. 큰 영역 확장은 후속 범위로 남긴다.
- 검증 공급자: **InsightFace buffalo_l**. 얼굴 임베딩은 요청 처리 중 메모리에서만 비교하고 저장하지 않는다.
- CAMP-2: RTX 3090 24GB, ComfyUI API `:8188`, P2 격리 경로 `/opt/gamdo`
- 운영 unit: `ops/camp2/gamdo-comfyui.service` → CAMP-2 `/etc/systemd/system/gamdo-comfyui.service`, 재부팅 후 자동 시작

## 확인 결과

- `/system_stats`: ComfyUI 0.28.0, PyTorch CUDA 2.11.0+cu128, RTX 3090 인식
- `INPAINT_LoadInpaintModel` 및 `INPAINT_InpaintWithModel` custom node 등록 확인
- LaMa smoke test: 512×512 입력/마스크 → 실제 ComfyUI 결과 PNG 생성 성공
- 5-case GPU smoke benchmark: 각 입력에서 seed 0·1 후보 2개 생성 성공
- 실제 사진 샘플 5종(astronaut/coffee/chelsea/rocket/motorcycle)으로 10개 결과 생성 성공, 전부 원본 해상도 유지 및 파일 무결성 확인. 마스크가 품질 평가용으로 큐레이션된 세트는 아니므로 정식 4/5 육안 판정은 보류.
- CAMP-2 내부 FastAPI + worker E2E: `queued → processing → validating → done`, InsightFace `validation=passed` 후보 2개(seed 0·1) 반환.
- 연속 부하 10건: 10/10 `done`, 10/10 `validation=passed`, 개별 1.70~2.22초, 총 18.09초.
- InsightFace `buffalo_l`: CUDAExecutionProvider 로드 성공
- 임계값 sanity check: 동일 이미지 cosine 1.0000, 밝기 변경 cosine 0.9993, 얼굴 미검출 케이스 거부. 초기 threshold `0.35` 통과 확인(실제 인물 5장 formal calibration은 별도).

## 정리 기록

- 종료된 VibeCutter Docker 컨테이너·이미지 삭제
- `/root/glm-model` 삭제
- `/var/lib/dure`, Dure qualification 컨테이너·vLLM 이미지·모델은 보존
- 정리 후·GPU 런타임 설치 후 CAMP-2 디스크: 약 15GB 여유, 86% 사용

## 미완료

- 실제 인물 사진 5장 품질 평가
- 실제 인물 5장 기반 정식 품질·threshold calibration
- 실기기에서 CAMP-2 후보 2개 수신 및 결과 화면 전체 흐름 검증
- FLUX.1 Fill 아웃페인팅

## 2026-07-25 — CAMP-2 결과 전달 경로 수정

- 원격 `/opt/gamdo/server`에 최신 `app/main.py`·`app/routes/edit_jobs.py`를 백업 후 반영했다.
- FastAPI를 재시작하고 결과 URL을 서버 내부 절대경로에서 `/files/{filename}`으로 수정했다.
- 실제 인물 카드 사진으로 `queued → processing → validating → done`, 후보 2개, seed 0·1, `validation=passed`를 재확인했다.
- `/files/...png` 결과 다운로드 HTTP 200을 확인했다.
- 실기기 `SM-G970N` Android API 테스트 2개를 CAMP-2 SSH 터널 경유로 통과시켰다.

## 2026-07-27 — CAMP-2 AI 품질 게이트 보강

- CAMP-2 재접속 확인: RTX 3090 24GB, NVIDIA 580.173.02, ComfyUI/FastAPI/worker 모두 `active`, ComfyUI `/system_stats`와 FastAPI `/health` 응답 정상.
- LaMa 5종 benchmark를 재실행해 5/5 케이스에서 seed 0·1 후보 2개씩 생성했다. 총 5.56초(케이스당 약 1.11초)였다.
- 각 후보에 PNG 디코드 가능·원본과 동일 해상도·원본 바이트와 불일치·케이스 간 결과 중복 없음 검사를 추가했고 10/10 후보가 통과했다. 이는 미적 품질 점수가 아니라 생성 결과 위장·손상·해상도 변경을 막는 최소 게이트다.
- 워커는 검증 탈락 후보를 즉시 삭제하고, 후보 경로가 입력 원본을 가리키는 경우 `candidate_aliases_input`으로 거부하도록 보강했다. 원본 파일은 삭제하지 않는다.
- 캘리브레이션 도구는 이제 얼굴이 실제로 검출된 측정 쌍이 5개 미만이거나 `sameIdentity` 라벨이 빠지면 실패한다. 카드 에셋·앱 화면 캡처·합성 이미지는 임계값 산출에 사용하지 않는다.
- 캘리브레이션은 같은 사람 2쌍 이상과 다른 사람 2쌍 이상을 모두 요구하며, `GAMDO_FACE_SIMILARITY_THRESHOLD`가 `0~1` 범위를 벗어나거나 `NaN`·무한대·비숫자이면 운영 기본값 `0.35`로 되돌린다. 실제 사진 전에는 기본값을 변경하지 않는다.

## 2026-07-27 — InsightFace CUDA provider 재검증

- CAMP-2 worker와 동일하게 `/etc/gamdo/gamdo.env`의 `LD_LIBRARY_PATH`를 적용해 InsightFace `buffalo_l`을 초기화했다.
- detection·landmark 2종·genderage·recognition 모델 전부에서 `CUDAExecutionProvider`가 활성 provider로 확인됐다. 환경 파일을 적용하지 않은 단독 점검에서는 `libcublasLt.so.12`를 찾지 못해 CPU로 fallback했으나, 운영 worker 환경에서는 재현되지 않았다.
- InsightFace 패키지의 비개인 다인 테스트 이미지 `t1.jpg`로 명시적 마스크를 얼굴에서 떨어진 위치에 지정해 FastAPI E2E를 재실행했다. `queued → processing → validating → done`, 후보 2개, 두 후보 모두 `validation=passed`를 확인했다. 이 이미지는 threshold 캘리브레이션 라벨 쌍에는 사용하지 않는다.
