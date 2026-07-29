# GAMDO Web

`나 찍어줘`의 브라우저 카메라와 데모 전용 감도 아카이브입니다.

```powershell
npm install
npm run build
```

`dist/`는 Git에 넣지 않습니다. CAMP-2 배포 전 위 빌드를 실행하면 FastAPI가
`/shoot/{shareToken}`, `/archive`, `/web-assets/*`로 결과물을 서빙합니다. HTTPS에서만
브라우저 카메라(`getUserMedia`)가 동작합니다.
