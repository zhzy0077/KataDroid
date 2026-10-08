import React from 'react';
import {AbsoluteFill, Audio, Easing, Img, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame} from 'remotion';
import plan from '../content/tutorial.zh-CN.json';
import motionPlan from '../content/tutorial-motions.json';
import {C, type TeaserProps} from './Teaser';

type Touch = {at: number; type: string; x: number; y: number; label: string; duration: number};
export type TutorialProps = TeaserProps & {touches: Record<string, Touch[]>};
type Scene = (typeof plan.scenes)[number];
type Camera = {at: number; x: number; y: number; scale: number};
type Motion = {camera: Camera[]; steps: {at: number; text: string}[];
  highlights: {from: number; to: number; x: number; y: number; width: number; height: number}[]};
const motions = motionPlan as Record<string, Motion>;
const clamp = {extrapolateLeft: 'clamp' as const, extrapolateRight: 'clamp' as const};
const STAGE = {left: 64, top: 250, width: 952, height: 1380};
const media = (filename: string) => staticFile('tutorial/captures/' + filename);

const cameraAt = (frames: Camera[], seconds: number) => {
  if (frames.length === 1) return frames[0];
  const times = frames.map((key) => key.at);
  const value = (key: 'x' | 'y' | 'scale') => interpolate(seconds, times, frames.map((v) => v[key]), {
    ...clamp, easing: Easing.inOut(Easing.cubic),
  });
  return {x: value('x'), y: value('y'), scale: value('scale')};
};

const TapMarks: React.FC<{events: Touch[]; seconds: number}> = ({events, seconds}) => <>
  {events.map((event, i) => {
    const age = seconds - event.at;
    const length = event.type === 'long' ? event.duration + .7 : 1.1;
    if (age < -.12 || age > length) return null;
    const opacity = interpolate(age, [-.12, .1, length - .25, length], [0, 1, 1, 0], clamp);
    const scale = interpolate(age, [0, .3, length], [.78, 1, 1.25], clamp);
    return <div key={i} style={{
      position: 'absolute', left: event.x - 49, top: event.y - 49, width: 98, height: 98,
      border: '7px solid #D7A84B', borderRadius: '50%', background: '#F1CA6133',
      transform: `scale(${scale})`, opacity,
      boxShadow: '0 0 0 3px #fff9',
    }}/>;
  })}
</>;

const Screen: React.FC<{scene: Scene; touches: TutorialProps['touches']; source?: string; from?: number}> = ({scene, touches, source, from = 0}) => {
  const localFrame = useCurrentFrame();
  const seconds = (localFrame + from) / plan.fps;
  const motion = motions[scene.id];
  const camera = cameraAt(motion.camera, seconds);
  const path = source ?? scene.media;
  const clip = path.replace(/\.[^.]+$/, '');
  const video = path.endsWith('.mp4');
  return <div style={{position: 'absolute', ...STAGE, overflow: 'hidden', borderRadius: 26,
    background: '#E9EEE3', border: '1px solid #D8E0D1'}}>
    <div style={{
      position: 'absolute', width: 1080, height: 2272, transformOrigin: '0 0',
      transform: `translate(${STAGE.width / 2 - camera.x * camera.scale}px, ${STAGE.height / 2 - camera.y * camera.scale}px) scale(${camera.scale})`,
      borderRadius: 28, overflow: 'hidden', background: C.paper,
    }}>
      {video ? <OffthreadVideo src={media(path)} muted style={{width: 1080, height: 2272, display: 'block'}}/> :
        <Img src={media(path)} style={{width: 1080, height: 2272, display: 'block'}}/>}
      {motion.highlights.map((area, index) => {
        const opacity = interpolate(seconds, [area.from, area.from + .25, area.to - .25, area.to], [0, 1, 1, 0], clamp);
        return <div key={index} style={{
          position: 'absolute', left: area.x, top: area.y, width: area.width, height: area.height,
          border: '5px solid #D2A247', borderRadius: 24, opacity, background: '#F5D77A10',
        }}/>;
      })}
      {video && <TapMarks events={touches[clip] ?? []} seconds={localFrame / plan.fps}/>}
    </div>
    <div style={{position: 'absolute', top: 15, left: 18, fontSize: 21, color: C.green,
      padding: '5px 11px', borderRadius: 12, background: '#F9FAF3ee'}}>
      {video ? '原速操作实录' : '实际 App 界面'}
    </div>
  </div>;
};

const FileStep: React.FC<{exporting?: boolean}> = ({exporting = false}) => <div style={{
  position: 'absolute', ...STAGE, borderRadius: 26, background: '#EDF2E5',
  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  padding: 55, textAlign: 'center', color: C.ink,
}}>
  <div style={{fontSize: 27, color: C.green, marginBottom: 64, letterSpacing: 4}}>系统文件选择器</div>
  <div style={{width: 260, height: 318, border: '8px solid ' + C.green, borderRadius: 26, display: 'flex',
    alignItems: 'center', justifyContent: 'center', color: C.green, fontSize: 61, fontWeight: 750, marginBottom: 66}}>.sgf</div>
  <div style={{fontSize: 52, fontWeight: 760, marginBottom: 26}}>{exporting ? '选择保存位置' : '选择棋谱文件'}</div>
  <div style={{fontSize: 34, lineHeight: 1.7, color: C.muted}}>
    {exporting ? <>点击保存<br/>导出整份棋谱与全部变化</> : <>选中你要打开的 .sgf 文件<br/>确认后自动返回棋盘</>}
  </div>
  <div style={{fontSize: 25, marginTop: 74, color: C.green}}>操作指引</div>
</div>;

const FileScreens: React.FC<{scene: Scene; touches: TutorialProps['touches']}> = ({scene, touches}) => scene.id === 'import' ? <>
  <Sequence durationInFrames={105}><Screen scene={scene} touches={touches}/></Sequence>
  <Sequence from={105} durationInFrames={63}><FileStep/></Sequence>
  <Sequence from={168} durationInFrames={42}><Screen scene={scene} touches={touches} source="imported.png" from={168}/></Sequence>
</> : <>
  <Sequence durationInFrames={96}><Screen scene={scene} touches={touches}/></Sequence>
  <Sequence from={96} durationInFrames={84}><FileStep exporting/></Sequence>
</>;

const Lesson: React.FC<{scene: Scene; touches: TutorialProps['touches']}> = ({scene, touches}) => {
  const f = useCurrentFrame();
  const motion = motions[scene.id];
  const step = [...motion.steps].reverse().find((s) => f >= s.at * plan.fps)?.text ?? scene.tip;
  return <AbsoluteFill>
    <div style={{position: 'absolute', top: 49, right: 64, color: C.green, fontSize: 27, fontWeight: 570}}>{scene.eyebrow}</div>
    <div style={{position: 'absolute', left: 64, top: 111, fontSize: 61, fontWeight: 780,
      letterSpacing: -1.6, opacity: interpolate(f, [0, 10], [.45, 1], clamp)}}>{scene.title}</div>
    <div style={{position: 'absolute', left: 67, top: 199, color: C.muted, fontSize: 25}}>{scene.tip}</div>
    {scene.id === 'import' || scene.id === 'export' ? <FileScreens scene={scene} touches={touches}/> : <Screen scene={scene} touches={touches}/>}
    <div style={{position: 'absolute', left: 64, top: 1657, width: 952, height: 111,
      borderRadius: 22, background: '#E4EDD9', display: 'flex', alignItems: 'center', padding: '14px 27px', gap: 20}}>
      <div style={{width: 9, height: 49, borderRadius: 5, background: C.green, flexShrink: 0}}/>
      <div style={{fontSize: 32, fontWeight: 570, lineHeight: 1.35, color: C.green}}>{step}</div>
    </div>
  </AbsoluteFill>;
};

export const Tutorial: React.FC<TutorialProps> = ({cues, captions, touches}) => {
  const frame = useCurrentFrame();
  const active = cues.find((cue) => frame >= cue.from && frame < cue.from + cue.durationInFrames);
  return <AbsoluteFill style={{fontFamily: '"Noto Sans SC Variable", sans-serif', color: C.ink,
    background: C.paper, overflow: 'hidden', WebkitFontSmoothing: 'antialiased'}}>
    <div style={{position: 'absolute', top: 42, left: 64, display: 'flex', alignItems: 'center', gap: 15}}>
      <Img src={staticFile('assets/icon.png')} style={{width: 49, height: 49, borderRadius: 13}}/>
      <span style={{fontSize: 35, fontWeight: 750, letterSpacing: -.8}}>KataDroid</span>
    </div>
    {plan.scenes.map((scene) => <Sequence key={scene.id} from={scene.from} durationInFrames={scene.duration}>
      <Lesson scene={scene} touches={touches}/>
    </Sequence>)}
    {captions && active && <div style={{position: 'absolute', left: 74, right: 74, top: 1803,
      minHeight: 78, display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center',
      fontSize: 35, fontWeight: 580, lineHeight: 1.5}}>{active.text}</div>}
    <div style={{position: 'absolute', left: 64, right: 64, bottom: 20, height: 3, background: '#d8e1cf'}}>
      <div style={{height: '100%', width: `${Math.min(100, frame / (plan.durationInFrames - 1) * 100)}%`, background: C.green}}/>
    </div>
    <Audio src={staticFile('audio/ambience-tutorial.wav')} volume={(f) =>
      .09 * Math.min(1, f / 45, (plan.durationInFrames - f) / 70)}/>
    {cues.map((cue) => <Sequence key={cue.id} from={cue.from} durationInFrames={cue.durationInFrames}>
      <Audio src={staticFile(cue.src)}/>
    </Sequence>)}
  </AbsoluteFill>;
};
