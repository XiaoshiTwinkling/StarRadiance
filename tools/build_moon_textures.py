#!/usr/bin/env python3
"""Offline asset pipeline for StarRadiance's realistic Moon.

Turns the public-domain NASA SVS "CGI Moon Kit" sources into the PNG textures the
mod ships:

  lroc_color_16bit_srgb_4k.tif  ->  moon_albedo_4k.png / moon_albedo_2k.png
  ldem_16_uint.tif              ->  moon_normal_4k.png / moon_normal_2k.png

Run from the repo root (downloads land in tools/downloads/):

    python tools/build_moon_textures.py            # 4k + 2k
    python tools/build_moon_textures.py --res 8    # also emit 8k (not shipped)

Outputs go to src/client/resources/assets/starradiance/textures/environment/.
The 8k variants are written to tools/out_8k/ so they never bloat the jar.

Source (NASA SVS, Ernie Wright; LRO/LROC/LOLA; public domain, attribution requested):
  https://svs.gsfc.nasa.gov/4720/
"""

import argparse
import os
import sys
import urllib.request

import numpy as np
from PIL import Image

BASE = "https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720"
DOWNLOADS = os.path.join("tools", "downloads")
OUT_DIR = os.path.join("src", "client", "resources", "assets", "starradiance",
                       "textures", "environment")
OUT_8K = os.path.join("tools", "out_8k")

ALBEDO_CANDIDATES = [
    "lroc_color_16bit_srgb_4k.tif",  # preferred: 16-bit detail
    "lroc_color_2k.jpg",             # fallback: 8-bit, already small
]
DEM_CANDIDATES = [
    "ldem_16_uint.tif",  # 5760x2880
    "ldem_4_uint.tif",   # 1440x720
]


def pick(candidates):
    """Return the first candidate present in tools/downloads/, else None."""
    for name in candidates:
        path = os.path.join(DOWNLOADS, name)
        if os.path.exists(path) and os.path.getsize(path) > 0:
            return path
    return None

MOON_RADIUS_M = 1_737_400.0
DEM_OFFSET = 20_000.0   # stored value is (elev_m + offset) / 0.5
DEM_SCALE = 0.5         # metres per stored unit
# Craters are kilometres wide but only a few hundred metres deep — the raw slope is
# far too shallow to read near the terminator, so exaggerate the gradient.
RELIEF_EXAGGERATION = 6.0


def download(name):
    os.makedirs(DOWNLOADS, exist_ok=True)
    dest = os.path.join(DOWNLOADS, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"  cached {name} ({os.path.getsize(dest)/1e6:.1f} MB)")
        return dest
    url = f"{BASE}/{name}"
    print(f"  downloading {name} ...")
    tmp = dest + ".part"
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        f.write(r.read())
    os.replace(tmp, dest)
    print(f"  saved {name} ({os.path.getsize(dest)/1e6:.1f} MB)")
    return dest


def load_16bit_rgb(path):
    """Return a float32 HxWx3 array in 0..1 from a 16-bit sRGB TIFF or an 8-bit JPEG.

    Pillow is inconsistent about wide-gamut TIFFs: a 16-bit RGB file may open as
    'RGB' (already truncated to 8 bits) or as a raw 16-bit mode. Handle both so a
    Pillow change does not silently halve our precision.
    """
    im = Image.open(path)
    print(f"  {os.path.basename(path)}: mode={im.mode} size={im.size}")
    if im.mode == "RGB":
        arr = np.asarray(im, dtype=np.float32) / 255.0
    elif im.mode in ("I;16", "I;16B", "I", "I;16L"):
        arr = np.asarray(im, dtype=np.float32) / 65535.0
        arr = np.repeat(arr[..., None], 3, axis=2)
    else:
        arr = np.asarray(im.convert("RGB"), dtype=np.float32) / 255.0
    return arr


def load_dem(path):
    """Return a float32 HxW array of elevation in metres."""
    im = Image.open(path)
    print(f"  {os.path.basename(path)}: mode={im.mode} size={im.size}")
    arr = np.asarray(im).astype(np.float32)
    if arr.ndim == 3:
        arr = arr[..., 0]
    return (arr - DEM_OFFSET) * DEM_SCALE


def save_png(arr, path, size):
    """Downscale/encode a float 0..1 HxW or HxWx3 array to an 8-bit PNG."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img = Image.fromarray((np.clip(arr, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8))
    if img.size != size:
        img = img.resize(size, Image.LANCZOS)
    img.save(path, optimize=True)
    print(f"  wrote {path}  ({os.path.getsize(path)/1e6:.2f} MB, {img.size[0]}x{img.size[1]})")


def resample(arr, w, h):
    """Bilinear-resample an HxW (or HxWx3) float array to w x h."""
    if arr.shape[1] == w and arr.shape[0] == h:
        return arr
    chan = arr.ndim == 3
    src = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8)) if chan else \
        Image.fromarray(arr.astype(np.float32), mode="F")
    out = np.asarray(src.resize((w, h), Image.BILINEAR), dtype=np.float32)
    return out / 255.0 if chan else out


def dem_to_normal(dem, w, h, exaggeration):
    """Sobel-ish central-difference normal map from an equirectangular DEM.

    Grid is longitude-columns (0..360, wrapping) by latitude-rows (90..-90, top down).
    Spacing is physical: d_east = R*cos(lat)*d_lon, d_north = R*d_lat.
    """
    dem = resample(dem, w, h)
    dlon = 2.0 * np.pi / w
    dlat = np.pi / h

    lat = (np.pi / 2.0) - (np.arange(h) + 0.5) * dlat           # row centres
    coslat = np.clip(np.cos(lat), 1e-3, None)[:, None]          # avoid poles blowing up

    # Elevation differences (central), longitude wraps around the seam.
    de = (np.roll(dem, -1, axis=1) - np.roll(dem, 1, axis=1)) * 0.5
    dn = np.zeros_like(dem)
    dn[1:-1, :] = (dem[:-2, :] - dem[2:, :]) * 0.5              # row above is north
    dn[0, :] = dem[0, :] - dem[1, :]
    dn[-1, :] = dem[-2, :] - dem[-1, :]

    dz_dx = de / (MOON_RADIUS_M * coslat * dlon)                # east slope
    dz_dy = dn / (MOON_RADIUS_M * dlat)                         # north slope
    dz_dx *= exaggeration
    dz_dy *= exaggeration

    # Tangent-space normal, +Z out of the surface.
    nx = -dz_dx
    ny = -dz_dy
    nz = np.ones_like(dem)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx *= inv
    ny *= inv
    nz *= inv

    out = np.stack([nx * 0.5 + 0.5, ny * 0.5 + 0.5, nz * 0.5 + 0.5], axis=2)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", nargs="+", type=int, default=[4, 2],
                    help="albedo/normal resolutions in k-pixels of width (default 4 2)")
    args = ap.parse_args()

    print("Resolving NASA sources ...")
    albedo_path = pick(ALBEDO_CANDIDATES)
    dem_path = pick(DEM_CANDIDATES)
    if albedo_path is None:
        albedo_path = download(ALBEDO_CANDIDATES[0])
    if dem_path is None:
        dem_path = download(DEM_CANDIDATES[0])
    print(f"  albedo <- {os.path.basename(albedo_path)}")
    print(f"  dem    <- {os.path.basename(dem_path)}")

    print("Building albedo ...")
    albedo = load_16bit_rgb(albedo_path)

    print("Building normal map ...")
    dem = load_dem(dem_path)

    for k in args.res:
        w = k * 1024
        h = w // 2
        # 8k is a resource-pack override, never shipped in the jar.
        out_dir = OUT_8K if k >= 8 else OUT_DIR
        save_png(albedo, os.path.join(out_dir, f"moon_albedo_{k}k.png"), (w, h))
        normal = dem_to_normal(dem, w, h, RELIEF_EXAGGERATION)
        save_png(normal, os.path.join(out_dir, f"moon_normal_{k}k.png"), (w, h))

    print("Done.")


if __name__ == "__main__":
    sys.exit(main())
