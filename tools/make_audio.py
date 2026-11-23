#!/usr/bin/env python3
"""Synthesises every sound effect and music loop into app/src/main/assets/audio/*.ogg.
Requires numpy, scipy and ffmpeg (libvorbis). Run: python3 tools/make_audio.py"""
import numpy as np, subprocess, os, sys, wave, tempfile
from scipy.signal import lfilter, fftconvolve

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'audio')
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)

def t_(d): return np.arange(int(d * SR)) / SR
def hz(n): return 440.0 * 2 ** ((n - 69) / 12)
def env(n, a=0.004, dec=6.0):
    t = np.arange(n) / SR
    e = np.exp(-dec * t); k = max(1, int(a * SR)); e[:k] *= np.linspace(0, 1, k); return e
def lp(x, fc):
    a = np.exp(-2 * np.pi * fc / SR); return lfilter([1 - a], [1, -a], x)
def hp(x, fc): return x - lp(x, fc)
def bell(f, d, v=1.0, dec=5.0):
    t = t_(d); y = np.zeros_like(t)
    for r, a in ((1, 1), (2.01, .5), (2.76, .35), (4.07, .2), (5.4, .1)): y += a * np.sin(2 * np.pi * f * r * t) * np.exp(-dec * r ** .6 * t)
    return v * y / 2.2 * np.minimum(1, t / .003)
def marimba(f, d, v=1.0):
    t = t_(d); y = np.sin(2 * np.pi * f * t) * np.exp(-9 * t) + .45 * np.sin(2 * np.pi * f * 4 * t) * np.exp(-18 * t) + .2 * np.sin(2 * np.pi * f * 9.2 * t) * np.exp(-30 * t)
    return v * y * np.minimum(1, t / .002)
def pluck(f, d, v=1.0):
    t = t_(d); y = sum(a * np.sin(2 * np.pi * f * k * t) for k, a in ((1, 1), (2, .4), (3, .2))) * np.exp(-7 * t)
    return v * lp(y, 2200) * np.minimum(1, t / .004)
def sweep(f0, f1, d, v=1.0, wave='sin'):
    t = t_(d); f = f0 * (f1 / f0) ** (t / d); ph = 2 * np.pi * np.cumsum(f) / SR
    y = np.sin(ph) if wave == 'sin' else (2 * ((ph / (2 * np.pi)) % 1) - 1)
    return v * y
def noise(d, v=1.0): return v * rng.uniform(-1, 1, int(d * SR))
def place(buf, x, at):
    i = int(at * SR); n = min(len(x), len(buf) - i)
    if n > 0: buf[i:i + n] += x[:n]
def reverb(x, size=.9, mix=.25):
    n = int(size * SR); ir = rng.normal(size=n) * np.exp(-5 * np.arange(n) / n); ir = lp(ir, 5000)
    wet = fftconvolve(x, ir / np.abs(ir).sum() * 6)[:len(x) + n]
    out = np.zeros(len(wet)); out[:len(x)] += x * (1 - mix); out += wet * mix; return out
def fin(x, peak=.8, fade=.004):
    x = x.copy(); m = np.abs(x).max() or 1; x = x / m * peak
    k = int(fade * SR); x[-k:] *= np.linspace(1, 0, k); return x
def write(name, x, stereo=False, q=3):
    x = np.clip(x, -1, 1)
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tf: p = tf.name
    with wave.open(p, 'wb') as w:
        w.setnchannels(2 if stereo else 1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype('<i2').tobytes())
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', p, '-c:a', 'libvorbis', '-q:a', str(q), os.path.join(OUT, name + '.ogg')], check=True)
    os.remove(p)

PENT = [0, 2, 4, 7, 9, 12, 14, 16, 19]
sfx = {}
def sfx_(name, x): sfx[name] = fin(x)

b = np.zeros(int(.35 * SR)); place(b, bell(hz(81), .3, 1), 0); place(b, bell(hz(88), .25, .5), .03); sfx_('select', b)
b = np.zeros(int(.25 * SR)); place(b, hp(lp(noise(.2), 4200 ), 500) * np.hanning(int(.2 * SR)) * .7, 0); place(b, sweep(500, 900, .12, .5) * env(int(.12 * SR), dec=20), .02); sfx_('swap', b)
b = np.zeros(int(.4 * SR)); place(b, lp(sweep(190, 140, .16, 1, 'saw'), 700) * env(int(.16 * SR), dec=8), 0); place(b, lp(sweep(170, 120, .18, 1, 'saw'), 600) * env(int(.18 * SR), dec=8), .14); sfx_('invalid', b)
for k in range(9):
    b = np.zeros(int(.7 * SR)); base = 72 + PENT[k]
    for j, o in enumerate((0, 4, 7)): place(b, marimba(hz(base + o), .45, 1 - j * .15), j * .045)
    place(b, bell(hz(base + 24), .5, .25), .02); sfx_(f'match_{k}', reverb(b, .5, .18))
b = np.zeros(int(.2 * SR)); place(b, hp(noise(.05), 1500) * env(int(.05 * SR), dec=60), 0); place(b, sweep(700, 220, .12, .8) * env(int(.12 * SR), dec=22), 0); sfx_('pop', b)
b = np.zeros(int(.3 * SR)); place(b, sweep(130, 55, .25, 1) * env(int(.25 * SR), dec=14), 0); place(b, lp(noise(.05), 600) * env(int(.05 * SR), dec=40) * .6, 0); sfx_('fall', b)
b = np.zeros(int(.9 * SR))
for j, n in enumerate((72, 76, 79, 84, 88)): place(b, bell(hz(n), .5, .9), j * .06)
place(b, hp(noise(.5), 3000) * np.linspace(0, 1, int(.5 * SR)) * np.exp(-3 * t_(.5)) * .25, 0); sfx_('make', reverb(b, .9, .25))
b = np.zeros(int(.9 * SR)); place(b, lp(noise(.45), 2500) * np.linspace(0, 1, int(.45 * SR)) ** 2 * .6, 0)
for j, n in enumerate((60, 67, 72, 76)): place(b, pluck(hz(n), .5, .8), .3 + j * .02)
place(b, bell(hz(88), .55, .5), .35); sfx_('special', reverb(b, .7, .22))
b = np.zeros(int(1.0 * SR)); place(b, sweep(95, 28, .8, 1) * env(int(.8 * SR), dec=4.5), 0); place(b, lp(noise(.8), 900) * env(int(.8 * SR), dec=5), 0)
place(b, hp(noise(.2), 2000) * env(int(.2 * SR), dec=25) * .6, 0); sfx_('boom', b)
for n in range(2, 9):
    b = np.zeros(int((.3 + n * .07) * SR))
    for j in range(n): place(b, bell(hz(76 + PENT[min(j, 8)]), .4, .8), j * .07)
    sfx_(f'cascade_{n}', reverb(b, .6, .2))
for k, nt in enumerate((84, 88, 91)):
    b = np.zeros(int(1.0 * SR)); place(b, bell(hz(nt), .9, 1, 3.5), 0); place(b, bell(hz(nt + 12), .7, .35, 4), 0); place(b, hp(noise(.12), 5000) * env(int(.12 * SR), dec=30) * .2, 0)
    sfx_(f'star_{k}', reverb(b, 1.0, .3))
b = np.zeros(int(2.8 * SR))
for j, n in enumerate((72, 76, 79, 84, 88, 91, 96)): place(b, bell(hz(n), 1.0, .85, 3), j * .11)
for n in (72, 76, 79, 84): place(b, pluck(hz(n - 12), 1.3, .6), .75)
place(b, bell(hz(96), 1.8, .8, 2), .75); sfx_('win', reverb(b, 1.4, .3))
b = np.zeros(int(2.0 * SR))
for j, n in enumerate((69, 66, 62, 57)): place(b, lp(pluck(hz(n), .7, 1), 1400), j * .26)
place(b, lp(sweep(220, 110, 1, .6, 'saw'), 500) * env(int(1 * SR), dec=3), .8); sfx_('lose', reverb(b, .9, .25))
b = np.zeros(int(.15 * SR)); place(b, sweep(900, 600, .08, 1) * env(int(.08 * SR), dec=40), 0); place(b, hp(noise(.02), 2000) * .3, 0); sfx_('btn', b)
b = np.zeros(int(.7 * SR)); place(b, bell(hz(83), .35, 1, 8), 0); place(b, bell(hz(88), .55, 1, 6), .07); sfx_('coin', reverb(b, .4, .15))
b = np.zeros(int(.7 * SR)); sw = lp(noise(.6), 3000) * np.sin(2 * np.pi * 11 * t_(.6)) ** 2 * np.hanning(int(.6 * SR)); place(b, sw * .7, 0)
for j in range(6): place(b, pluck(hz(72 + PENT[j % 5]), .15, .5), j * .08)
sfx_('shuffle', b)
b = np.zeros(int(.5 * SR)); place(b, lp(sweep(2400, 180, .35, 1, 'saw'), 3500) * env(int(.35 * SR), dec=7), 0); place(b, hp(noise(.3), 1200) * env(int(.3 * SR), dec=12) * .6, 0)
place(b, bell(hz(100), .2, .4), 0); sfx_('bolt', b)

# cartoon boing when a candy is touched, and a soft squish when it is slid
t = t_(.34); f = 330 + 140 * np.sin(2 * np.pi * 9 * t) * np.exp(-6 * t) + 120 * np.exp(-9 * t)
b = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-8 * t) + .35 * np.sin(4 * np.pi * np.cumsum(f) / SR) * np.exp(-12 * t)
sfx_('jiggle', b * np.minimum(1, t / .004))
t = t_(.16); f = 220 * (1.9 ** np.minimum(1, t / .09)); b = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * np.minimum(1, t / .16)) ** .7
sfx_('squish', lp(b, 1800) * .9)

for k, v in sfx.items(): write('sfx_' + k, v, False, 4)

# ------------------------------------------------------------------ music
def music(world):
    r = np.random.default_rng(100 + world)
    bpm = [104, 112, 98, 108, 92, 116, 120, 110, 124, 88][world]
    root = [60, 62, 57, 64, 55, 65, 60, 67, 62, 58][world]
    minor = world in (2, 4, 9)
    beat = 60 / bpm; bars = 16; n = int(bars * 4 * beat * SR); tail = int(3 * SR)
    L = np.zeros(n + tail); R = np.zeros(n + tail)
    prog = [(0, 4, 7), (9, 0, 4), (5, 9, 0), (7, 11, 2)] if not minor else [(0, 3, 7), (8, 0, 3), (5, 8, 0), (7, 10, 2)]
    scale = [0, 2, 4, 7, 9] if not minor else [0, 3, 5, 7, 10]
    for bar in range(bars):
        t0 = bar * 4 * beat; ch = prog[(bar // 2) % 4] if bar % 8 < 8 else prog[bar % 4]; ch = prog[bar % 4]
        for k, o in enumerate(ch):  # soft pad
            d = 4 * beat + .6; t = t_(d); f = hz(root - 12 + o)
            y = (np.sin(2 * np.pi * f * t) + .5 * np.sin(2 * np.pi * f * 2.003 * t) + .25 * np.sin(2 * np.pi * f * 3 * t)) * np.minimum(1, t / .5) * np.minimum(1, (d - t) / .6) * .11
            place(L, y * (.8 + .2 * (k % 2)), t0); place(R, y * (1 - .2 * (k % 2)), t0)
        for bt in range(4):  # bass + soft kick
            tt = t0 + bt * beat; bn = root - 24 + (ch[0] if bt % 2 == 0 else ch[2])
            b = pluck(hz(bn), beat * .9, .5); place(L, b, tt); place(R, b, tt)
            if bt % 2 == 0: k = sweep(110, 45, .16, .55) * env(int(.16 * SR), dec=18); place(L, k, tt); place(R, k, tt)
        for e in range(8):  # shaker
            h = hp(noise(.04), 6000) * env(int(.04 * SR), dec=70) * (.13 if e % 2 else .08); place(L, h, t0 + e * beat / 2 + .005); place(R, h, t0 + e * beat / 2)
        steps = 8 if bar % 2 == 0 else 6
        for s in range(steps):  # bell melody
            if r.random() < .72:
                nn = root + 12 + r.choice(scale) + 12 * int(r.random() < .3)
                tt = t0 + s * (4 * beat / steps); m = marimba(hz(nn), .55, .34 if bar % 4 else .42)
                pan = .5 + .35 * np.sin(s + bar); place(L, m * (1 - pan) * 1.6, tt); place(R, m * pan * 1.6, tt)
    wet = np.stack([reverb(L, 1.2, .35)[:n + tail], reverb(R, 1.0, .35)[:n + tail]])
    st = wet[:, :n].copy(); st[:, :tail] += wet[:, n:n + tail] if wet.shape[1] >= n + tail else 0  # fold tail into start for a seamless loop
    st = st / (np.abs(st).max() or 1) * .55
    return st.T.reshape(-1)
for w in range(10):
    write(f'music_{w}', music(w), True, 2)
    print('music', w, file=sys.stderr)
print('audio done')
