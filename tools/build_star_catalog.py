#!/usr/bin/env python3
"""Build the star resources from the HYG v4.2 catalogue.

Outputs (both committed; the runtime never needs the CSV):

  stars.dat       unchanged hot-path format:
                    "NWST" + int32 count + per star 4 big-endian float32 (raDeg, decDeg, mag, bv)
  stars_meta.dat  the v2 side file used by the star-chart screen:
                    "NWSM" + int32 count
                    + int32 constellationCount + (int32 len + utf8)*
                    + int32 stringCount + (int32 len + utf8)*
                    + per star: int32 conIndex, name, designation, spectral, distanceCentily
                  (-1 means "not available"; distance is in 0.01 ly)

HYG columns used: ra (hours), dec (deg), mag, ci (B-V), proper, bayer, flam, con, spect, dist (pc).
The Sun (proper == "Sol") is skipped and rows without a B-V colour are dropped, matching the
shipped catalogue.

    python tools/build_star_catalog.py [--input PATH] [--out PATH] [--meta-out PATH] [--max-mag X]
"""

import argparse
import csv
import hashlib
import json
import os
import struct
import sys

DEFAULT_INPUT = os.path.join("tools", "downloads", "hyg_v42.csv")
DEFAULT_OUT = os.path.join("src", "client", "resources", "assets", "starradiance", "sky", "stars.dat")
DEFAULT_META = os.path.join("src", "client", "resources", "assets", "starradiance", "sky", "stars_meta.dat")
DEFAULT_MANIFEST = os.path.join("tools", "star_catalog_manifest.json")
CATALOG_EPOCH = "J2000"
LY_PER_PARSEC = 3.261563777

GREEK = {
    "alp": "\u03b1", "bet": "\u03b2", "gam": "\u03b3", "del": "\u03b4", "eps": "\u03b5",
    "zet": "\u03b6", "eta": "\u03b7", "the": "\u03b8", "iot": "\u03b9", "kap": "\u03ba",
    "lam": "\u03bb", "mu": "\u03bc", "nu": "\u03bd", "xi": "\u03be", "omi": "\u03bf",
    "pi": "\u03c0", "rho": "\u03c1", "sig": "\u03c3", "tau": "\u03c4", "ups": "\u03c5",
    "phi": "\u03c6", "chi": "\u03c7", "psi": "\u03c8", "ome": "\u03c9",
}
SUPERSCRIPTS = {"1": "\u00b9", "2": "\u00b2", "3": "\u00b3", "4": "\u2074", "5": "\u2075"}


def designation(row):
    """Human-readable Bayer/Flamsteed designation, e.g. 'alpha CMa' -> 'α CMa', '61 Cyg'."""
    con = (row.get("con") or "").strip()
    bayer = (row.get("bayer") or "").strip()
    flam = (row.get("flam") or "").strip()
    if not con:
        return ""
    if bayer:
        parts = bayer.split("-", 1)
        key = parts[0].lower()[:3]
        letter = GREEK.get(key)
        if letter is None:
            letter = bayer
        if len(parts) > 1 and parts[1] in SUPERSCRIPTS:
            letter += SUPERSCRIPTS[parts[1]]
        return f"{letter} {con}"
    if flam:
        return f"{flam} {con}"
    return ""


class StringPool:
    def __init__(self):
        self.index = {}
        self.values = []

    def intern(self, text):
        if not text:
            return -1
        existing = self.index.get(text)
        if existing is not None:
            return existing
        self.index[text] = len(self.values)
        self.values.append(text)
        return self.index[text]


def build(input_path, out_path, meta_path, max_mag):
    stars = []
    constellations = set()
    skipped_sun = 0
    skipped_no_colour = 0
    with open(input_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if (row.get("proper") or "").strip() == "Sol":
                skipped_sun += 1
                continue
            mag = row.get("mag") or ""
            ci = row.get("ci") or ""
            ra = row.get("ra") or ""
            dec = row.get("dec") or ""
            if mag == "" or ra == "" or dec == "" or ci == "":
                skipped_no_colour += 1
                continue
            magnitude = float(mag)
            if max_mag is not None and magnitude > max_mag:
                continue
            con = (row.get("con") or "").strip()
            if con:
                constellations.add(con)
            stars.append({
                "raDeg": float(ra) * 15.0,
                "decDeg": float(dec),
                "mag": magnitude,
                "bv": float(ci),
                "con": con,
                "proper": (row.get("proper") or "").strip(),
                "designation": designation(row),
                "spect": (row.get("spect") or "").strip(),
                "distLy": float(row.get("dist") or 0.0) * LY_PER_PARSEC,
            })

    stars.sort(key=lambda star: star["mag"])
    con_ids = sorted(constellations)
    con_index = {name: index for index, name in enumerate(con_ids)}
    pool = StringPool()

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as handle:
        handle.write(b"NWST")
        handle.write(struct.pack(">i", len(stars)))
        for star in stars:
            handle.write(struct.pack(">ffff", star["raDeg"], star["decDeg"], star["mag"], star["bv"]))

    with open(meta_path, "wb") as handle:
        handle.write(b"NWSM")
        handle.write(struct.pack(">i", len(stars)))
        handle.write(struct.pack(">i", len(con_ids)))
        for name in con_ids:
            payload = name.encode("utf-8")
            handle.write(struct.pack(">i", len(payload)))
            handle.write(payload)
        # The string pool is written after a placeholder pass so indices are stable.
        entries = []
        for star in stars:
            entries.append((
                con_index.get(star["con"], -1),
                pool.intern(star["proper"]),
                pool.intern(star["designation"]),
                pool.intern(star["spect"]),
                int(round(star["distLy"] * 100.0)) if star["distLy"] > 0.0 else -1,
            ))
        handle.write(struct.pack(">i", len(pool.values)))
        for value in pool.values:
            payload = value.encode("utf-8")
            handle.write(struct.pack(">i", len(payload)))
            handle.write(payload)
        for entry in entries:
            handle.write(struct.pack(">iiiii", *entry))

    digest = hashlib.sha256(open(input_path, "rb").read()).hexdigest()
    manifest = {
        "source": os.path.basename(input_path),
        "sourceSha256": digest,
        "catalogEpoch": CATALOG_EPOCH,
        "maxMagnitude": max_mag,
        "stars": len(stars),
        "constellations": len(con_ids),
        "strings": len(pool.values),
        "skippedSun": skipped_sun,
        "skippedNoColour": skipped_no_colour,
        "catalogBytes": os.path.getsize(out_path),
        "metaBytes": os.path.getsize(meta_path),
    }
    with open(DEFAULT_MANIFEST, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build the StarRadiance star resources from HYG v4.2")
    parser.add_argument("--input", default=DEFAULT_INPUT)
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--meta-out", default=DEFAULT_META)
    parser.add_argument("--max-mag", type=float, default=None)
    args = parser.parse_args(argv)
    if not os.path.exists(args.input):
        print(f"input not found: {args.input}", file=sys.stderr)
        return 1
    print(json.dumps(build(args.input, args.out, args.meta_out, args.max_mag), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
