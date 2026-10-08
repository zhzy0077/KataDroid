const SAMPLE_RATE = 48000;

function pcmWave(samples) {
  const output = Buffer.alloc(44 + samples.length * 2);
  output.write('RIFF', 0); output.writeUInt32LE(output.length - 8, 4);
  output.write('WAVEfmt ', 8); output.writeUInt32LE(16, 16);
  output.writeUInt16LE(1, 20); output.writeUInt16LE(1, 22);
  output.writeUInt32LE(SAMPLE_RATE, 24); output.writeUInt32LE(SAMPLE_RATE * 2, 28);
  output.writeUInt16LE(2, 32); output.writeUInt16LE(16, 34);
  output.write('data', 36); output.writeUInt32LE(samples.length * 2, 40);
  for (let i = 0; i < samples.length; i++) {
    output.writeInt16LE(Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767), 44 + i * 2);
  }
  return output;
}

export function makeStoneSound() {
  let seed = 20261008;
  return pcmWave(Float64Array.from({length: SAMPLE_RATE * 0.18}, (_, i) => {
    const t = i / SAMPLE_RATE;
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    const noise = seed / 4294967296 * 2 - 1;
    const attack = Math.min(1, t / 0.001);
    return attack * (0.20 * noise * Math.exp(-t * 170) +
      0.30 * Math.sin(2 * Math.PI * 1050 * t) * Math.exp(-t * 85) +
      0.17 * Math.sin(2 * Math.PI * 2340 * t) * Math.exp(-t * 120));
  }));
}

export function makeAmbience(seconds) {
  const motif = [[0.2, 196], [1.65, 293.6648], [3.2, 392], [4.8, 440], [6.4, 523.2511], [7.8, 293.6648]];
  const notes = [...motif];
  for (let start = 12; start < seconds - 2; start += 12) {
    for (const [onset, frequency] of motif) {
      if (start + onset < seconds - 2) notes.push([start + onset, frequency]);
    }
  }
  return pcmWave(Float64Array.from({length: Math.round(SAMPLE_RATE * seconds)}, (_, i) => {
    const t = i / SAMPLE_RATE;
    let value = 0.032 * Math.sin(2 * Math.PI * 98 * t) +
      0.020 * Math.sin(2 * Math.PI * 146.8324 * t);
    for (const [onset, frequency] of notes) {
      const age = t - onset;
      if (age < 0) continue;
      const envelope = (1 - Math.exp(-age * 35)) * Math.exp(-age * 1.45);
      value += envelope * (0.20 * Math.sin(2 * Math.PI * frequency * age) +
        0.045 * Math.sin(2 * Math.PI * frequency * 2 * age));
    }
    return value * Math.min(1, t / 0.4, (seconds - t) / 1.2);
  }));
}
