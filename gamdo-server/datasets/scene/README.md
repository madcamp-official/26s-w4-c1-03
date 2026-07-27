# GAMDO scene-model dataset contract

이 디렉터리에는 이미지 원본을 저장하지 않는다. 공개 데이터셋 또는 팀이 허가받은 사진은 외부 경로에 두고, 저장소에는 `manifest.jsonl`만 둔다.

## 클래스

매니페스트의 `category`는 다음 값만 허용한다.

`person`, `drinkware`, `bag`, `plant`, `food_tableware`, `unknown`

`unknown`은 학습 대상이 아니라 hard-negative 검증 샘플에 사용한다.

## 한 줄의 형식

```json
{"image":"/data/gamdo/objects/cup_001.jpg","source":"coco","license":"CC BY 4.0","license_url":"https://cocodataset.org/#termsofuse","commercial_use":false,"split":"train","width":1280,"height":1600,"instances":[{"label":"cup","category":"drinkware","bbox":[120,180,840,1120],"polygon":[[120,210],[300,180],[820,240],[840,1080],[500,1300],[150,1120]]}]}
```

- `bbox`는 픽셀이 아닌 `[left, top, right, bottom]` 픽셀 좌표다.
- `polygon`은 최소 3점의 픽셀 좌표이며, 실제 물체 외곽선이다.
- `source`, `license`, `license_url`, `commercial_use`는 반드시 기록한다.
- `split`은 `train`, `validation`, `test` 중 하나다.
- 사진 원본의 EXIF는 학습 전 제거한다.

## 검증

```powershell
python gamdo-server/scripts/validate_scene_dataset.py gamdo-server/datasets/scene/manifest.example.jsonl
```

실제 학습을 시작할 때는 `--min-per-class 300 --require-mask`를 사용한다. 라이선스가 불명확하거나 `commercial_use=true`가 확인되지 않은 샘플은 상용 배포 모델 학습 세트에서 제외한다.
