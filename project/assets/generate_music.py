"""Reproducible original instrumental sketches. No samples or external compositions.
Run from any directory: python project/assets/generate_music.py
Each file contains exactly 96 beats; lyrics/sections in Session.kt use the same beat clock.
"""
from pathlib import Path
import hashlib
import json
import wave
import numpy as np

RATE = 24000
OUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
OUT.mkdir(parents=True, exist_ok=True)

def hz(note):
    return 440 * 2 ** ((note - 69) / 12)

def make(name, bpm, roots, motif):
    beat = 60 / bpm
    mix = np.zeros(round(96 * beat * RATE), dtype=np.float64)
    rng = np.random.default_rng(2602)

    def add(start, duration, notes, gain, kind='keys'):
        begin = round(start * beat * RATE)
        count = min(round(duration * beat * RATE), len(mix) - begin)
        if count <= 0:
            return
        t = np.arange(count) / RATE
        env = np.minimum(t / .012, 1) * np.minimum((count / RATE - t) / .09, 1)
        signal = np.zeros(count)
        for note in notes:
            f = hz(note)
            signal += np.sin(2 * np.pi * f * t) + .25 * np.sin(4 * np.pi * f * t) * np.exp(-t * 4)
        mix[begin:begin + count] += gain * signal / len(notes) * env * np.exp(-t * (2.3 if kind == 'keys' else .8))

    for bar in range(24):
        root = roots[bar % len(roots)]
        minor = name == 'starlight' and bar % 4 in (0, 3)
        triad = [root + 12, root + (15 if minor else 16), root + 19]
        for step in range(8):
            add(bar * 4 + step * .5, .65, [triad[step % 3]], .19)
        for step in (0, 2):
            add(bar * 4 + step, 1.5, [root - 12], .26, 'bass')
        for step in range(4):
            start = round((bar * 4 + step) * beat * RATE)
            count = min(round(.17 * RATE), len(mix) - start)
            t = np.arange(count) / RATE
            if step % 2 == 0:
                drum = np.sin(2 * np.pi * (49 * t + 4 * (1 - np.exp(-t * 30)))) * np.exp(-t * 27) * .24
            else:
                drum = rng.normal(0, 1, count) * np.exp(-t * 38) * .075
            mix[start:start + count] += drum
        # Quiet original guide melody; leaves room for singing.
        if bar >= 2:
            for step, note in enumerate(motif[bar % len(motif)]):
                add(bar * 4 + step, .85, [note], .095)
    fade = min(RATE, len(mix) // 2)
    mix[:fade] *= np.linspace(0, 1, fade)
    mix[-fade:] *= np.linspace(1, 0, fade)
    mix *= .85 / max(1, np.max(np.abs(mix)))
    path = OUT / f'{name}.wav'
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((mix * 32767).astype('<i2').tobytes())
    return dict(id=name, bpm=bpm, beats=96, durationSeconds=96 * beat,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())

tracks = [
    make('campfire', 96, [48, 43, 45, 41], [[72, 76, 79, 76], [71, 74, 79, 74], [72, 76, 81, 79], [69, 72, 77, 76]]),
    make('starlight', 90, [45, 41, 48, 43], [[69, 72, 76, 72], [69, 72, 77, 76], [67, 72, 76, 74], [67, 71, 74, 71]]),
    make('weekend', 120, [41, 48, 46, 48], [[77, 81, 84, 81], [76, 79, 84, 79], [74, 77, 82, 81], [76, 79, 84, 86]])
]
(Path(__file__).parent / 'music-manifest.json').write_text(json.dumps(dict(
    origin='Original procedural music and original Korean lyrics created for PLEOKE. No external samples.',
    generator='generate_music.py', sampleRate=RATE, channels=1, tracks=tracks), indent=2), encoding='utf-8')
print('Generated', len(tracks), 'original tracks')
