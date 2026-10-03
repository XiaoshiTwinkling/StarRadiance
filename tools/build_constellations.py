#!/usr/bin/env python3
"""Build the constellation-line resource from the d3-celestial data set.

Source (BSD-3-Clause, fetched once at build time; the runtime never needs the network):
    https://cdn.jsdelivr.net/gh/ofrohn/d3-celestial@master/data/constellations.lines.json
    https://unpkg.com/d3-celestial/data/constellations.lines.json   (fallback)

Outputs:
    src/client/resources/assets/starradiance/sky/constellations.dat
        "NWCL" + int32 idCount + (int32 len + utf8)*
        + per id: int32 polylineCount
        + then per id, per polyline: int32 vertexCount + vertexCount * (float32 raDeg, decDeg)
        The id list is read from stars_meta.dat, so the indices match the star catalogue.
    src/client/resources/assets/starradiance/sky/constellations.NOTICE.txt
        The BSD-3-Clause notice and the source URL.

If the download fails the tool falls back to a procedural figure: for each constellation it links
the stars brighter than magnitude 5 with a greedy minimum spanning tree.

    python tools/build_constellations.py

Run tools/build_star_catalog.py first: the id list comes from stars_meta.dat.
"""

import json
import math
import os
import ssl
import struct
import sys
import urllib.request

SKY_DIR = os.path.join("src", "client", "resources", "assets", "starradiance", "sky")
META_PATH = os.path.join(SKY_DIR, "stars_meta.dat")
CATALOG_PATH = os.path.join(SKY_DIR, "stars.dat")
OUT_PATH = os.path.join(SKY_DIR, "constellations.dat")
NOTICE_PATH = os.path.join(SKY_DIR, "constellations.NOTICE.txt")
DOWNLOAD_DIR = os.path.join("tools", "downloads")
LINES_CACHE = os.path.join(DOWNLOAD_DIR, "constellations.lines.json")
LICENSE_CACHE = os.path.join(DOWNLOAD_DIR, "d3-celestial.LICENSE.txt")

LINES_URLS = [
    "https://cdn.jsdelivr.net/gh/ofrohn/d3-celestial@master/data/constellations.lines.json",
    "https://unpkg.com/d3-celestial/data/constellations.lines.json",
]
LICENSE_URLS = [
    "https://cdn.jsdelivr.net/gh/ofrohn/d3-celestial@master/LICENSE",
    "https://unpkg.com/d3-celestial/LICENSE",
]

BAND_LIMIT = 5.0


def fetch(urls, what, cache_path):
    """Downloads, falling back to the on-disk cache so rebuilds stay reproducible offline."""
    context = ssl._create_unverified_context()
    for url in urls:
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
            with urllib.request.urlopen(request, timeout=45, context=context) as response:
                data = response.read()
            if data:
                print(f"  {what} <- {url} ({len(data)} bytes)")
                os.makedirs(DOWNLOAD_DIR, exist_ok=True)
                with open(cache_path, "wb") as handle:
                    handle.write(data)
                return data, url
        except Exception as exc:  # noqa: BLE001
            print(f"  FAIL {url}: {type(exc).__name__} {exc}")
    if os.path.exists(cache_path):
        with open(cache_path, "rb") as handle:
            data = handle.read()
        print(f"  {what} <- cached {cache_path} ({len(data)} bytes)")
        return data, None
    return None, None


def read_meta():
    """Returns (constellation ids, per-star constellation index, magnitudes, ra/dec)."""
    with open(META_PATH, "rb") as handle:
        data = handle.read()
    if data[0:4] != b"NWSM":
        raise ValueError("stars_meta.dat has an invalid header")

    # Layout: magic | int32 count | int32 idCount + ids | int32 stringCount + strings
    #         | count * (int32 conIndex, name, designation, spectral, distanceCentily)
    offset = 4

    def read_int():
        nonlocal offset
        value = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        return value

    def read_string():
        nonlocal offset
        length = read_int()
        value = data[offset:offset + length].decode("utf-8")
        offset += length
        return value

    count = read_int()
    id_count = read_int()
    ids = [read_string() for _ in range(id_count)]
    string_count = read_int()
    for _ in range(string_count):
        read_string()

    entries = struct.unpack_from(">" + "i" * (count * 5), data, offset)
    offset += count * 20
    stars = list(entries[0::5])
    if offset != len(data):
        raise ValueError(f"stars_meta.dat has {len(data) - offset} trailing bytes")

    with open(CATALOG_PATH, "rb") as handle:
        catalog = handle.read()
    if catalog[0:4] != b"NWST":
        raise ValueError("stars.dat has an invalid header")
    catalog_count = struct.unpack(">i", catalog[4:8])[0]
    if catalog_count != count:
        raise ValueError("stars.dat and stars_meta.dat disagree on the star count")
    mags = []
    ras = []
    decs = []
    for i in range(catalog_count):
        ra, dec, mag, _bv = struct.unpack(">ffff", catalog[8 + 16 * i:24 + 16 * i])
        ras.append(ra)
        decs.append(dec)
        mags.append(mag)
    return ids, stars, mags, ras, decs


def procedural_figures(ids, stars, mags, ras, decs):
    """Fallback: chain the bright stars of each constellation with a greedy MST."""
    print("  falling back to procedural constellation figures (greedy MST)")
    vectors = []
    for i in range(len(ras)):
        ra = math.radians(ras[i])
        dec = math.radians(decs[i])
        vectors.append((math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra), math.sin(dec)))
    figures = {}
    for index, name in enumerate(ids):
        members = [i for i in range(len(stars)) if stars[i] == index and mags[i] <= BAND_LIMIT]
        if len(members) < 2:
            continue
        used = [members[0]]
        rest = set(members[1:])
        lines = []
        while rest:
            best = None
            for a in used:
                for b in rest:
                    dot = sum(vectors[a][k] * vectors[b][k] for k in range(3))
                    distance = 1.0 - min(1.0, dot)
                    if best is None or distance < best[0]:
                        best = (distance, a, b)
            _, a, b = best
            lines.append([[ras[a], decs[a]], [ras[b], decs[b]]])
            used.append(b)
            rest.discard(b)
        figures[name] = lines
    return figures


def source_figures(payload, ids):
    """Maps the GeoJSON features onto the catalogue's constellation ids."""
    known = {name.lower(): name for name in ids}
    figures = {}
    skipped = []
    for feature in payload["features"]:
        key = str(feature.get("id", "")).strip().lower()
        target = known.get(key)
        if target is None:
            skipped.append(feature.get("id"))
            continue
        lines = []
        for line in feature["geometry"]["coordinates"]:
            if len(line) >= 2:
                # d3-celestial stores right ascension in degrees but lets it run negative west of
                # 0h (e.g. Andromeda starts at -5.47); normalise into [0, 360).
                lines.append([[float(point[0]) % 360.0, float(point[1])] for point in line])
        if lines:
            # Serpens is split into Caput/Cauda features that share the IAU id "Ser", so append.
            figures.setdefault(target, []).extend(lines)
    if skipped:
        print(f"  note: skipped source features without a catalogue match: {', '.join(map(str, skipped))}")
    return figures


def main():
    if not os.path.exists(META_PATH) or not os.path.exists(CATALOG_PATH):
        print("run tools/build_star_catalog.py first", file=sys.stderr)
        return 1
    ids, stars, mags, ras, decs = read_meta()
    print(f"catalogue: {len(stars)} stars, {len(ids)} constellation ids")

    payload_raw, source_url = fetch(LINES_URLS, "constellation lines", LINES_CACHE)
    if payload_raw is not None:
        figures = source_figures(json.loads(payload_raw), ids)
    else:
        figures = procedural_figures(ids, stars, mags, ras, decs)

    vertices = sum(len(line) for lines in figures.values() for line in lines)
    print(f"figures: {len(figures)} constellations, {vertices} vertices")

    with open(OUT_PATH, "wb") as handle:
        handle.write(b"NWCL")
        handle.write(struct.pack(">i", len(ids)))
        for name in ids:
            encoded = name.encode("utf-8")
            handle.write(struct.pack(">i", len(encoded)))
            handle.write(encoded)
        for name in ids:
            handle.write(struct.pack(">i", len(figures.get(name, []))))
        for name in ids:
            for line in figures.get(name, []):
                handle.write(struct.pack(">i", len(line)))
                for ra, dec in line:
                    if not (0.0 <= ra < 360.0) or not (-90.0 <= dec <= 90.0):
                        raise ValueError(f"{name}: coordinate out of range ({ra}, {dec})")
                    handle.write(struct.pack(">ff", ra, dec))
    print(f"  wrote {OUT_PATH} ({os.path.getsize(OUT_PATH)} bytes)")

    license_raw, license_url = fetch(LICENSE_URLS, "d3-celestial LICENSE", LICENSE_CACHE)
    body = license_raw.decode("utf-8", "replace") if license_raw else (
        "BSD 3-Clause Licence. Copyright (c) Olaf Frohn. All rights reserved."
    )
    with open(NOTICE_PATH, "w", encoding="utf-8") as handle:
        handle.write("Constellation line data\n")
        handle.write("=======================\n\n")
        handle.write("constellations.dat is derived from the d3-celestial project by Olaf Frohn\n")
        handle.write("(https://github.com/ofrohn/d3-celestial), file data/constellations.lines.json,\n")
        handle.write("distributed under the BSD 3-Clause licence. Source used for this build:\n")
        handle.write(f"  {source_url or 'procedural fallback (no download)'}\n")
        if license_url:
            handle.write(f"  licence text from {license_url}\n")
        handle.write("\n")
        handle.write(body.rstrip() + "\n")
    print(f"  wrote {NOTICE_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
