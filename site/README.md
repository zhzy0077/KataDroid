# KataDroid GitHub Pages

The production site preserves the approved prototype's layout and includes
Homepage and Technical navigation, an English/Chinese selector, real app
screenshots, the complete app architecture diagram (UI, records, SGF, preferences, caches,
benchmarks, KataGo, LiteRT and CPU/NPU backends), and measured results.
The HTML, CSS and JavaScript have no runtime dependencies or external fonts.

## Build and preview

From the repository root, with Python 3:

```bash
python3 tools/build_pages.py
python3 -m http.server 8000 --bind 127.0.0.1 --directory .local/pages-site
```

Open `http://localhost:8000/`. Use `?lang=zh#tech` for the Chinese technical page
or `?lang=en#home` for the English homepage. The language selector remembers
preferences when browser storage is available. Otherwise it continues to work
for the current visit. On first visit, browser language supplies the default.
Both page sections remain readable without JavaScript; navigation uses anchors.

The build stages an independent artifact in ignored `.local/pages-site/`, copying
only the used screenshots and icon from their existing source locations.
It checks local assets, fragment links, unique IDs, bilingual entries, accessible
label targets, and prototype-marker removal. No APKs, models, credentials or raw
test output enter the artifact. The site folder alone is source, not a deployable
artifact: deploy the output of `tools/build_pages.py`.

## Browser verification

```bash
uv venv .local/pages-venv
uv pip install --python .local/pages-venv/bin/python -r site/browser-requirements.txt
.local/pages-venv/bin/python -m playwright install chromium --no-shell
.local/pages-venv/bin/python tools/check_pages_browser.py
```

Browser checks cover both pages in both languages at desktop, tablet and mobile
widths, project-path asset loading, images, keyboard navigation, browser history,
language persistence, disabled storage, and readable content without JavaScript.
The workflow runs these checks before uploading a deployment artifact. Captures
stay under ignored `.local/pages-browser/`.

## Publish

The publishing branch is `gh-pages`. `.github/workflows/pages.yml` builds and
validates on relevant pushes and pull requests, but deploys only the `gh-pages`
branch. A manual workflow run can redeploy that branch. GitHub Pages must use
**Settings → Pages → Build and deployment → Source: GitHub Actions**. If the
`github-pages` environment restricts deployment branches, allow `gh-pages`.

After the site changes are committed and pushed to `gh-pages`, the workflow
publishes to `https://zhzy0077.github.io/KataDroid/`. Publishing configuration is
an external repository setting, separate from this build. The workflow follows
[GitHub's custom Pages workflow guidance](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

The download button links to the repository's Releases page. It does not promise
an APK exists before a release is published and never selects an unverified binary.

## Maintain

Edit `index.html`, `styles.css`, and `site.js`. Every translated HTML/SVG string
has paired `data-en` / `data-zh` attributes; names such as LiteRT, QNN and Neuron
remain unchanged. Update both copies together. The variation-tree screenshot currently exists only in English, with a Chinese
alt description identifying its language. Board, settings and engine screenshots
use the corresponding English or Chinese assets. Add available locale screenshots to `ASSETS` in
`tools/build_pages.py` and update the image selection in `site.js` when needed.

Performance claims come from `docs/models-and-benchmarks.md`, architecture from
`docs/katago-integration.md`, and vendor components from `docs/npu.md`. Preserve
benchmark conditions and distinguish exploratory results from completed audits.
Changes to the approved direction can be compared against
`design/pages-prototype/index.html`; the prototype is not deployed.

## Illustrated technical overview

`artwork/app-architecture.png` is a conceptual illustration generated with the
built-in imagegen tool. It represents the app, records, settings, analysis,
KataGo, LiteRT and CPU/vendor NPU paths. Its short component names are shared
across languages. All surrounding copy, alt descriptions and component legends
are bilingual. The precise diagram remains in an expandable architecture section.
The real app screenshots remain separate from the conceptual illustration.
The generation prompt is preserved in `artwork/architecture.prompt.md`.
