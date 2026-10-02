'use strict';

const tabs = [...document.querySelectorAll('[role="tab"]')];
const panels = [...document.querySelectorAll('[role="tabpanel"]')];
const languageButtons = [...document.querySelectorAll('[data-language]')];
let language = 'en';

function updateTitle() {
  const tech = location.hash === '#tech';
  document.title = language === 'zh'
    ? `KataDroid — ${tech ? '技术' : 'KataGo on Mobile'}`
    : `KataDroid — ${tech ? 'Technical' : 'KataGo on Mobile'}`;
}

function showPage() {
  const selected = location.hash === '#tech' ? 'tech-tab' : 'home-tab';
  for (const tab of tabs) {
    const active = tab.id === selected;
    tab.setAttribute('aria-selected', String(active));
    tab.tabIndex = active ? 0 : -1;
    document.getElementById(tab.getAttribute('aria-controls')).hidden = !active;
  }
  updateTitle();
}

function navigate(page, focus = false) {
  const hash = page === 'tech' ? '#tech' : '#home';
  if (location.hash !== hash) history.pushState(null, '', hash);
  showPage();
  window.scrollTo(0, 0);
  if (focus) tabs[page === 'tech' ? 1 : 0].focus();
}

for (const link of document.querySelectorAll('a[href="#home"], a[href="#tech"]')) {
  link.addEventListener('click', event => {
    // Preserve the browser's open-in-new-tab and modified-click behavior.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(link.hash === '#tech' ? 'tech' : 'home');
  });
}

for (const [index, tab] of tabs.entries()) {
  tab.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - index;
    navigate(next === 1 ? 'tech' : 'home', true);
  });
}

function restorePage() {
  showPage();
  if (['#home', '#tech'].includes(location.hash)) window.scrollTo(0, 0);
}
window.addEventListener('hashchange', restorePage);
window.addEventListener('popstate', restorePage);

function setLanguage(next, remember = true) {
  language = next === 'zh' ? 'zh' : 'en';
  document.documentElement.lang = language === 'zh' ? 'zh-CN' : 'en';
  for (const element of document.querySelectorAll('[data-en]')) {
    // Copy comes only from this site's authored translation attributes.
    // SVG text uses textContent; HTML copy supports the approved inline emphasis.
    if (element.namespaceURI === 'http://www.w3.org/2000/svg') {
      element.textContent = element.dataset[language];
    } else {
      element.innerHTML = element.dataset[language];
    }
  }
  for (const button of languageButtons) {
    button.setAttribute('aria-pressed', String(button.dataset.language === language));
  }
  const board = document.getElementById('app-screen');
  board.src = `assets/analysis-${language}.png`;
  board.alt = language === 'zh'
    ? 'KataDroid 分析 AlphaGo 对李世石第四局第 50 手，显示候选着法与胜率'
    : 'KataDroid analyzing AlphaGo vs Lee Sedol, Game 4, at move 50';
  for (const image of document.querySelectorAll('[data-screen]')) {
    const screen = image.dataset.screen;
    const imageLanguage = screen === 'variations' || screen === 'engine' ? 'en' : language;
    image.src = `assets/${screen}-${imageLanguage}.png`;
    image.alt = image.dataset[language === 'zh' ? 'altZh' : 'altEn'];
    if (language === 'zh' && imageLanguage === 'en') image.alt += '（英文界面）';
  }
  for (const image of document.querySelectorAll('[data-localized-image]')) {
    image.src = `assets/${image.dataset.localizedImage}-${language}.png`;
    image.alt = image.dataset[language === 'zh' ? 'altZh' : 'altEn'];
  }
  for (const image of document.querySelectorAll('[data-localized-alt]')) {
    image.alt = image.dataset[language === 'zh' ? 'altZh' : 'altEn'];
  }
  document.querySelector('[data-guide]').href =
    'https://github.com/zhzy0077/KataDroid/blob/main/docs/' +
    (language === 'zh' ? 'usage.zh-CN.md' : 'android-ui.md');
  document.querySelector('meta[name="description"]').content = language === 'zh'
    ? 'KataDroid 为 Android 提供离线 KataGo 分析、变化树与 SGF 编辑。了解应用、集成架构与实测搜索性能。'
    : 'KataDroid brings offline KataGo analysis, variations and SGF editing to Android. Explore the app, integration architecture and measured search performance.';
  updateTitle();
  if (remember) {
    try { localStorage.setItem('katadroid-site-language', language); } catch { /* Storage may be disabled. */ }
    const url = new URL(location.href);
    url.searchParams.set('lang', language);
    history.replaceState(null, '', url);
  }
}

for (const button of languageButtons) {
  button.addEventListener('click', () => setLanguage(button.dataset.language));
}

let preferred = navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en';
try { preferred = localStorage.getItem('katadroid-site-language') || preferred; } catch { /* Use browser language. */ }
preferred = new URLSearchParams(location.search).get('lang') || preferred;
setLanguage(preferred, false);
showPage();
window.addEventListener('load', () => {
  if (['#home', '#tech'].includes(location.hash)) window.scrollTo(0, 0);
});
