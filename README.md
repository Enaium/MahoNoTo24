# 魔塔 (魔法の塔)

《魔塔》经典 Flash 游戏的 Kotlin Multiplatform + SDL3 移植版。

游戏原作：**胖老鼠工作室** 的经典魔塔。本仓库使用
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) 与
[sdl-kmp](https://github.com/Enaium/sdl-kmp)（SDL3 的 KMP 绑定）将游戏重新实现。

本移植版以 **h5mota（HTML5 魔塔平台）重制版**（`assets/`，24 层魔塔 Ver 1.12）的
数据与逻辑为准：楼层地图、怪物/道具数据、事件脚本、商店、战斗公式与美术资源
全部来自该版本，而非早期 SWF 版。

![](https://img.cdn1.vip/i/6a81156f7e9f7_1786844527.webp)

## 特性

- 完整移植 h5mota 版 24 层魔塔：序章 + 21 层主塔 + 22/23 西/南/东隐藏层 + 地下层
- 数据驱动：楼层、怪物、道具、事件、商店均解析自 `assets/` 的 JSON 数据
  （使用 kotlinx.serialization）
- 完整事件系统：对话（含头像/标题）、选择、确认、商店、楼层切换、开/关门、
  显隐图块、战后/拾取事件等
- 战斗：经典回合制（暴击、回合间隔可调）与极速模式（期望伤害公式），
  支持破甲/净化/吸血/固伤/连击/反击等特殊效果；战后显示金币/经验奖励
- 地图信息层（显伤）：怪物上直接显示预测伤害、击杀金币/经验数值，
  道具上显示补充数值（需圣光徽）
- 道具/状态：钥匙、宝石、血瓶、装备、毒/衰/咒状态、圣水等
- 存档/读档（文件存储，FileKit）、怪物图鉴（X）、楼层传送（G）、道具栏（T）、
  快捷商店（V）、帮助面板、鼠标操作
- 图片加载使用 [sdl-image-kmp](https://github.com/Enaium/sdl-image-kmp)（SDL_image）；
  中文 UI 文字由 [sdl-ttf-kmp](https://github.com/Enaium/sdl-ttf-kmp)（SDL_ttf 3）实时渲染

## 目录结构

```
src/
  commonMain/          # 全部游戏代码（逻辑 + 渲染 + 资源加载）
  jvmMain/             # JVM 入口 + 文字图集生成工具
  nativeMain/          # macOS 原生入口
assets/
  images/ materials/ tilesets/ animates/ sounds_wav/ bgms_wav/ floors/  # h5mota 版数据与资源
  data.json maps.json enemys.json items.json icons.json events.json      # 游戏数据（JSON）
  fonts/               # 数字（number.ttf）+ 代码（FiraCode.ttf）字体；中文字体使用系统字体
```

## 构建与运行

```bash
# JVM
./gradlew fatJar
java -jar build/libs/MahoNoTo-1.0-SNAPSHOT-all.jar assets

# macOS 原生 (Apple Silicon)
./gradlew linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/MahoNoTo.kexe assets
```

> 游戏资源目录默认为 `assets`（相对工作目录），可传参指定其他路径。

### 无头测试模式

不带显示器（CI / 服务器）环境可运行脚本化的自测流程，并把每一帧渲染
结果保存为 BMP 截图到 `shots/`：

```bash
./build/bin/macosArm64/debugExecutable/MahoNoTo.kexe assets --test
```

`--test --full` 为通关自测模式：以满属性（高血量/攻击/防御、全钥匙、
全楼层传送）自动走完 21 层主塔并验证结局流程。

## 操作

| 按键 | 功能 |
|------|------|
| 方向键 | 移动（按住连续行走）/ 菜单选择 |
| 空格 / 回车 | 确认（对话、选择、商店、跳跃面板） |
| X / Esc | 关闭面板 / 商店 |
| X | 怪物图鉴（需圣光徽） |
| G | 楼层传送（需风之罗盘） |
| T | 道具栏 |
| V | 快捷商店 |
| S / D | 存档 / 读档 |
| F | 二倍斩技能（需炎之灵杖） |
| Esc | 系统菜单 |
| 鼠标 | 点击工具栏/选择/确认；点击地图格子高亮怪物 |

## 移植说明

- 数据文件由 h5mota 编辑器导出的 JS 转换为 JSON（`assets/*.json`），
  用 kotlinx.serialization 解析；文件读写使用
  [FileKit](https://github.com/vinceglb/FileKit)。
- 游戏逻辑对照 h5mota 引擎实现：移动/碰撞、事件机、战斗公式、商店、
  状态栏（自绘）、对话框（winSkin 九宫格）等。
- 渲染为 640x480 窗口，地图 13x13（32px/格），状态栏在左侧、工具栏在地图下方，
  与 h5mota PC 布局一致。
