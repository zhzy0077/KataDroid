import React from 'react';
import {Composition, staticFile, type CalculateMetadataFunction} from 'remotion';
import '@fontsource-variable/noto-sans-sc';
import plan from '../content/teaser.zh-CN.json';
import promo from '../content/promo.zh-CN.json';
import {Teaser, type TeaserProps} from './Teaser';
import {Promo} from './Promo';
import tutorial from '../content/tutorial.zh-CN.json';
import {Tutorial, type TutorialProps} from './Tutorial';

const metadata = (config: {durationInFrames: number; manifest?: string}): CalculateMetadataFunction<TeaserProps> => async ({props}) => {
  const response = await fetch(staticFile(config.manifest ?? 'audio/manifest.json'));
  if (!response.ok) throw new Error('请先运行 npm run prepare。');
  const manifest = await response.json();
  if (!Array.isArray(manifest.cues)) throw new Error('配音清单格式无效。');
  for (const cue of manifest.cues) {
    if (typeof cue.id !== 'string' || typeof cue.text !== 'string' ||
        typeof cue.src !== 'string' || !cue.src.startsWith('audio/') || cue.src.includes('..') ||
        !Number.isInteger(cue.from) || cue.from < 0 ||
        !Number.isInteger(cue.durationInFrames) || cue.durationInFrames < 1 ||
        cue.from + cue.durationInFrames > config.durationInFrames) {
      throw new Error('配音片段超出时间轴或格式无效。');
    }
  }
  return {props: {...props, cues: manifest.cues}};
};

const tutorialMetadata: CalculateMetadataFunction<TutorialProps> = async (args) => {
  const base = await metadata(tutorial)(args);
  const response = await fetch(staticFile('tutorial/captures/touches.json'));
  if (!response.ok) throw new Error('请先准备教程实录与点按时间轴。');
  const touches = await response.json();
  return {props: {...args.props, ...base.props, touches}};
};

export const VideoRoot: React.FC = () => <>
  <Composition
    id={plan.id}
    component={Teaser}
    width={plan.width}
    height={plan.height}
    fps={plan.fps}
    durationInFrames={plan.durationInFrames}
    defaultProps={{cues: [], captions: true}}
    calculateMetadata={metadata(plan)}
  />
  <Composition
    id={promo.id}
    component={Promo}
    width={promo.width}
    height={promo.height}
    fps={promo.fps}
    durationInFrames={promo.durationInFrames}
    defaultProps={{cues: [], captions: true}}
    calculateMetadata={metadata(promo)}
  />
  <Composition
    id={tutorial.id}
    component={Tutorial}
    width={tutorial.width}
    height={tutorial.height}
    fps={tutorial.fps}
    durationInFrames={tutorial.durationInFrames}
    defaultProps={{cues: [], captions: true, touches: {}}}
    calculateMetadata={tutorialMetadata}
  />
</>;
