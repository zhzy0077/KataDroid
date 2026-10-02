#!/usr/bin/env python3
"""Check the built website in Chromium, including GitHub project-path hosting.

Requires playwright==1.63.0 and `python -m playwright install chromium --no-shell`.
All reports and captures remain in ignored .local/.
"""
from __future__ import annotations

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading

from playwright.sync_api import sync_playwright, expect
from build_pages import ROOT, build


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args: object) -> None:
        pass


def main() -> None:
    host = ROOT / '.local' / 'pages-browser'
    build(host / 'KataDroid')
    captures = host / 'captures'
    captures.mkdir(exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(host)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}/KataDroid/'
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel='chromium', headless=True)
            failures: list[str] = []
            context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='en-US')
            page = context.new_page()
            page.on('pageerror', lambda error: failures.append(str(error)))
            page.on('response', lambda response: failures.append(f'HTTP {response.status}: {response.url}')
                    if response.status >= 400 else None)
            for width in (1440, 768, 390, 360):
                page.set_viewport_size({'width': width, 'height': 900})
                for locale in ('en', 'zh'):
                    for section in ('home', 'tech'):
                        page.goto(f'{base}?lang={locale}#{section}', wait_until='networkidle')
                        expect(page.locator('html')).to_have_attribute('lang', 'zh-CN' if locale == 'zh' else 'en')
                        expect(page.locator(f'#{section}-panel')).to_be_visible()
                        expect(page.locator('#tech-panel' if section == 'home' else '#home-panel')).to_be_hidden()
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Overflow: {width}/{locale}/{section}'
                        assert page.evaluate('Array.from(document.images).filter(i=>i.getClientRects().length && i.loading!=="lazy").every(i=>i.complete && i.naturalWidth>0)'), 'Broken visible image'
                        # A scrollable diagram retains legible labels, while the page itself stays within the viewport.
                        if section == 'tech':
                            expect(page.locator('.architecture-art img')).to_be_visible()
                            page.locator('.architecture-details > summary').click()
                            expect(page.locator('.diagram')).to_be_visible()
                            page.locator('.architecture-details > summary').click()
                            assert page.locator('.diagram text').filter(has_text='NPU').count() >= 4
                        if width in (1440, 390):
                            page.screenshot(path=str(captures / f'{section}-{locale}-{width}.png'), full_page=True)
            page.set_viewport_size({'width': 1440, 'height': 1000})
            page.goto(f'{base}?lang=en#home')
            page.locator('#tech-tab').click()
            expect(page.locator('#tech-panel')).to_be_visible()
            page.locator('[data-language="zh"]').click()
            expect(page.locator('#tech-tab')).to_have_text('技术')
            expect(page.locator('.system-diagram figcaption')).to_have_text('复盘与对弈应用，背后是本地引擎。')
            page.locator('#home-tab').click()
            expect(page.locator('#home-panel')).to_be_visible()
            page.reload()
            expect(page.locator('html')).to_have_attribute('lang', 'zh-CN')
            # Storage preference survives a visit without a language query.
            page.goto(f'{base}#home')
            expect(page.locator('html')).to_have_attribute('lang', 'zh-CN')
            page.locator('#home-tab').focus()
            page.keyboard.press('ArrowRight')
            expect(page.locator('#tech-tab')).to_be_focused()
            expect(page.locator('#tech-panel')).to_be_visible()
            page.keyboard.press('Home')
            expect(page.locator('#home-tab')).to_be_focused()
            expect(page.locator('#home-panel')).to_be_visible()
            page.go_back()
            expect(page.locator('#tech-panel')).to_be_visible()
            page.go_forward()
            expect(page.locator('#home-panel')).to_be_visible()
            # Exercise every lazy image and localized screenshot selection.
            page.locator('.screenshots').scroll_into_view_if_needed()
            for image in page.locator('.screen-grid img').all():
                image.scroll_into_view_if_needed()
                expect(image).to_be_visible()
                page.wait_for_function('(img) => img.complete && img.naturalWidth > 0', arg=image.element_handle())
            expect(page.locator('.btn').first).to_have_attribute('href', 'https://github.com/zhzy0077/KataDroid/releases')
            context.close()
            no_js = browser.new_context(java_script_enabled=False)
            fallback = no_js.new_page()
            fallback.goto(base)
            expect(fallback.locator('#home-panel')).to_be_visible()
            expect(fallback.locator('#tech-panel')).to_be_visible()
            no_js.close()
            restricted = browser.new_context()
            restricted.add_init_script("Object.defineProperty(window, 'localStorage', {get(){throw new Error('disabled')}})")
            fallback = restricted.new_page()
            fallback.goto(f'{base}?lang=en')
            fallback.locator('[data-language="zh"]').click()
            expect(fallback.locator('html')).to_have_attribute('lang', 'zh-CN')
            restricted.close()
            browser.close()
            assert not failures, '\n'.join(failures)
        print('Passed: 16 locale/page/viewport combinations, image loading, tabs, keyboard navigation, '
              'browser history, saved language, blocked storage, no-JS fallback and /KataDroid/ hosting.')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
