# 魔塔 (MahoNoTo)

《魔塔》经典 Flash 游戏的 Kotlin Multiplatform + SDL3 移植版。

游戏原作：**胖老鼠工作室** 的经典 SWF 版魔塔。本仓库使用
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) 与
[sdl-kmp](https://github.com/Enaium/sdl-kmp)（SDL3 的 KMP 绑定）将整个游戏
重新实现

## 特性

- 完整移植原版 22 层地图、31 种怪物与全部游戏机制：
  移动、战斗（含会心一击）、钥匙/门、道具、商店（金币/经验/钥匙买卖）、
  楼层跳跃、怪物图鉴、NPC 对话（公主、商人、老者、魔王）、结局判定
- 纯 Kotlin 编写的 PNG 解码器，不依赖 SDL_image
- 中文 UI 文字：开发期用黑体字体预渲染为位图图集，运行时拼合
- 音效与背景音乐（按楼层切换、循环播放）

## 目录结构

```
src/
  commonMain/          # 全部游戏代码（逻辑 + 渲染 + 资源加载）
  jvmMain/             # JVM 入口 + 文字图集生成工具
  nativeMain/          # macOS 原生入口 + 文件 IO
assets/
  sprites/             # FFDec 导出的全部精灵帧（地图块/怪物/道具/UI/对话）
  sounds/              # 由 mp3 转换的 WAV 音效与 BGM
  fonts/               # 黑体字体 + 生成的文字图集（atlas.png + chars.txt）
  title.png            # 标题画面背景
```

## 构建与运行

```bash
# JVM
./gradlew jvmJar
java -jar build/libs/MahoNoTo-jvm-1.0-SNAPSHOT.jar assets

# macOS 原生 (Apple Silicon)
./gradlew linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/MahoNoTo.kexe assets

# macOS 原生 (Intel)
./gradlew linkDebugExecutableMacosX64
./build/bin/macosX64/debugExecutable/MahoNoTo.kexe assets
```

> 游戏资源目录默认为 `assets`（相对工作目录），可传参指定其他路径。

### 无头测试模式

不带显示器（CI / 服务器）环境可运行脚本化的自测流程，并把每一帧渲染
结果保存为 BMP 截图到 `shots/`：

```bash
./build/bin/macosArm64/debugExecutable/MahoNoTo.kexe assets --test
```

### 重新生成文字图集（开发用）

```bash
./gradlew generateAtlas
```

## 操作

| 按键 | 功能 |
|------|------|
| 方向键 | 移动 / 菜单选择 |
| 空格 / 回车 | 确认（对话、商店、跳跃面板） |
| 2 / 8 | 商店、跳跃面板中上下选择 |
| 5 | 确认购买 / 跳跃 |
| L | 打开 / 关闭怪物图鉴 |
| J | 打开 / 关闭楼层跳跃 |
| R | 重新开始游戏 |
| Q | 回到序章 |
| Esc | 返回标题画面 |

## 移植说明

- 地图与怪物数据从原 SWF 脚本（`frame_1158 DoAction.as`）解析生成
  （见 `MapData.kt`），游戏逻辑逐行对照移植（见 `GameState.kt`）。
- 战斗采用原版回合制：每 500ms 一轮攻防，攻击方按等级概率触发双倍伤害。
- 对话（公主、商人等）使用 FFDec 导出的静态帧图，按阶段切换帧。
- 窗口保持原版 570x410 尺寸 1:1 渲染，还原 2005 年的画面质感。