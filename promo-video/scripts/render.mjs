import {bundle} from '@remotion/bundler';
import {renderMedia, renderStill, selectComposition} from '@remotion/renderer';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {findBrowser} from './browser.mjs';
import {cuesToSrt} from './subtitles.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const final = process.argv.includes('--final');
const tutorial = process.argv.includes('--tutorial');
if (final && tutorial) throw new Error('请一次选择一个视频版本。');
const kind = tutorial ? 'tutorial' : final ? 'promo' : 'teaser';
const plan = JSON.parse(await readFile(join(root, 'content/' + kind + '.zh-CN.json'), 'utf8'));
const stem = final || tutorial ? plan.slug : 'katadroid-teaser-zh-1080p';
const output = join(root, '.local/output');
await mkdir(output, {recursive: true});
const manifest = JSON.parse(await readFile(join(root, '.local/public', plan.manifest ?? 'audio/manifest.json'), 'utf8'));
if (final && plan.durationInFrames / plan.fps >= 60) throw new Error('正式版必须小于 60 秒。');
if (final) {
  for (const name of ['offline', 'preview', 'review', 'tree', 'play', 'menu']) {
    await readFile(join(root, '.local/public/captures', name + '.mp4'));
  }
}
if (tutorial) {
  if (plan.durationInFrames / plan.fps >= 60) throw new Error('教程控制在一分钟以内。');
  for (const name of ['hero.png', 'imported.png', 'import-menu.mp4', 'analysis.mp4', 'review.mp4', 'preview.mp4', 'branches.mp4', 'export-menu.mp4']) {
    await readFile(join(root, '.local/public/tutorial/captures', name));
  }
}
const stillsOnly = process.argv.includes('--stills-only');
if (!stillsOnly && manifest.cues.length !== plan.clips.length && !process.argv.includes('--allow-silent')) {
  throw new Error('请先生成对应版本的完整配音；排版预览可显式添加 --allow-silent。');
}
console.log('打包本地视频工程…');
const serveUrl = await bundle({
  entryPoint: join(root, 'src/index.ts'),
  publicDir: join(root, '.local/public'),
  outDir: join(root, '.local/bundle'),
  enableCaching: true,
});
const browserExecutable = findBrowser();
const composition = await selectComposition({serveUrl, id: plan.id, browserExecutable, logLevel: 'warn'});
const stills = tutorial ? [[45, '01-curve'], [156, '02-import'], [444, '03-analysis'], [675, '04-review'], [855, '05-compare'], [1080, '06-preview'], [1494, '07-branches'], [1710, '08-save']] : final ? [[90, '01-intro'], [285, '02-offline'], [573, '03-preview'], [810, '04-review'], [1128, '05-tree'], [1380, '06-sgf'], [1530, '07-close']] : [[64, '01-hook'], [172, '02-analysis'], [270, '03-close']];
const prefix = kind + '-';
for (const [frame, name] of stills) {
  await renderStill({
    serveUrl, composition, browserExecutable, frame, imageFormat: 'png',
    output: join(output, prefix + name + '.png'), logLevel: 'warn',
  });
  console.log('分镜预览：' + prefix + name + '.png');
}
if (!stillsOnly) {
  let previous = -1;
  await renderMedia({
    serveUrl, composition, browserExecutable,
    outputLocation: join(output, stem + '.mp4'),
    codec: 'h264', pixelFormat: 'yuv420p', colorSpace: 'bt709', crf: 18,
    audioCodec: 'aac', audioBitrate: '192k', sampleRate: 48000,
    concurrency: 4, overwrite: true, logLevel: 'warn',
    metadata: {title: tutorial ? plan.title : final ? 'KataDroid 中文宣传片 · 54 秒' : 'KataDroid 中文宣传片 · 10 秒样片', comment: '真实项目界面 / MiMo 中文旁白'},
    onProgress: ({progress}) => {
      const percent = Math.floor(progress * 100 / 20) * 20;
      if (percent !== previous) {previous = percent; console.log('渲染 ' + percent + '%');}
    },
  });
  await writeFile(join(output, kind + '.zh-CN.srt'), cuesToSrt(manifest.cues, plan.fps));
  if (tutorial) {
    const chapters = plan.scenes.map((scene) => {
      const second = scene.from / plan.fps;
      return String(Math.floor(second / 60)).padStart(2, '0') + ':' + String(second % 60).padStart(2, '0') + ' ' + scene.title;
    }).join('\n') + '\n';
    await writeFile(join(output, 'tutorial.chapters.txt'), chapters);
  }
  console.log('成片：.local/output/' + stem + '.mp4');
}
