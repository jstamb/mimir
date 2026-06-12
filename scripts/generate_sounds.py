#!/usr/bin/env python3
"""Generates Mimir's default UI sounds: short clean sine blips with exp decay. 44.1kHz mono 16-bit WAV."""
import math, struct, wave, os

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

def tone(path, freqs, ms, gain=0.5, sweep=False):
    n = int(SR * ms / 1000)
    frames = bytearray()
    for i in range(n):
        t = i / SR
        env = math.exp(-6.0 * i / n)                      # exponential decay
        if sweep:
            f = freqs[0] + (freqs[1] - freqs[0]) * (i / n)  # linear sweep
            s = math.sin(2 * math.pi * f * t)
        else:
            s = sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)
        frames += struct.pack("<h", int(32767 * gain * env * s))
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(bytes(frames))
    print("wrote", path, len(frames) // 2, "samples")

os.makedirs(OUT, exist_ok=True)
tone(os.path.join(OUT, "nav_tick.wav"), [1800], 22, gain=0.25)
tone(os.path.join(OUT, "select.wav"), [880, 1320], 45, gain=0.35)
tone(os.path.join(OUT, "launch.wav"), [440, 1760], 140, gain=0.4, sweep=True)
tone(os.path.join(OUT, "back.wav"), [520], 30, gain=0.25)
