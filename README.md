<<<<<<< HEAD
hello
=======
# Vox助手（VoxAssist）

全离线中文语音助手 APK。唤醒 → 本地流式识别 → GLM 回答 → Edge TTS 朗读，支持打断。

## 架构

| 环节 | 技术 | 在线？ |
|---|---|---|
| 唤醒词 "你好小爱" | sherpa-onnx KWS (wenetspeech 3.3M) | 离线 |
| 语音活动检测/切句 | Silero VAD | 离线 |
| 流式语音识别 | streaming-zipformer-small-bilingual-zh-en | 离线 |
| 大脑 | GLM (ai-hub.digiwincloud.com.cn, OpenAI 兼容) | 在线 |
| 朗读 | Edge Read-Aloud TTS（晓晓，+20% 语速，内置 Sec-MS-GEC v2 令牌移植） | 在线 |

## 构建

GitHub Actions（`.github/workflows/build.yml`）：
1. DRM 令牌移植自检（对拍 Python edge-tts，固定时间戳 1700000000 → `2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2`）
2. `gradle assembleDebug`（AGP 8.5.2 / Gradle 8.10.2 / JDK 17 / Kotlin 1.9.24）
3. 产物：arm64-v8a debug APK

## 首启流程（App 内自动完成）

1. 麦克风权限
2. 下载模型（~55MB 一次性）：ASR tarball + silero_vad.onnx + KWS tarball
3. 用户填 GLM API Key
4. 点"启动" → 前台服务开始常驻

## 对话状态机

```
IDLE --(KWS 命中)--> LISTENING --(endpoint 切句)--> THINKING --(GLM 返回)--> SPEAKING
  ^                    |  ^                            |                        |
  +----(30s 静音)-------+  +--------(barge-in VAD)------+<----(播完)-------------+
```

## 已知约束

- 仅 arm64-v8a（麒麟 970 等）
- GLM Key 未填时启动会报"未配置 GLM API Key"
- Edge TTS 失败时无备选（后续可加系统 TTS 兜底）
>>>>>>> ea66995 (VoxAssist v1.0: offline wake+ASR, GLM brain, Edge TTS with DRM port)
