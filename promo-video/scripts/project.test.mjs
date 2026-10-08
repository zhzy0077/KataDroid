import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {findBrowser} from './browser.mjs';
import {makeStoneSound, makeAmbience} from './sound.mjs';
import {cuesToSrt} from './subtitles.mjs';

const plan = JSON.parse(await readFile(new URL('../content/teaser.zh-CN.json', import.meta.url), 'utf8'));

test('10 秒时间轴包含三个不重叠的配音区间', () => {
  assert.equal(plan.durationInFrames / plan.fps, 10);
  assert.equal(plan.width, 1920);
  assert.equal(plan.height, 1080);
  const ids = new Set();
  plan.clips.forEach((clip, index) => {
    assert.ok(!ids.has(clip.id)); ids.add(clip.id);
    assert.match(clip.id, /^[a-z]+$/);
    assert.ok(Number.isInteger(clip.from) && clip.from >= 0);
    const end = clip.from + Math.ceil(clip.slotSeconds * plan.fps);
    assert.ok(end <= (plan.clips[index + 1]?.from ?? plan.durationInFrames));
    assert.ok(clip.caption.length > 0 && clip.text.length > 0);
  });
});

const promo = JSON.parse(await readFile(new URL('../content/promo.zh-CN.json', import.meta.url), 'utf8'));

test('正式版含片尾共 54 秒，镜头连续且所有旁白预算都在时间轴内', () => {
  assert.equal(promo.durationInFrames / promo.fps, 54);
  assert.ok(promo.durationInFrames / promo.fps < 60);
  let end = 0;
  for (const scene of promo.scenes) {
    assert.equal(scene.from, end);
    assert.ok(scene.duration > 0);
    end += scene.duration;
  }
  assert.equal(end, promo.durationInFrames);
  assert.equal(new Set(promo.clips.map((clip) => clip.id)).size, promo.clips.length);
  promo.clips.forEach((clip, index) => {
    const slotEnd = clip.from + Math.ceil(clip.slotSeconds * promo.fps);
    assert.ok(slotEnd <= (promo.clips[index + 1]?.from ?? promo.durationInFrames));
    assert.ok(clip.caption.length <= 28, '字幕保持单行可读');
  });
});

const tutorial = JSON.parse(await readFile(new URL('../content/tutorial.zh-CN.json', import.meta.url), 'utf8'));
const lessonMotions = JSON.parse(await readFile(new URL('../content/tutorial-motions.json', import.meta.url), 'utf8'));

test('竖屏教程为 58 秒，镜头、标注和旁白都在各自时段内', () => {
  assert.equal(tutorial.width / tutorial.height, 9 / 16);
  assert.equal(tutorial.durationInFrames / tutorial.fps, 58);
  const preview = tutorial.clips.find((clip) => clip.id === 'preview');
  assert.match(preview.text, /七手/);
  assert.match(preview.caption, /七手/);
  assert.match(tutorial.scenes.find((scene) => scene.id === 'preview').tip, /七手/);
  let end = 0;
  for (const scene of tutorial.scenes) {
    assert.equal(scene.from, end);
    end += scene.duration;
    const motion = lessonMotions[scene.id];
    assert.ok(motion);
    assert.equal(motion.camera[0].at, 0);
    assert.equal(motion.steps[0].at, 0);
    for (const k of motion.camera) assert.ok(k.at <= scene.duration / tutorial.fps && k.scale > 0);
    for (const h of motion.highlights) assert.ok(h.from < h.to && h.to <= scene.duration / tutorial.fps);
  }
  assert.equal(end, tutorial.durationInFrames);
  tutorial.clips.forEach((clip, index) => {
    assert.ok(clip.from + Math.ceil(clip.slotSeconds * tutorial.fps) <= (tutorial.clips[index + 1]?.from ?? end));
    assert.ok(clip.caption.length <= 28);
  });
});

test('缺失的显式浏览器路径明确报错', () => {
  assert.throws(() => findBrowser({REMOTION_BROWSER_EXECUTABLE: '/not-a-real-browser', PATH: ''}), /不可执行/);
  assert.equal(findBrowser({PATH: ''}), undefined);
});

test('原创音效输出可重复、有效的 48 kHz PCM WAV', () => {
  const wav = makeStoneSound();
  assert.deepEqual(wav, makeStoneSound());
  assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
  assert.equal(wav.toString('ascii', 8, 12), 'WAVE');
  assert.equal(wav.readUInt32LE(24), 48000);
  assert.equal(wav.length, 44 + 48000 * 0.18 * 2);
  assert.equal(makeAmbience(10).length, 44 + 48000 * 10 * 2);
});

test('SRT 使用实际帧区间生成毫秒时间戳', () => {
  assert.equal(cuesToSrt([{from: 12, durationInFrames: 72, text: '把 KataGo 装进口袋'}], 30),
    '1\n00:00:00,400 --> 00:00:02,800\n把 KataGo 装进口袋\n');
  assert.throws(() => cuesToSrt([], 0), /positive/);
});
