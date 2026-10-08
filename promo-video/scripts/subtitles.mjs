function timestamp(seconds) {
  const total = Math.round(seconds * 1000);
  const milliseconds = total % 1000;
  const sec = Math.floor(total / 1000) % 60;
  const min = Math.floor(total / 60000) % 60;
  const hours = Math.floor(total / 3600000);
  return [hours, min, sec].map((x) => String(x).padStart(2, '0')).join(':') +
    ',' + String(milliseconds).padStart(3, '0');
}

export function cuesToSrt(cues, fps) {
  if (!Number.isFinite(fps) || fps <= 0) throw new Error('fps must be positive');
  return cues.map((cue, i) =>
    (i + 1) + '\n' + timestamp(cue.from / fps) + ' --> ' +
    timestamp((cue.from + cue.durationInFrames) / fps) + '\n' +
    cue.text + '\n'
  ).join('\n');
}
