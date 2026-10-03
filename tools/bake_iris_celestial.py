#!/usr/bin/env python3
"""Bake the celestial textures the mod swaps in while an Iris shaderpack is active.

Iris renders the pack's sun/moon from the *vanilla* quad geometry and samples whatever texture
the vanilla renderer bound (`SUN` / `MOON_PHASES`). Rather than cancel `renderSky` (which would
remove the pack's sky too), the mod redirects those two texture binds to these files, so the pack
draws our circular sun and a phase-correct NASA moon.

Outputs (rgba, matching vanilla's atlas layout):
  sun_disc.png          512x512 circular limb-darkened sun (supersampled limb, soft corona)
  moon_phases_nasa.png  1024x512, 4x2 cells of 256, cell (p%4, p/4) = vanilla moon phase p

Both carry an alpha channel that is 0 where the sprite is black, so the unlit part of a moon and
the empty corners of the sun stay transparent instead of compositing as black boxes. Alpha is fully
opaque anywhere the sprite has real brightness, so the additive blend the sky pass uses is unaffected.

Phase convention follows vanilla/Celestial: 0 = full, 4 = new; elongation = ((p-4) mod 8)*45 deg.
"""

import argparse
import math
import os
import sys

import numpy as np
from PIL import Image

DOWNLOADS = os.path.join("tools", "downloads")
OUT_DIR = os.path.join("src", "client", "resources", "assets", "starradiance",
                       "textures", "environment")
ALBEDO_CANDIDATES = ["lroc_color_16bit_srgb_4k.tif", "lroc_color_2k.jpg"]
CELL = 256


def load_albedo():
    for name in ALBEDO_CANDIDATES:
        path = os.path.join(DOWNLOADS, name)
        if not os.path.exists(path):
            continue
        im = Image.open(path)
        arr = np.asarray(im.convert("RGB"), dtype=np.float32) / 255.0
        print(f"  albedo <- {name} ({im.size[0]}x{im.size[1]})")
        return arr
    raise SystemExit("no albedo source in tools/downloads/ (run build_moon_textures.py first)")


def sample_albedo(albedo, nx, ny, nz):
    """Near-side sphere normal -> equirect UV -> bilinear-ish albedo sample.

    Matches the 3D moon's parametrization: u=0.5 is the sub-Earth point, +X = east, +Y = north.
    """
    h, w = albedo.shape[:2]
    coslat = math.sqrt(max(1.0 - ny * ny, 1e-9))
    lam = math.atan2(nx, nz)            # longitude
    phi = math.asin(max(-1.0, min(1.0, ny)))  # latitude
    u = (lam + math.pi) / (2.0 * math.pi)
    v = (math.pi / 2.0 - phi) / math.pi
    x = min(w - 1, max(0, int(u * w)))
    y = min(h - 1, max(0, int(v * h)))
    return albedo[y, x]


def bake_sun():
    """Limb-darkened disk with a soft corona, supersampled so the limb is not stair-stepped.

    Sized to land near 1:1 with the on-screen quad: vanilla draws the sun ~30 blocks across at
    distance 100, so the disk covers roughly 300 px. A 128 px sprite magnified with the default
    nearest-neighbour filtering is what made the sun read as a jagged polygon.
    """
    size = 512
    R = 0.62   # disk radius as a fraction of half-size (matches the custom shader's 0.28 of 64)
    GLOW = 0.25
    subsamples = 3

    rgb = np.zeros((size, size, 3), dtype=np.float32)
    axis = np.arange(size, dtype=np.float32)
    for sy in range(subsamples):
        for sx in range(subsamples):
            fx = ((axis + (sx + 0.5) / subsamples) / size * 2.0 - 1.0)[None, :]
            fy = ((axis + (sy + 0.5) / subsamples) / size * 2.0 - 1.0)[:, None]
            d = np.sqrt(fx * fx + fy * fy)
            mu = np.sqrt(np.clip(1.0 - (d / R) ** 2, 0.0, 1.0))
            limb = 1.0 - 0.45 * (1.0 - mu)
            b = np.where(d <= R, limb, GLOW * np.exp(-(d - R) * 10.0))
            t = np.clip(d / R, 0.0, 1.0)
            rgb[:, :, 0] += b
            rgb[:, :, 1] += b * (0.97 - 0.05 * t)
            rgb[:, :, 2] += b * (0.90 - 0.10 * t)
    rgb /= subsamples * subsamples

    img = np.zeros((size, size, 4), dtype=np.float32)
    img[:, :, :3] = rgb
    img[:, :, 3] = np.clip(rgb.max(axis=2) * 64.0, 0.0, 1.0)
    return img


def bake_moon(albedo):
    atlas = np.zeros((CELL * 2, CELL * 4, 4), dtype=np.float32)
    for phase in range(8):
        elong = math.radians(((phase - 4) % 8) * 45.0)
        # Sun direction in the near-side frame: +Z = toward Earth (sub-Earth), +X = east.
        lx, lz = math.sin(elong), -math.cos(elong)
        col = phase % 4
        row = phase // 4
        for j in range(CELL):
            for i in range(CELL):
                # Screen -> disk coords in [-1, 1]; x east, y north (row 0 = north/top).
                fx = (i + 0.5) / CELL * 2.0 - 1.0
                fy = 1.0 - (j + 0.5) / CELL * 2.0
                r2 = fx * fx + fy * fy
                if r2 > 1.0:
                    continue
                nz = math.sqrt(max(0.0, 1.0 - r2))
                diff = max(fx * lx + nz * lz, 0.0)
                if diff <= 0.0:
                    continue
                c = sample_albedo(albedo, fx, fy, nz) * diff
                atlas[row * CELL + j, col * CELL + i, :3] = c
                # Transparent on the unlit side, so a new moon does not composite as a black disk.
                atlas[row * CELL + j, col * CELL + i, 3] = min(1.0, float(c.max()) * 64.0)

    # Shaderpacks shade this sprite as if it were near-white (vanilla's moon atlas is) and apply a
    # crushing curve to dark input, so real lunar albedo — which is ~0.05 — comes out invisible.
    # Normalise the lit surface up to full brightness; the maria keep their relative contrast.
    peak = float(atlas[:, :, :3].max())
    if peak > 0.0:
        atlas[:, :, :3] /= peak
    return atlas


def save(arr, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    Image.fromarray((np.clip(arr, 0, 1) * 255.0 + 0.5).astype(np.uint8)).save(path, optimize=True)
    print(f"  wrote {path} ({os.path.getsize(path)/1e6:.2f} MB)")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Bake the Iris-facing celestial textures.")
    parser.add_argument("--sun-only", action="store_true",
                        help="skip the moon atlas, which needs the NASA albedo in tools/downloads/")
    args = parser.parse_args(argv)

    print("Baking sun disk ...")
    save(bake_sun(), "sun_disc.png")
    if args.sun_only:
        print("Skipping the moon phase atlas (--sun-only).")
    else:
        print("Baking moon phase atlas ...")
        save(bake_moon(load_albedo()), "moon_phases_nasa.png")
    print("Done.")


if __name__ == "__main__":
    sys.exit(main())
