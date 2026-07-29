# Third-party notices

## EfficientDet-Lite0 INT8

- Asset: `app/src/main/assets/models/efficientdet_lite0_coco_int8.tflite`
- Model source: [TensorFlow Lite Model Maker / TF Hub EfficientDet-Lite0](https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite)
- Downloaded for this build from the TF Hub URL above.
- Size: 4,563,519 bytes
- SHA-256: `2E04C53BFEAC0AC2A30C057C7E2A777594CE39BAAAC35A92F74FB1E8C4FC4E0B`
- License: Apache License 2.0. The model is used as an on-device inference dependency; the original training images are not bundled in the app.

The MediaPipe Tasks Vision runtime is declared through Gradle as
`com.google.mediapipe:tasks-vision:0.10.26` and remains subject to its own
upstream license and notice requirements.

## FLUX.1 Fill dev and ComfyUI support models

- Model source: [Black Forest Labs FLUX.1 Fill dev](https://huggingface.co/black-forest-labs/FLUX.1-Fill-dev)
- Deployment source: the public Comfy-Org repackaged split model plus the
  `comfyanonymous/flux_text_encoders` CLIP-L and scaled FP8 T5 encoders.
- Purpose: CAMP-2 demo-only image outpainting; the model is never bundled in
  the Android APK.
- License: FLUX.1 Fill dev is governed by the upstream FLUX.1 dev
  non-commercial license. It must be replaced or separately licensed before a
  commercial release.
- Verified support-file SHA-256:
  - `clip_l.safetensors`: `660c6f5b1abae9dc498ac2d21e1347d2abdb0cf6c0c0c8576cd796491d9a6cdd`
  - `t5xxl_fp8_e4m3fn_scaled.safetensors`: `a498f0485dc9536735258018417c3fd7758dc3bccc0a645feaa472b34955557a`
  - `ae.safetensors`: `afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38`

## IC-Light

- Source: [lllyasviel/IC-Light](https://github.com/lllyasviel/IC-Light)
- Code license: Apache License 2.0.
- Offset model: `iclight_sd15_fc.safetensors`, 1,719,148,312 bytes.
- SHA-256: `a033fbaaa2f3f7859fa6a4477ee63ebbf9c116bf3569d5811856d2807f3468cd`.
- Purpose: CAMP-2 demo-only foreground relighting. The service uses the
  upstream Stable Diffusion dependencies and their respective model terms.

## ViewCrafter

- Source: [Drexubery/ViewCrafter](https://github.com/Drexubery/ViewCrafter)
- Code license: Apache License 2.0.
- Planned checkpoint: `Drexubery/ViewCrafter_16` plus the upstream DUSt3R
  checkpoint. Model weights are server-side only and are not bundled in the
  Android APK.
- The runtime capability remains disabled until both complete checkpoints and
  a successful CAMP-2 smoke test are present; partial files are not treated as
  a deployed model.
