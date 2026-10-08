import React from 'react';
import {
  AbsoluteFill, Audio, Easing, Img, interpolate, Sequence,
  spring, staticFile, useCurrentFrame, useVideoConfig,
} from 'remotion';
import plan from '../content/teaser.zh-CN.json';

export type AudioCue = {
  id: string; from: number; durationInFrames: number; src: string; text: string;
};
export type TeaserProps = {cues: AudioCue[]; captions: boolean};

export const C = {
  paper: '#F5F7F0', surface: '#FFFEF8', ink: '#263B2E',
  green: '#28634E', muted: '#657360', border: '#DCE3D3', pale: '#E5EDDC',
};
const clamp = {extrapolateLeft: 'clamp' as const, extrapolateRight: 'clamp' as const};
const ease = Easing.bezier(0.22, 1, 0.36, 1);
const asset = (name: string) => staticFile('assets/' + name);

function fade(frame: number, start: number, stop: number) {
  return interpolate(frame, [start, start + 18, stop - 18, stop], [0, 1, 1, 0], clamp);
}
function rise(frame: number, start: number, distance = 28) {
  return interpolate(frame, [start, start + 26], [distance, 0], {...clamp, easing: ease});
}

export const Arrow: React.FC<{size?: number; color?: string}> = ({size = 28, color = C.surface}) => (
  <svg width={size} height={size} viewBox="0 0 28 28" fill="none">
    <path d="M5 14h17M15 6l8 8-8 8" stroke={color} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const Frame: React.FC<{durationInFrames?: number}> = ({durationInFrames = plan.durationInFrames}) => {
  const f = useCurrentFrame();
  return <>
    <div style={{position: 'absolute', inset: 0,
      background: 'radial-gradient(ellipse at 87% 26%, #e0e9d7 0%, transparent 55%), radial-gradient(ellipse at 1% 95%, #f4eddf 0%, transparent 46%), ' + C.paper}}/>
    <svg width="1920" height="1080" style={{position: 'absolute', opacity: 0.085}}>
      <defs><pattern id="grid" width="72" height="72" patternUnits="userSpaceOnUse">
        <path d="M72 0H0v72" fill="none" stroke={C.green} strokeWidth="1"/>
      </pattern><linearGradient id="gridFade"><stop stopColor="white" stopOpacity="0"/><stop offset="1" stopColor="white"/></linearGradient>
        <mask id="gridMask"><rect width="1920" height="1080" fill="url(#gridFade)"/></mask>
      </defs>
      <rect x="700" width="1220" height="1080" fill="url(#grid)" mask="url(#gridMask)"/>
    </svg>
    <div style={{position: 'absolute', top: 52, left: 104, display: 'flex', alignItems: 'center', gap: 17}}>
      <Img src={asset('icon.png')} style={{width: 54, height: 54, borderRadius: 16}}/>
      <span style={{fontSize: 32, fontWeight: 720, letterSpacing: -0.8}}>KataDroid</span>
      <span style={{width: 1, height: 26, background: '#afbeaa', margin: '0 10px'}}/>
      <span style={{fontSize: 21, color: C.muted, letterSpacing: 3}}>围棋的下一步</span>
    </div>
    <div style={{position: 'absolute', top: 69, right: 108, fontSize: 19, color: C.muted, letterSpacing: 1.2}}>ANDROID / 本地 AI</div>
    <div style={{position: 'absolute', bottom: 24, left: 108, right: 108, height: 2, background: C.border}}>
      <div style={{height: 2, width: ((f + 1) / durationInFrames * 100) + '%', background: C.green}}/>
    </div>
  </>;
};

const Phone: React.FC<{focus: number}> = ({focus}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const enter = spring({frame: f, fps, config: {damping: 25, stiffness: 85, mass: 1}});
  return <div style={{
    position: 'absolute', top: 128, left: 1314, width: 390,
    padding: 8, borderRadius: 38, background: '#314439',
    border: '2px solid #899588', opacity: 1 - focus,
    boxShadow: '24px 34px 72px #263b2e24, 0 2px 4px #263b2e30',
    transform: 'translate(' + (focus * 90) + 'px, ' + ((1 - enter) * 70 - f * 0.025) + 'px) rotate(' + (1.5 - f * 0.003) + 'deg)',
  }}>
    <div style={{borderRadius: 28, overflow: 'hidden', background: C.paper, lineHeight: 0}}>
      <Img src={asset('analysis-zh.png')} style={{display: 'block', width: '100%', height: 'auto'}}/>
    </div>
  </div>;
};

const Detail: React.FC<{focus: number}> = ({focus}) => {
  const f = useCurrentFrame();
  const panel = interpolate(f, [142, 166], [0, 1], {...clamp, easing: ease});
  const pulse = interpolate(f, [128, 159, 190], [1, 1.17, 1], clamp);
  return <div style={{opacity: focus}}>
    <div style={{position: 'absolute', top: 148, left: 1040, width: 780, height: 780,
      borderRadius: 36, overflow: 'hidden', boxShadow: '12px 26px 70px #263b2e20',
      transform: 'translateY(' + (1 - focus) * 30 + 'px) scale(' + (0.97 + focus * 0.03) + ')'}}>
      <Img src={asset('board-zh.png')} style={{width: '100%', height: '100%'}}/>
      <div style={{position: 'absolute', left: '69.3%', top: '44.7%', width: 68, height: 68,
        borderRadius: '50%', border: '2px solid #28634e99',
        transform: 'translate(-50%, -50%) scale(' + pulse + ')',
        opacity: interpolate(f, [123, 138], [0, 0.8], clamp)}}/>
    </div>
    <div style={{position: 'absolute', top: 666, left: 1278, width: 544,
      borderRadius: 32, overflow: 'hidden', lineHeight: 0, opacity: panel,
      transform: 'translateY(' + (1 - panel) * 45 + 'px)',
      boxShadow: '0 18px 62px #263b2e2a', background: C.surface}}>
      <Img src={asset('analysis-panel-zh.png')} style={{width: '100%'}}/>
    </div>
  </div>;
};

const Copy: React.FC<{start: number; stop: number; eyebrow: string; first: string; second: string; children: React.ReactNode}> =
({start, stop, eyebrow, first, second, children}) => {
  const f = useCurrentFrame();
  const opacity = start === 0 ? interpolate(f, [0, 12, stop - 18, stop], [0.15, 1, 1, 0], clamp) : fade(f, start, stop);
  return <div style={{position: 'absolute', top: 263, left: 110, width: 895, opacity,
    transform: 'translateY(' + rise(f, start) + 'px)'}}>
    <div style={{display: 'flex', alignItems: 'center', gap: 15, fontSize: 24, color: C.green, letterSpacing: 4, marginBottom: 27}}>
      <span style={{width: 29, height: 2, background: C.green}}/>{eyebrow}
    </div>
    <div style={{fontSize: 106, fontWeight: 760, letterSpacing: -4, lineHeight: 1.24}}>
      <div>{first}</div><div style={{color: C.green}}>{second}</div>
    </div>
    {children}
  </div>;
};

export const Teaser: React.FC<TeaserProps> = ({cues, captions}) => {
  const frame = useCurrentFrame();
  const focus = interpolate(frame, [95, 117, 209, 232], [0, 1, 1, 0], {...clamp, easing: ease});
  const captionCues = cues.length ? cues : plan.clips.map((clip) => ({
    id: clip.id, from: clip.from, durationInFrames: Math.ceil(clip.slotSeconds * plan.fps),
    src: '', text: clip.caption,
  }));
  const caption = captionCues.find((cue) => frame >= cue.from && frame < cue.from + cue.durationInFrames);
  return <AbsoluteFill style={{
    fontFamily: '"Noto Sans SC Variable", sans-serif', color: C.ink,
    backgroundColor: C.paper, overflow: 'hidden', WebkitFontSmoothing: 'antialiased',
  }}>
    <Frame/>
    <Phone focus={focus}/>
    {focus > 0 && <Detail focus={focus}/>}
    <Copy start={0} stop={112} eyebrow="随身的围棋分析伙伴" first="把 KataGo" second="装进口袋。">
      <div style={{fontSize: 29, color: C.muted, lineHeight: 1.8, marginTop: 33}}>
        在手机上，离线运行 KataGo。<br/>打开棋盘，开始思考。
      </div>
      <div style={{display: 'flex', alignItems: 'center', gap: 17, marginTop: 42}}>
        <span style={{width: 16, height: 16, borderRadius: '50%', background: C.ink, boxShadow: '18px 0 0 #e5dece', marginRight: 18}}/>
        <span style={{fontSize: 22, letterSpacing: 2}}>方寸棋盘，也有无限可能</span>
      </div>
    </Copy>
    <Copy start={96} stop={229} eyebrow="让判断，更有依据" first="看见落点。" second="读懂局势。">
      <div style={{fontSize: 28, color: C.muted, lineHeight: 1.8, marginTop: 33}}>
        候选落点，为下一手提供方向。<br/>胜率走势，把局势变化看清楚。
      </div>
      <div style={{marginTop: 38, fontSize: 22, color: C.green, display: 'flex', alignItems: 'center', gap: 16}}>
        <span>真实引擎分析</span><span style={{width: 66, height: 1, background: '#8ea58b'}}/><span>棋盘与走势，同屏呈现</span>
      </div>
    </Copy>
    <Copy start={213} stop={333} eyebrow="你的下一手，从这里开始" first="每一步，" second="都有思路。">
      <div style={{fontSize: 29, color: C.muted, marginTop: 32}}>KataDroid · 随身复盘，自在对弈</div>
      <div style={{display: 'flex', alignItems: 'center', gap: 24, marginTop: 41}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 30, borderRadius: 18,
          background: C.green, color: C.surface, padding: '17px 27px', fontSize: 25, fontWeight: 560}}>
          免费下载<Arrow/>
        </div>
        <span style={{fontSize: 21, color: C.muted}}>Android 13 及以上</span>
      </div>
      <div style={{fontSize: 23, color: C.green, marginTop: 25, letterSpacing: 0.2}}>github.com/zhzy0077/KataDroid</div>
    </Copy>
    {captions && caption && <div style={{
      position: 'absolute', bottom: 54, left: 240, right: 240, textAlign: 'center',
      color: C.ink, fontSize: 27, fontWeight: 500, letterSpacing: 1,
    }}>
      <span style={{background: '#f5f7f0ed', padding: '7px 25px', borderRadius: 8}}>{caption.text}</span>
    </div>}
    {cues.map((cue) => <Sequence key={cue.id} from={cue.from} durationInFrames={cue.durationInFrames} layout="none">
      <Audio src={staticFile(cue.src)}/>
    </Sequence>)}
    <Audio src={staticFile('audio/ambience.wav')} volume={0.17}/>
    {[22, 126, 226].map((from) => <Sequence key={from} from={from} durationInFrames={6} layout="none">
      <Audio src={staticFile('audio/stone.wav')} volume={0.26}/>
    </Sequence>)}
  </AbsoluteFill>;
};
