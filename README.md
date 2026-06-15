<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen?logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin" />
  <img src="https://img.shields.io/badge/AI-YOLOX%20%2B%20ONNX%20Runtime-blue" />
  <img src="https://img.shields.io/badge/Engine-Pikafish%202026-red" />
  <img src="https://img.shields.io/badge/LLM-DeepSeek-orange" />
  <img src="https://img.shields.io/badge/Min%20SDK-24-yellow" />
</p>

<h1 align="center">♟ ChinaChessMaster</h1>

<p align="center">
  <b>基于端侧 AI 视觉 + 本地象棋引擎 + 大模型推理的中国象棋实时辅助系统</b>
  <br/>
  <i>截屏识棋 → YOLOX 检测 → Pikafish 引擎分析 → DeepSeek 降级 → 一键出招</i>
</p>

---

## ✨ 功能概述

ChinaChessMaster 是一款 Android 端的中国象棋辅助工具。通过**悬浮球**一键触发，实时截取屏幕上的象棋棋盘，利用 **YOLOX 目标检测模型**在本地识别棋子，优先由 **Pikafish 本地象棋引擎**（全球顶级中国象棋 AI）分析最佳走法，引擎不可用时自动降级至 **DeepSeek 大语言模型**，最终以动画形式展示最佳走法建议。

### 核心特性

- **🔍 端侧视觉识别** — YOLOX nano 模型 + ONNX Runtime，全程离线推理，无需上传图片
- **♟ 本地象棋引擎** — 集成 **Pikafish 2026**（Stockfish 象棋分支），搭载 NNUE 神经网络评估，顶级棋力，完全离线
- **🧠 大模型降级兜底** — Pikafish 不可用时自动切换至 DeepSeek API 分析
- **🎯 全局悬浮球** — 覆盖任意象棋 App（如天天象棋），一键截图分析
- **🎬 走法动画** — 可视化展示推荐走法的起点与终点，Toast 显示来源（PikaFish / DeepSeek）

---

## 🏗 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                         Android App                           │
│                                                               │
│  ┌──────────┐    ┌─────────────────────────────────────────┐  │
│  │ 悬浮球   │───▶│           ChessMasterController         │  │
│  │ Overlay  │    │                                         │  │
│  │ Service  │    │  截图 → YOLOX识别 → FEN生成              │  │
│  └──────────┘    │          │                              │  │
│                  │          ▼                              │  │
│                  │   ┌─────────────┐   ┌───────────────┐  │  │
│                  │   │  Pikafish   │   │  LlmApiClient │  │  │
│                  │   │  Engine     │──▶│  (DeepSeek)   │  │  │
│                  │   │  (优先)     │失败│  (降级兜底)    │  │  │
│                  │   └─────────────┘   └───────────────┘  │  │
│                  └─────────────────────────────────────────┘  │
│                                                               │
│  ┌──────────────────────┐   ┌─────────────────────────────┐  │
│  │  ChessBoardAnalyzer  │   │       PikafishEngine        │  │
│  │  ┌────────────────┐  │   │  libpikafish.so             │  │
│  │  │ YoloxDetector  │  │   │  (nativeLibraryDir)         │  │
│  │  │ (ONNX Runtime) │  │   │  + pikafish.nnue (51MB)     │  │
│  │  └────────────────┘  │   │  UCI 协议通信               │  │
│  │  + generateFen()     │   └─────────────────────────────┘  │
│  └──────────────────────┘                                     │
└──────────────────────────────────────────────────────────────┘
```

---

## 📁 项目结构

```
app/src/main/java/com/ximao/chinachessmaster/
├── MainActivity.kt                  # 入口 Activity，权限申请与服务启动
├── analyzer/
│   ├── ChessBoardAnalyzer.kt        # 棋盘分析器：区域检测 + 坐标映射 + 颜色分类 + FEN 生成
│   └── YoloxChessDetector.kt        # YOLOX ONNX 推理引擎：前处理 → 推理 → NMS 后处理
├── api/
│   └── LlmApiClient.kt             # DeepSeek 大模型 API 客户端（降级兜底）
├── config/
│   └── ModelConfig.kt               # 模型配置管理（从 assets/model_config.json 加载）
├── controller/
│   └── ChessMasterController.kt     # 核心控制器：截图 → 识别 → Pikafish 优先 → DeepSeek 降级
├── engine/
│   ├── PikafishEngine.kt            # Pikafish UCI 引擎封装：进程管理 + UCI 协议通信
│   └── PikafishMoveConverter.kt     # ICCS 走法转换器：坐标转换 + 标准记谱生成
├── model/
│   └── ChessModels.kt               # 数据模型（棋子、走法、分析结果）
└── service/
    ├── OverlayService.kt             # 悬浮球 UI 服务
    ├── ScreenCaptureService.kt       # 屏幕截图服务（MediaProjection）
    └── AutoPlayAccessibilityService.kt  # 无障碍服务（自动落子）

app/src/main/assets/
├── chessai-det-light.onnx           # YOLOX nano 棋子检测模型（34MB）
├── model_config.json                # 大模型 API 配置文件
└── pikafish/
    ├── pikafish-armv8               # Pikafish 引擎二进制（ARM64 通用版）
    ├── pikafish-armv8-dotprod       # Pikafish 引擎二进制（ARM64 点积加速版，优先使用）
    └── pikafish.nnue                # NNUE 神经网络评估模型（51MB）

app/src/main/jniLibs/arm64-v8a/
└── libpikafish.so                   # 以 .so 形式打包的引擎，由系统安装到 nativeLibraryDir（获得执行权限）
```

---

## 🔬 技术实现

### 1. 屏幕截图

通过 Android **MediaProjection API** 实现全局截屏，无需 Root 权限。用户授权后，`ScreenCaptureService` 在后台持续运行，随时可获取当前屏幕画面。

### 2. 棋盘区域检测

`ChessBoardAnalyzer.detectBoardRegion()` 采用**颜色采样扫描算法**：

- 纵向逐行扫描中间区域，匹配棋盘特征色（暖黄/棕色调，R > G > B），确定上下边界
- 沿垂直中线横向扫描，确定左右边界
- 有效性校验：棋盘面积须占屏幕的一定比例

### 3. YOLOX 棋子检测

使用 [nrl-ai/chessai](https://github.com/nrl-ai/chessai) 的 **YOLOX nano** 预训练模型，通过 ONNX Runtime 在设备端离线推理：

| 阶段 | 处理内容 |
|------|---------|
| **预处理** | 等比缩放 + 灰色填充（114）至 640×640，归一化 HWC → CHW |
| **推理** | ONNX Runtime 前向传播，输出 `[1, 8400, 12]` |
| **后处理** | Grid/Stride 解码 → cxcywh → xyxy → Multi-class NMS |

**检测类别**（7 类）：

| 模型标签 | 红方 | 黑方 |
|---------|------|------|
| k (King) | 帅 | 将 |
| a (Advisor) | 仕 | 士 |
| b (Bishop) | 相 | 象 |
| r (Rook) | 車 | 車 |
| n (Knight) | 馬 | 馬 |
| c (Cannon) | 炮 | 砲 |
| p (Pawn) | 兵 | 卒 |

### 4. 红黑方区分

模型仅检测棋子类型，不区分阵营。通过分析检测框内棋子文字区域的**像素颜色特征**来判断红方或黑方。

### 5. Pikafish 本地引擎（主路）

集成 [Pikafish](https://github.com/official-pikafish/Pikafish) 2026 版本（基于 Stockfish 的中国象棋分支），通过 UCI 协议与引擎进程通信：

| 步骤 | 说明 |
|------|------|
| **FEN 生成** | `ChessBoardAnalyzer.generateFen()` 将识别到的棋子列表转为标准 FEN 字符串；根据帅/将的屏幕位置自动判断先手方（`w`/`b`） |
| **引擎启动** | 引擎 `.so` 由系统安装至 `nativeLibraryDir`，通过 `sh -c 'ulimit -s unlimited; engine'` 启动，解除栈大小限制以支持 NNUE 64MB 内存分配 |
| **UCI 通信** | `position fen <FEN>` → `go movetime 3000` → 解析 `bestmove <ICCS>` |
| **走法转换** | `PikafishMoveConverter` 将 ICCS 坐标（如 `h0f1`）直接映射为内部坐标（ICCS 行0 = 红方底线 = 内部 row=0），并从 FEN 解析棋子颜色，生成"红馬二进1"格式记谱 |
| **选项配置** | `NumaPolicy none`（禁用 NUMA 共享内存，避免 Android 兼容性问题）、`Threads 2`、`Hash 32MB` |

**ICCS 坐标系**：列 a-i → 0-8，行 0 = 红方底线，行 9 = 黑方底线，与内部坐标完全一致无需转换。

### 6. 大模型策略分析（降级兜底）

Pikafish 引擎不可用时，将识别结果转化为结构化棋盘文本描述，发送至 **DeepSeek API** 获取走法建议。支持通过 `model_config.json` 灵活切换不同 LLM 后端。Toast 提示中会显示走法来源（`PikaFish 提示：` / `DeepSeek 提示：`）。

---

## 🚀 快速开始

### 环境要求

- Android Studio Ladybug 或更高版本
- JDK 11+
- Android 设备（minSdk 24，即 Android 7.0+）

### 构建 & 运行

```bash
# 克隆仓库
git clone https://github.com/VictoriaColone/ChinaChessMaster.git

# 使用 Android Studio 打开项目，或命令行构建
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

### 配置大模型

编辑 `app/src/main/assets/model_config.json`，填入你的 API Key：

```json
{
  "current_model": "deepseek",
  "models": {
    "deepseek": {
      "name": "DeepSeek",
      "base_url": "https://api.deepseek.com/v1",
      "api_key": "YOUR_API_KEY_HERE",
      "model_id": "deepseek-chat",
      "max_tokens": 2048,
      "temperature": 0.3
    }
  }
}
```

### 使用方式

1. 打开 ChinaChessMaster，授予**悬浮窗**和**屏幕录制**权限
2. 切换到任意象棋 App（如天天象棋），开始对弈
3. 点击**悬浮球**，等待分析完成
4. 查看推荐走法动画

---

## 🛠 技术栈

| 组件 | 技术选型 |
|------|---------|
| **语言** | Kotlin |
| **目标检测** | YOLOX nano + ONNX Runtime Mobile 1.16.3 |
| **象棋引擎** | Pikafish 2026 (ARM64)，UCI 协议，NNUE 神经网络评估 |
| **大模型** | DeepSeek API（降级兜底，可配置） |
| **网络** | OkHttp 4.x |
| **序列化** | Gson |
| **异步** | Kotlin Coroutines + Mutex |
| **UI** | Android WindowManager (悬浮窗) |
| **截屏** | MediaProjection API |

<p align="center">
  <i>Built with ♟ and ❤️</i>
</p>
