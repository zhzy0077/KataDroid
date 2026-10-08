import React from 'react';
import {
  AbsoluteFill, Audio, Easing, Img, interpolate, OffthreadVideo,
  Sequence, spring, staticFile, useCurrentFrame, useVideoConfig,
} from 'remotion';
import plan from '../content/promo.zh-CN.json';
import {Arrow, C, Frame, type TeaserProps} from './Teaser';

const clamp = {extrapolateLeft: 'clamp' as const, extrapolateRight: 'clamp' as const};
const easing = Easing.bezier(0.22, 1, 0.36, 1);
type Scene = (typeof plan.scenes)[number];
type Region = {x: number; y: number; width: number; height: number};
const BOARD: Region = {x: 32, y: 313, width: 1016, height: 1016};
const PANEL: Region = {x: 32, y: 1468, width: 1016, height: 656};

const Footage: React.FC<{name: string; style: React.CSSProperties}> = ({name, style}) => (
  <OffthreadVideo muted src={staticFile('captures/' + name + '.mp4')} style={style}/>
);

const Viewport: React.FC<{
  media: string; region: Region; width: number; left: number; top: number;
  animated?: boolean;
}> = ({media, region, width, left, top, animated = false}) => {
  const frame = useCurrentFrame();
  const scale = width / region.width;
  const entry = animated ? interpolate(frame, [0, 22], [26, 0], {...clamp, easing}) : 0;
  return <div style={{
    position: 'absolute', left, top, width, height: region.height * scale,
    overflow: 'hidden', borderRadius: 31, background: C.surface, lineHeight: 0,
    transform: 'translateY(' + entry + 'px)',
    boxShadow: '10px 24px 70px #263b2e22',
    border: '1px solid #d4dfc9',
  }}>
    <Footage name={media} style={{
      position: 'absolute', display: 'block', width: 1080 * scale, height: 2272 * scale,
      left: -region.x * scale, top: -region.y * scale,
    }}/>
  </div>;
};

const Phone: React.FC<{media?: string; still?: boolean; play?: boolean}> = ({media, still = false, play = false}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const enter = spring({frame: f, fps, config: {damping: 30, stiffness: 105}});
  const style: React.CSSProperties = {display: 'block', width: 384, height: 2272 * 384 / 1080};
  return <div style={{
    position: 'absolute', left: 1312, top: 126, width: 384, boxSizing: 'content-box',
    padding: 8, borderRadius: 37, background: '#314439',
    border: '2px solid #899588', boxShadow: '24px 34px 72px #263b2e24, 0 2px 4px #263b2e30',
    transform: 'translateY(' + ((1 - enter) * 24 - f * 0.018) + 'px) rotate(' + (0.9 - f * 0.0025) + 'deg)',
  }}>
    <div style={{...style, borderRadius: 27, overflow: 'hidden', background: C.paper}}>
      {still ? <Img src={staticFile('captures/hero.png')} style={style}/> :
        play ? <>
          <Sequence from={0} durationInFrames={140} layout="none"><Footage name="play" style={style}/></Sequence>
          <Sequence from={140} durationInFrames={100} layout="none"><Footage name="menu" style={style}/></Sequence>
        </> : <Footage name={media!} style={style}/>}
    </div>
  </div>;
};

const SceneCopy: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const opacity = interpolate(f, [0, 12], [0.28, 1], clamp);
  const shift = interpolate(f, [0, 22], [23, 0], {...clamp, easing});
  const intro = scene.id === 'intro';
  const close = scene.id === 'close';
  return <div style={{
    position: 'absolute', left: 110, top: 261, width: 902,
    opacity, transform: 'translateY(' + shift + 'px)',
  }}>
    <div style={{display: 'flex', alignItems: 'center', gap: 15, fontSize: 23, color: C.green, letterSpacing: 3, marginBottom: 28}}>
      <span style={{width: 29, height: 2, background: C.green}}/>{scene.eyebrow}
    </div>
    <div style={{fontSize: close ? 106 : 99, fontWeight: 760, letterSpacing: -3.5, lineHeight: 1.27}}>
      <div>{scene.lines[0]}</div><div style={{color: C.green}}>{scene.lines[1]}</div>
    </div>
    {intro ? <>
      <div style={{fontSize: 31, color: C.green, marginTop: 39, fontWeight: 520}}>把 KataGo 装进口袋。</div>
      <div style={{fontSize: 25, color: C.muted, marginTop: 17}}>方寸棋盘，也有无限可能。</div>
    </> : close ? <>
      <div style={{fontSize: 28, color: C.muted, marginTop: 31}}>KataDroid · 随身复盘，自在对弈</div>
      <div style={{display: 'flex', alignItems: 'center', gap: 22, marginTop: 41}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 28, borderRadius: 18, color: C.surface,
          background: C.green, padding: '17px 27px', fontSize: 25, fontWeight: 560}}>
          免费下载<Arrow/>
        </div>
        <span style={{fontSize: 21, color: C.muted}}>Android 13 及以上</span>
      </div>
      <div style={{fontSize: 22, color: C.green, marginTop: 27}}>github.com/zhzy0077/KataDroid</div>
    </> : <>
      <div style={{fontSize: 27, color: C.muted, lineHeight: 1.95, marginTop: 34}}>
        {scene.body?.map((line) => <div key={line}>{line}</div>)}
      </div>
      <div style={{display: 'flex', gap: 13, alignItems: 'center', marginTop: 39, color: C.green, fontSize: 19, letterSpacing: 1}}>
        <span style={{width: 7, height: 7, borderRadius: '50%', background: '#719562'}}/>
        模拟器实录 · 原速操作
      </div>
    </>}
  </div>;
};

const PromoScene: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const previewFocus = scene.id === 'preview' ? interpolate(f, [135, 153, 232, 247], [0, 1, 1, 0], clamp) : 0;
  return <AbsoluteFill>
    {scene.id === 'intro' || scene.id === 'close' ? <Phone still/> :
      scene.id === 'preview' || scene.id === 'review' ? <>
        <Viewport media={scene.media!} region={BOARD} width={780} left={1040} top={148} animated/>
        <div style={{opacity: 1 - previewFocus}}><Viewport media={scene.media!} region={PANEL} width={544} left={1278} top={605} animated/></div>
        {scene.id === 'preview' && <div style={{opacity: previewFocus}}>
          <Viewport media="preview" region={{x: 60, y: 1970, width: 960, height: 148}} width={780} left={1040} top={837}/>
        </div>}
      </> : scene.id === 'tree' ? <>
        <Phone media="tree"/>
        <Viewport media="tree" region={PANEL} width={750} left={1055} top={472} animated/>
      </> : <Phone media={scene.media} play={scene.id === 'play'}/>}
    <SceneCopy scene={scene}/>
    {scene.id === 'close' && <div style={{
      position: 'absolute', left: 758, top: 685, width: 224,
      opacity: interpolate(f, [8, 25], [0, 1], clamp),
    }}>
      <Img src={staticFile('assets/download-qr.svg')} style={{width: 224, height: 224, borderRadius: 20,
        border: '1px solid ' + C.border, boxShadow: '0 9px 30px #263b2e09'}}/>
      <div style={{fontSize: 18, textAlign: 'center', color: C.muted, marginTop: 7}}>扫码获取 Android 版本</div>
    </div>}
  </AbsoluteFill>;
};

export const Promo: React.FC<TeaserProps> = ({cues, captions}) => {
  const frame = useCurrentFrame();
  const active = cues.find((cue) => frame >= cue.from && frame < cue.from + cue.durationInFrames);
  return <AbsoluteFill style={{
    fontFamily: '"Noto Sans SC Variable", sans-serif', color: C.ink,
    background: C.paper, overflow: 'hidden', WebkitFontSmoothing: 'antialiased',
  }}>
    <Frame durationInFrames={plan.durationInFrames}/>
    {plan.scenes.map((scene) => <Sequence key={scene.id} from={scene.from} durationInFrames={scene.duration}>
      <PromoScene scene={scene}/>
    </Sequence>)}
    {captions && active && <div style={{
      position: 'absolute', left: 200, right: 200, bottom: 53, textAlign: 'center',
      fontSize: 26, fontWeight: 500, color: C.ink, letterSpacing: 0.5,
    }}><span style={{background: '#f5f7f0f2', borderRadius: 8, padding: '7px 24px'}}>{active.text}</span></div>}
    {cues.map((cue) => <Sequence key={cue.id} from={cue.from} durationInFrames={cue.durationInFrames} layout="none">
      <Audio src={staticFile(cue.src)}/>
    </Sequence>)}
    <Audio src={staticFile('audio/ambience-full.wav')} volume={0.16}/>
    {[22, 153, 393, 663, 933, 1203, 1443].map((from) =>
      <Sequence key={from} from={from} durationInFrames={6} layout="none">
        <Audio src={staticFile('audio/stone.wav')} volume={0.22}/>
      </Sequence>)}
  </AbsoluteFill>;
};
