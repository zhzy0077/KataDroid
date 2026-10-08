import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFile, writeFile} from 'node:fs/promises';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import jsQR from 'jsqr';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const still = process.argv.includes('--still');
const path = join(root, '.local/output', still ? 'promo-07-close.png' : 'katadroid-promo-zh-54s.mp4');
const seek = still ? [] : ['-ss', '51'];
const pixels = execFileSync('ffmpeg', [
  '-v', 'error', ...seek, '-i', path, '-vf', 'crop=224:224:758:685',
  '-frames:v', '1', '-pix_fmt', 'rgba', '-f', 'rawvideo', '-',
], {maxBuffer: 4 * 1024 * 1024});
assert.equal(pixels.length, 224 * 224 * 4);
const result = jsQR(new Uint8ClampedArray(pixels), 224, 224);
const expected = 'https://github.com/zhzy0077/KataDroid/releases';
assert.equal(result?.data, expected, '片尾二维码必须能从实际输出画面正确解码。');
console.log('下载二维码解码通过：' + expected);
if (!still) {
  const reportPath = join(root, '.local/output/promo-verification.json');
  const report = JSON.parse(await readFile(reportPath, 'utf8'));
  report.qrTarget = expected;
  report.qrDecode = 'passed';
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
}
