# 视频工程依赖与素材许可

| 项目 | 许可 / 来源 |
| --- | --- |
| 本工程原创代码、图形和合成音效 | 上级仓库 [MIT](../LICENSE) |
| Remotion 4.0.534 | [Remotion License](https://github.com/remotion-dev/remotion/blob/v4.0.534/LICENSE.md)，自定义源码可用许可 |
| React / React DOM | MIT，详见对应 npm 包 LICENSE |
| qrcode | MIT，本地生成 GitHub Releases 下载二维码 |
| jsQR | Apache-2.0，校验实际成片中的二维码 |
| TypeScript | Apache-2.0 |
| Noto Sans SC | SIL Open Font License 1.1，详见 `node_modules/@fontsource-variable/noto-sans-sc/LICENSE` |
| FFmpeg | 取决于构建组件的 LGPL / GPL 许可；使用本机安装版本及 Remotion 的渲染依赖 |
| Chromium | Chromium 与第三方组件各自的开源许可证 |
| App 图标与样片界面截图 | 上级 KataDroid 仓库，截图所示第三方标识保留原样 |
| 宣传片与教程界面、操作录屏 | 从最新源码构建的 App，在独立 Android Studio AVD 上录制 |
| 教程棋谱 `content/tutorial-game.sgf` | AlphaGo–Lee Sedol 第四局（2016-03-13）的落子与比赛事实，取自[公开 SGF](https://github.com/benjaminmantle/baduk-study-material/blob/master/10-whole-games/ai-era/alphago-vs-lee-sedol-2016/2016.03.13-Lee_Sedol-Alphago.sgf)；保留完整主线，清理原注释与旁支 |
| MiMo TTS | 外部服务，使用用户提供的账号和服务条款；[官方文档](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5) |

教程棋谱清理脚本为 `scripts/sgf_mainline.py`，来源下载地址、原始文件与清理后文件的 SHA-256 保存在忽略目录 `.local/capture/tutorial/source/`。胜率曲线来自 App 实际计算。

## Remotion 免费使用条件

上游许可证允许个人、最多 3 名员工的营利组织、非营利组织等免费制作视频与图片，包括商业用途。更大规模营利组织需要对应企业许可；衍生软件再分发亦有约束。Remotion 的自定义许可独立于本工程的 MIT 许可。

当前工程不接入付费云渲染或其他云端视频平台。依赖版本锁定在 `package-lock.json`，第三方完整许可证随对应包提供。
