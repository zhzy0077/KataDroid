import {copyFile, mkdir, readFile, writeFile, access} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname, resolve, join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {makeStoneSound, makeAmbience} from './sound.mjs';
import QRCode from 'qrcode';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(root, '..');
const publicDir = join(root, '.local/public');
await mkdir(join(publicDir, 'assets'), {recursive: true});
await mkdir(join(publicDir, 'audio'), {recursive: true});
const assets = [
  ['design/icon/katadroid-ai-1024.png', 'icon.png'],
  ['docs/images/analysis-zh.png', 'analysis-zh.png'],
  ['docs/images/settings-zh.png', 'settings-zh.png'],
  ['docs/images/engine-zh.png', 'engine-zh.png'],
];
for (const [source, target] of assets) {
  await copyFile(join(repo, source), join(publicDir, 'assets', target));
}
const screenshot = join(publicDir, 'assets/analysis-zh.png');
const header = await readFile(screenshot);
if (header.readUInt32BE(16) !== 1264 || header.readUInt32BE(20) !== 2584) {
  throw new Error('分析截图尺寸已变化，请重新检查棋盘与分析卡片的裁切坐标。');
}
for (const [filename, crop] of [
  ['board-zh.png', '1108:1108:80:416'],
  ['analysis-panel-zh.png', '1184:724:40:1700'],
]) {
  const result = spawnSync('ffmpeg', ['-y', '-hide_banner', '-loglevel', 'error',
    '-i', screenshot, '-vf', 'crop=' + crop, '-frames:v', '1',
    join(publicDir, 'assets', filename)], {encoding: 'utf8'});
  if (result.error) throw new Error('素材准备需要 PATH 中的 FFmpeg。', {cause: result.error});
  if (result.status !== 0) throw new Error(result.stderr);
}
await writeFile(join(publicDir, 'audio/stone.wav'), makeStoneSound());
await writeFile(join(publicDir, 'audio/ambience.wav'), makeAmbience(10));
await writeFile(join(publicDir, 'audio/ambience-full.wav'), makeAmbience(54));
const tutorialPlan = JSON.parse(await readFile(join(root, 'content/tutorial.zh-CN.json'), 'utf8'));
await writeFile(join(publicDir, 'audio/ambience-tutorial.wav'), makeAmbience(tutorialPlan.durationInFrames / tutorialPlan.fps));
await writeFile(join(publicDir, 'assets/download-qr.svg'), await QRCode.toString(
  'https://github.com/zhzy0077/KataDroid/releases',
  {type: 'svg', errorCorrectionLevel: 'M', margin: 4, width: 280,
    color: {dark: '#28634E', light: '#FFFEF8'}},
));
const manifestPath = join(publicDir, 'audio/manifest.json');
try { await access(manifestPath); }
catch { await writeFile(manifestPath, JSON.stringify({cues: []}, null, 2) + '\n'); }
const fullManifest = join(publicDir, 'audio/promo-manifest.json');
try { await access(fullManifest); }
catch { await writeFile(fullManifest, JSON.stringify({cues: []}, null, 2) + '\n'); }
const tutorialManifest = join(publicDir, 'audio/tutorial-manifest.json');
try { await access(tutorialManifest); }
catch { await writeFile(tutorialManifest, JSON.stringify({cues: []}, null, 2) + '\n'); }
console.log('已准备图标、中文界面裁切、下载二维码与原创音效。');
