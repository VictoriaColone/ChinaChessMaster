# 切换 YOLOX + ONNX Runtime 棋盘识别方案

将棋盘识别从 ML Kit OCR 切换到 YOLOX + ONNX Runtime 本地推理方案。使用已下载的 `chessai-det-light.onnx` 模型（34MB，来自 [nrl-ai/chessai](https://github.com/nrl-ai/chessai)），在设备端实时检测棋子。

## User Review Required

> [!IMPORTANT]
> 模型只区分棋子类型（7类：車/馬/象/士/将/炮/兵），不区分红黑方。需要结合棋子文字周围的像素颜色分析来区分红黑。

> [!WARNING]
> ONNX 模型文件 34MB，会增加 APK 体积。如需优化可后续考虑模型量化。

## Proposed Changes

### Gradle 依赖

#### [MODIFY] [build.gradle.kts](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/app/build.gradle.kts)
- 添加 ONNX Runtime Mobile 依赖：`com.microsoft.onnxruntime:onnxruntime-android:1.16.3`
- 移除 ML Kit 中文 OCR 依赖：`com.google.mlkit:text-recognition-chinese`

#### [MODIFY] [libs.versions.toml](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/gradle/libs.versions.toml)
- 添加 `onnxruntime` 版本和库定义
- 移除 `mlkit-text-chinese` 相关条目

---

### 模型资源

#### [NEW] [chessai-det-light.onnx](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/app/src/main/assets/chessai-det-light.onnx)
- 从 `/tmp/VinXiangQi/chessai-det-light.onnx` 复制到 assets 目录
- YOLOX nano 模型，输入 `[1, 3, 640, 640]`，输出 `[1, 8400, 12]`
- 7 类别：r(車), n(馬), b(象), a(士), k(将帅), c(炮), p(兵卒)

---

### 核心识别器

#### [NEW] [YoloxChessDetector.kt](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/app/src/main/java/com/ximao/chinachessmaster/analyzer/YoloxChessDetector.kt)
- 封装 ONNX Runtime 推理逻辑
- 实现 YOLOX 预处理：缩放 + 灰色填充（114）到 640x640
- 实现 YOLOX 后处理：grid/stride 解码 → cxcywh → xyxy → NMS
- 输出检测结果列表：`List<DetectedPiece>`（类别、置信度、bbox）

关键推理流程：
```kotlin
// 预处理
val inputTensor = preprocessBitmap(bitmap) // [1, 3, 640, 640]
// ONNX 推理
val output = session.run(mapOf("images" to inputTensor))
// 后处理：grid解码 + NMS
val detections = postprocess(output, ratio) // List<DetectedPiece>
```

YOLOX 后处理算法（从 Python 移植）：
```
strides = [8, 16, 32]
对每个 stride，生成 (h/stride × w/stride) 的 grid
outputs[..., :2] = (outputs[..., :2] + grids) * expanded_strides
outputs[..., 2:4] = exp(outputs[..., 2:4]) * expanded_strides
然后 cxcywh → xyxy，乘以 obj_score，做 multiclass NMS
```

---

#### [MODIFY] [ChessBoardAnalyzer.kt](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/app/src/main/java/com/ximao/chinachessmaster/analyzer/ChessBoardAnalyzer.kt)
- 移除 ML Kit OCR 依赖，改用 `YoloxChessDetector`
- 保留棋盘区域检测逻辑（`detectBoardRegion`）
- 保留颜色分析逻辑（`detectPieceColor`）用于区分红黑方
- 修改 `analyzeScreenshot` 流程：
  1. 检测棋盘区域（复用现有逻辑）
  2. 裁剪棋盘 → YOLOX 检测棋子类型和位置
  3. 根据检测框中心坐标映射到棋盘行列
  4. 颜色分析区分红黑方
  5. 生成文本描述（复用现有逻辑）

棋子类型映射（模型类别 → 红黑棋子名）：
| 模型类别 | 红方 | 黑方 |
|---------|------|------|
| k (King) | 帅 | 将 |
| a (Advisor) | 仕 | 士 |
| b (Bishop) | 相 | 象 |
| r (Rook) | 車 | 車 |
| n (Knight) | 馬 | 馬 |
| c (Cannon) | 炮 | 砲 |
| p (Pawn) | 兵 | 卒 |

---

#### [MODIFY] [ChessMasterController.kt](file:///Users/victorcolone/AndroidStudioProjects/ChinaChessMaster/app/src/main/java/com/ximao/chinachessmaster/controller/ChessMasterController.kt)
- 更新注释：`ML Kit OCR` → `YOLOX ONNX`
- 传入 `context` 给 `ChessBoardAnalyzer`（加载 ONNX 模型需要 AssetManager）

## Verification Plan

### Automated Tests
- 编译验证：`./gradlew assembleDebug`
- 安装到设备验证推理流程

### Manual Verification
- 打开天天象棋 App，开始一局对弈
- 点击悬浮球触发截图分析
- 验证 YOLOX 是否正确识别出棋子位置和类型
- 验证红黑方颜色区分是否准确
- 验证 DeepSeek 返回的走棋建议是否合理


---
生成时间: 2026/6/11 21:04:44
planId: b24d0b96-a30e-42a8-b20d-63205b01ffeb
plan_status: review