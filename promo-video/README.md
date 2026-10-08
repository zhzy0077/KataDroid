# KataDroid 中文视频

独立于 Android 构建的 **Remotion + MiMo TTS** 工程。

| 视频（MP4，Git LFS） | 时长 | 画幅 | Composition | 字幕 |
| --- | ---: | --- | --- | --- |
| [宣传片正式版](videos/katadroid-promo-zh-54s.mp4) | 54 秒，含片尾 | 1920×1080 · 30 fps | `KataDroid-Promo-ZH` | [SRT](videos/promo.zh-CN.srt) |
| [一分钟复盘教程](videos/katadroid-tutorial-zh-vertical-58s.mp4) | 58 秒 | 1080×1920 · 30 fps | `KataDroid-Tutorial-ZH` | [SRT](videos/tutorial.zh-CN.srt) |
| [风格样片](videos/katadroid-teaser-zh-1080p.mp4) | 10 秒 | 1920×1080 · 30 fps | `KataDroid-Teaser-ZH` | [SRT](videos/teaser.zh-CN.srt) |

正式版采用最新 APK 的中文 AVD 操作录屏，包含离线分析、候选预览、胜率图跳转、变化树、AI 对弈、SGF 菜单和下载二维码。中文旁白、字幕、轻量氛围音与落子音效均已接入。

## 获取成片

发布成片位于 [`videos/`](videos/)。MP4 使用 Git LFS；字幕、[教程章节](videos/tutorial.chapters.txt)和 [SHA-256 校验清单](videos/SHA256SUMS)使用普通 Git 文本存储。

安装 Git LFS 后，在仓库根目录执行：

```bash
git lfs install
git lfs pull --include="promo-video/videos/*.mp4"
(cd promo-video/videos && sha256sum -c SHA256SUMS)
```

更新发布成片时，先完成下文的渲染与校验，再从 `.local/output/` 复制对应的 MP4、SRT 和章节文件到 `videos/`，并更新校验清单。原始素材和验证记录继续保存在 `.local/`。

## 预览与渲染

需要 Node.js 22 及以上、Python 3、FFmpeg，以及 Chromium / Chrome。npm 依赖仅安装在本目录。

```bash
cd promo-video
npm ci
npm run check
npm run dev
```

打开 `http://localhost:3100`，在左侧选择上表对应的 Composition。浏览器由 PATH 自动发现，也可通过 `REMOTION_BROWSER_EXECUTABLE` 指定。

```bash
npm run render:final:stills # 检查七个分镜关键帧
npm run render:final        # 渲染 54 秒正式版
npm run verify:final        # 验证总时长、1620 帧、音视频与片尾二维码
```

正式版输出：

- `.local/output/katadroid-promo-zh-54s.mp4`
- `.local/output/promo.zh-CN.srt`
- `.local/output/promo-01-intro.png` 至 `promo-07-close.png`
- `.local/output/promo-verification.json`

10 秒样片保留 `tts:sample`、`render:sample`、`render:stills`、`verify:sample` 命令和原输出文件。

## 竖屏复盘教程

教程按「导入 → 分析 → 点击曲线回看 → 长按预览 → 试下分支 → 导出」讲解。长按镜头使用更新后的 App，实录候选变化的前七手。镜头含真实点按标记、局部放大和分步提示。文件选择环节使用标注为“操作指引”的图示，其余操作来自 App 实录。

```bash
npm run render:tutorial:stills # 检查八个教学关键帧
npm run render:tutorial        # 渲染竖屏教程，并生成字幕与章节
npm run verify:tutorial        # 全片解码、音轨、素材、分析来源与 SGF 导出校验
```

输出位于 `.local/output/`：

- `katadroid-tutorial-zh-vertical-58s.mp4`
- `tutorial.zh-CN.srt`
- `tutorial.chapters.txt`
- `tutorial-01-curve.png` 至 `tutorial-08-save.png`
- `tutorial-verification.json`、`tutorial-source-verification.json`

### 棋谱与真实分析

使用 [完整第四局棋谱](content/tutorial-game.sgf)：2016-03-13，AlphaGo 执黑、李世石执白，共 180 手。主线落子与比赛事实来自公开棋谱，来源见 [LICENSES.md](LICENSES.md)；原始注释和旁支已清理。

本次曲线由 App 的 **CPU / b6c96** 实际计算，全部 **181 个局面达到至少 100 visits**。第 **102→103 手**的黑棋胜率由 **79.0% 降到 46.3%**，教程围绕此转折演示操作。`scripts/tutorial_source.py` 验证模型缓存、完整主线和分析覆盖率，原始分析证据保存在 `.local/capture/tutorial/curve-proof.json`。

在第 102 手试下 P12 后，已通过系统文件选择器实际导出 SGF，校验原主线与新变化均被保留。导出样本和动作记录同样位于 `.local/capture/tutorial/`。

预览录制中途保存 App 截图、界面树与持久状态作为检查点。`preview-proof.json` 将七手原始 PV、棋谱未改动的检查结果及录屏 SHA-256 绑定；来源校验会核对这些证据与实际分析缓存。

### 教程素材与重拍

复用成片需要 `.local/public/tutorial/captures/` 和 `.local/public/audio/`；执行完整来源校验还需保留 `.local/capture/tutorial/`。设备标识、原始界面树和截图全部留在这些忽略目录内。

检查 `adb devices -l` 后，显式传入本次 AVD serial。先从 App 导入示例棋谱，分析镜头从“已暂停”状态开始：

```bash
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> analysis
python3 scripts/tutorial_source.py --serial <本次实际的AVD序列号> --require-complete
# 等待全部局面完成分析后，依次准备转折位置并录制：
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> prepare
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> review
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> preview
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> branches
python3 scripts/capture_tutorial.py --serial <本次实际的AVD序列号> export-menu
```

`import-menu` 可在导入棋谱前单独录制。菜单镜头结束后，从界面完成实际文件选择。改变棋谱、分析设置或操作位置时，同步更新 `content/tutorial-motions.json` 的镜头与数值标注，再运行来源校验。

## 中文旁白

```bash
# Bash：交互输入，不把 Key 写入脚本或 shell 历史。
read -rsp 'MiMo API Key: ' MIMO_API_KEY; echo
export MIMO_API_KEY
npm run tts:final    # 宣传片；教程使用 npm run tts:tutorial
unset MIMO_API_KEY
```

使用 `mimo-v2.5-tts`「白桦」音色。文案放在 API 的 `assistant` 消息，风格指令放在 `user` 消息；请求只发送至 MiMo 官方 HTTPS 端点。文案不变时复用本地音频，重新生成使用 `npm run tts:final -- --force`。

处理流程为首尾静音裁除、响度归一和有限语速调整。字幕跟随最终音频的实际时长。Key 仅从环境读取，不进入浏览器、渲染包或音频清单。

## 编辑入口

| 文件 | 用途 |
| --- | --- |
| `src/Promo.tsx` | 正式版画面、实录视频裁切、镜头与字幕 |
| `content/promo.zh-CN.json` | 54 秒时间轴、旁白、标题与文案 |
| `src/Teaser.tsx` | 10 秒样片与共用品牌样式 |
| `src/Tutorial.tsx` | 竖屏教学画面、局部镜头、触点与文件操作图示 |
| `content/tutorial.zh-CN.json` | 教程时间轴、旁白与字幕 |
| `content/tutorial-motions.json` | 教学镜头、高亮区域与分步提示 |
| `content/tutorial-game.sgf` | 完整示例主线与比赛事实 |
| `storyboard.zh-CN.md` | 完整分镜与拍摄要求 |
| `scripts/prepare-assets.mjs` | 本地图标、字体配套素材、二维码、音效 |
| `scripts/mimo_tts.py` | 配音请求、缓存与音频处理 |
| `scripts/render.mjs` | 渲染、关键帧与 SRT 输出 |
| `scripts/capture_avd.py` | 显式选择 AVD、控件定位、录屏与帧率标准化 |
| `scripts/capture_tutorial.py` | 教程操作实录与触点清单 |
| `scripts/tutorial_source.py` | 验证真实分析、定位转折 |
| `scripts/verify_tutorial.py` | 离线校验教程素材、旁白、分析证据和导出分支 |

正式版的片尾二维码指向 `https://github.com/zhzy0077/KataDroid/releases`。

## 实录素材

正式版使用本机的 `.local/public/captures/`；迁移工程时一并保留此目录及 `.local/public/audio/` 即可复用视频和旁白。运行 `prepare` 会保留它们。

| 素材 | 内容 | 时长 |
| --- | --- | ---: |
| `hero.png` | 实际完成历史分析后的 App 界面 | 静帧 |
| `offline.mp4` | 断网后开启 KataGo 分析 | 8 秒 |
| `preview.mp4` | 长按候选、预览三手、退出预览 | 9 秒 |
| `review.mp4` | 胜率图跳转 38 / 24 / 45 / 50 手 | 9 秒 |
| `tree.mp4` | 选择变化，在主线与分支间切换 | 9 秒 |
| `play.mp4` | 手动执黑，KataGo 自动执白应手 | 5.2 秒 |
| `menu.mp4` | 展开 SGF 导入、导出入口 | 3.5 秒 |

- 来自独立的 1080×2400 AVD，中文界面，CPU / b6c96。
- 历史初始分析预算为 100 visits；AI 自动落子的初始预算为 25 visits。当前局面可继续加深。
- 每个镜头只裁除系统栏，保留实际数值；剪辑与镜头放大在 Remotion 中完成。
- Android 的 screenrecord 为可变帧率；录屏脚本先补齐未变化画面的保持帧，再裁切时间段，输出 30 fps。播放速度保持 1×。
- 原始录屏、动作时刻、设备操作记录与构建验证信息保存在 `.local/capture/`。
- 原始截图探查中产生的中间素材留在本地，正式版只引用上表列出的文件。

检查设备并显式选择 serial：

```bash
adb devices -l
python3 scripts/capture_avd.py --serial <本次实际的AVD序列号> dump
python3 scripts/capture_avd.py --serial <本次实际的AVD序列号> screenshot --name reference
```

工具会验证目标为模拟器。录制方法为 Python 模块中的 `Avd.record()`；动作坐标以实际 UI 和棋盘几何为准。

## 文件与许可

原创工程代码沿用上级仓库 MIT 许可；Remotion 采用自定义许可证，个人及最多 3 名员工的营利组织可免费制作视频，包括商业用途。依赖与素材说明见 [LICENSES.md](LICENSES.md)。

字体由本地 Noto Sans SC 包提供；氛围音和落子声由代码原创合成。MiMo 按用户提供的免费服务使用。

`node_modules/`、音频缓存、原始录屏、AVD 状态与校验报告均已忽略；发布成片和字幕归档在 `videos/`。七手预览上限在 App 状态层统一处理，第三方 KataGo 源码保持不变。
