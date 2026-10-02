# GitHub Pages design prototype

Open `index.html` in a browser. No build step, server or external font is required.
The page reuses the existing app icon and English/Chinese screenshots from this
repository. All asset paths are relative, suitable for a future project Pages site.

Design direction: warm paper, serif editorial headlines, terracotta calls to
action, a board-grid backdrop, and an actual app screenshot. Sections cover the
app's offline analysis, variations, SGF, play modes and runtime compatibility.
The language button switches the copy and app screenshot between English and
Simplified Chinese. Navigation and release links are real repository destinations.

This is a review artifact only. No Pages workflow, production entry point,
publishing configuration or release download availability is assumed. The release
button opens the release list rather than promising a specific APK.

Review desktop and mobile layout, tone, colors, and section order before converting
this direction into a production site on the intended publishing branch. The current
workspace is a detached checkout; this prototype does not change branch state.

## Second review: two tabs

- **Homepage / 首页** retains the visual direction, adds an app screenshot gallery,
  and links the download action to GitHub Releases.
- **Perf & Tech / 性能与技术** explains the official KataGo/JNI/LiteRT pipeline,
  model conversion, buffer reuse, model and backend choices, cancellation, caches,
  benchmark methodology, and documented AVD/MediaTek/Snapdragon measurements.
- Top-right **EN / 中文** controls translate both tabs, swap available localized
  screenshots, update the guide link, and remember the choice when storage is
  available. The variation-tree screenshot currently exists only in English.
- `#home` and `#tech` open each tab directly. The two page controls sit in the original top-right navigation, with no separate
  tab bar. They support arrow keys,
  Home/End, accessible selection state, and browser history.

Performance sources are `docs/katago-integration.md`, `docs/model-conversion.md`,
and `docs/models-and-benchmarks.md`. The prototype distinguishes host-dependent
AVD CPU results, audited MT6989 NPU results, and exploratory Snapdragon results.
Production publishing remains pending design review.

## Architecture diagram

The technical page includes an inline SVG diagram with localized labels and an
accessible text description. It follows Compose → AnalysisController → JNI →
KataGo → LiteRT CompiledModel, then branches into CPU, Qualcomm compiler/dispatch
plugins with QNN/QAIRT and FastRPC, or experimental MediaTek plugins with Neuron
and AHWB/DMA-BUF. A return arrow identifies the five network output heads.
Small screens scroll the diagram horizontally to preserve readable labels.
The diagram uses no remote scripts, image generation, or external rendering tools.
