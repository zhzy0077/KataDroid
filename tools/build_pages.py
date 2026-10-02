#!/usr/bin/env python3
"""Stage and validate a self-contained GitHub Pages artifact (standard library only)."""
from __future__ import annotations

import argparse
from html.parser import HTMLParser
from pathlib import Path
import shutil
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    'katadroid-icon.png': 'design/icon/katadroid-ai-512.png',
    'app-architecture.png': 'site/artwork/app-architecture.png',
    **{f'{name}.png': f'docs/images/{name}.png' for name in (
        'analysis-en', 'analysis-zh', 'variations-en', 'settings-en',
        'settings-zh', 'engine-en',
    )},
}


class SiteValidator(HTMLParser):
    def __init__(self, output: Path):
        super().__init__(convert_charrefs=True)
        self.output = output
        self.ids: set[str] = set()
        self.references: list[str] = []
        self.label_targets: list[str] = []
        self.translations = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if element_id := values.get('id'):
            if element_id in self.ids:
                raise ValueError(f'Duplicate ID: {element_id}')
            self.ids.add(element_id)
        if 'data-en' in values:
            if not values.get('data-zh'):
                raise ValueError(f'Missing Chinese translation: {tag}')
            self.translations += 1
        if tag == 'img' and 'alt' not in values:
            raise ValueError('Image is missing alt text')
        for key in ('href', 'src'):
            if value := values.get(key):
                self.references.append(value)
        for key in ('aria-controls', 'aria-labelledby'):
            self.label_targets.extend((values.get(key) or '').split())

    def validate(self) -> None:
        for reference in self.references:
            url = urlsplit(reference)
            if url.scheme or url.netloc:
                if url.scheme != 'https':
                    raise ValueError(f'Unexpected external URL: {reference}')
                continue
            if url.path:
                target = (self.output / url.path).resolve()
                if not target.is_relative_to(self.output) or not target.is_file():
                    raise ValueError(f'Missing or non-contained asset: {reference}')
            if url.fragment and url.fragment not in self.ids:
                raise ValueError(f'Broken anchor: {reference}')
        for target in self.label_targets:
            if target not in self.ids:
                raise ValueError(f'Broken accessibility reference: {target}')
        if self.translations < 50:
            raise ValueError('Expected bilingual page and diagram content')


def build(output: Path) -> None:
    output = output.resolve()
    # Never erase a caller's directory: overwrite only the known site files.
    if output == ROOT or ROOT.is_relative_to(output):
        raise ValueError('Output must not be the repository or an ancestor')
    output.mkdir(parents=True, exist_ok=True)
    for name in ('index.html', 'styles.css', 'site.js'):
        shutil.copyfile(ROOT / 'site' / name, output / name)
    (output / 'assets').mkdir(exist_ok=True)
    for name, source in ASSETS.items():
        shutil.copyfile(ROOT / source, output / 'assets' / name)
    (output / '.nojekyll').write_text('')
    html = (output / 'index.html').read_text()
    if 'DESIGN PREVIEW' in html or '../../docs/' in html:
        raise ValueError('Prototype content leaked into production')
    validator = SiteValidator(output)
    validator.feed(html)
    validator.validate()
    # Include both locales used by JS, not just the initially visible images.
    for name in ASSETS:
        if not (output / 'assets' / name).is_file():
            raise ValueError(f'Missing locale asset: {name}')
    print(f'Built GitHub Pages artifact; verified {len(ASSETS)} assets, '
          f'{validator.translations} bilingual entries, anchors and accessibility references.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.local' / 'pages-site')
    build(parser.parse_args().output)
