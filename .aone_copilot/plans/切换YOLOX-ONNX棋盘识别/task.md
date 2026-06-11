# 切换 YOLOX-ONNX 棋盘识别 - 任务清单

## 依赖与资源
- [x] 修改 `libs.versions.toml`：添加 onnxruntime，移除 mlkit-text-chinese
- [x] 修改 `app/build.gradle.kts`：添加 onnxruntime-android 依赖，移除 mlkit 依赖
- [x] 复制 `chessai-det-light.onnx` 模型到 `app/src/main/assets/`

## 核心代码
- [x] 创建 `YoloxChessDetector.kt`：ONNX Runtime 推理 + YOLOX 前后处理
- [x] 重写 `ChessBoardAnalyzer.kt`：替换 ML Kit OCR 为 YOLOX 检测
- [x] 修改 `ChessMasterController.kt`：传入 context，更新注释

## 验证
- [x] 编译验证：`./gradlew assembleDebug`
- [x] 安装到设备测试识别效果


---
生成时间: 2026/6/11 21:04:44
planId: b24d0b96-a30e-42a8-b20d-63205b01ffeb