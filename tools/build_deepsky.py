#!/usr/bin/env python3
"""Build the bright deep-sky object table (galaxies, nebulae and clusters).

The list is a curated set of the objects an unaided eye or binoculars can actually find: the
famous Messier showpieces plus a handful of southern objects (the Magellanic Clouds, 47 Tucanae,
Omega Centauri, Centaurus A). Positions are J2000, rounded from the Messier/NGC catalogues.

Every row is validated against the shipped HYG catalogue: the nearest HYG star that belongs to the
same constellation must be close by, which catches a mistyped right ascension or declination
immediately. Pass --no-verify to skip that (it needs the HYG CSV).

Outputs:
    src/client/resources/assets/starradiance/sky/deepsky.dat
        "NDS1" + int32 count + per object: id, name, type, ra, dec, magnitude, major', minor'
    tools/deepsky_manifest.json

    python tools/build_deepsky.py
"""

import argparse
import csv
import json
import os
import struct
import sys

OUT_PATH = os.path.join("src", "client", "resources", "assets", "starradiance", "sky", "deepsky.dat")
MANIFEST_PATH = os.path.join("tools", "deepsky_manifest.json")
STAR_META = os.path.join("src", "client", "resources", "assets", "starradiance", "sky", "stars_meta.dat")
CSV_CANDIDATES = [
    os.path.join("tools", "downloads", "hyg_v42.csv"),
    r"C:\Users\17305\Desktop\Reference\hyg_v42.csv",
]

GALAXY, OPEN, GLOBULAR, NEBULA, PLANETARY, REMNANT = range(6)
TYPE_NAMES = ["galaxy", "open cluster", "globular cluster", "nebula", "planetary nebula",
              "supernova remnant"]

# id, name, constellation, type, RA (deg), Dec (deg), V mag, major axis ('), minor axis (')
OBJECTS = [
    # --- galaxies -------------------------------------------------------------
    ("M31", "Andromeda Galaxy", "And", GALAXY, 10.6847, 41.2691, 3.4, 190.0, 60.0),
    ("M32", "Le Gentil", "And", GALAXY, 10.6743, 40.8652, 8.1, 8.0, 6.0),
    ("M110", "Edward Young's Galaxy", "And", GALAXY, 10.0919, 41.6853, 8.9, 22.0, 11.0),
    ("M33", "Triangulum Galaxy", "Tri", GALAXY, 23.4621, 30.6602, 5.7, 62.0, 39.0),
    ("M51", "Whirlpool Galaxy", "CVn", GALAXY, 202.4696, 47.1953, 8.4, 11.0, 7.0),
    ("M63", "Sunflower Galaxy", "CVn", GALAXY, 198.9556, 42.0293, 8.6, 13.0, 7.0),
    ("M94", "Croc's Eye Galaxy", "CVn", GALAXY, 192.7210, 41.1203, 8.2, 11.0, 9.0),
    ("M106", "M106", "CVn", GALAXY, 184.7396, 47.3037, 8.4, 19.0, 8.0),
    ("M64", "Black Eye Galaxy", "Com", GALAXY, 194.1821, 21.6826, 8.5, 10.0, 5.0),
    ("M81", "Bode's Galaxy", "UMa", GALAXY, 148.8882, 69.0653, 6.9, 27.0, 14.0),
    ("M82", "Cigar Galaxy", "UMa", GALAXY, 148.9685, 69.6797, 8.4, 11.0, 5.0),
    ("M101", "Pinwheel Galaxy", "UMa", GALAXY, 210.8024, 54.3488, 7.9, 29.0, 27.0),
    ("M87", "Virgo A", "Vir", GALAXY, 187.7059, 12.3911, 8.6, 8.0, 7.0),
    ("M104", "Sombrero Galaxy", "Vir", GALAXY, 189.9976, -11.6231, 8.0, 9.0, 4.0),
    ("M83", "Southern Pinwheel", "Hya", GALAXY, 204.2538, -29.8657, 7.5, 13.0, 12.0),
    ("NGC253", "Sculptor Galaxy", "Scl", GALAXY, 11.8880, -25.2882, 7.1, 27.0, 7.0),
    ("NGC5128", "Centaurus A", "Cen", GALAXY, 201.3651, -43.0191, 6.8, 26.0, 20.0),
    ("LMC", "Large Magellanic Cloud", "Dor", GALAXY, 80.8938, -69.7561, 0.9, 645.0, 550.0),
    ("SMC", "Small Magellanic Cloud", "Tuc", GALAXY, 13.1583, -72.8003, 2.7, 319.0, 205.0),
    # --- globular clusters ----------------------------------------------------
    ("M2", "M2", "Aqr", GLOBULAR, 323.3626, -0.8232, 6.5, 16.0, 16.0),
    ("M3", "M3", "CVn", GLOBULAR, 205.5484, 28.3773, 6.2, 18.0, 18.0),
    ("M4", "M4", "Sco", GLOBULAR, 245.8967, -26.5256, 5.9, 26.0, 26.0),
    ("M5", "M5", "Ser", GLOBULAR, 229.6384, 2.0810, 5.6, 23.0, 23.0),
    ("M10", "M10", "Oph", GLOBULAR, 254.2877, -4.0993, 6.6, 20.0, 20.0),
    ("M12", "M12", "Oph", GLOBULAR, 251.8091, -1.9486, 6.7, 16.0, 16.0),
    ("M13", "Hercules Cluster", "Her", GLOBULAR, 250.4234, 36.4613, 5.8, 20.0, 20.0),
    ("M15", "M15", "Peg", GLOBULAR, 322.4930, 12.1670, 6.2, 18.0, 18.0),
    ("M22", "M22", "Sgr", GLOBULAR, 279.0998, -23.9048, 5.1, 32.0, 32.0),
    ("M92", "M92", "Her", GLOBULAR, 259.2808, 43.1359, 6.4, 14.0, 14.0),
    ("NGC5139", "Omega Centauri", "Cen", GLOBULAR, 201.6967, -47.4795, 3.7, 36.0, 36.0),
    ("NGC104", "47 Tucanae", "Tuc", GLOBULAR, 6.0238, -72.0813, 4.0, 31.0, 31.0),
    # --- open clusters --------------------------------------------------------
    ("M6", "Butterfly Cluster", "Sco", OPEN, 265.0500, -32.2167, 4.2, 25.0, 25.0),
    ("M7", "Ptolemy Cluster", "Sco", OPEN, 268.4500, -34.8167, 3.3, 80.0, 80.0),
    ("M11", "Wild Duck Cluster", "Sct", OPEN, 282.7660, -6.2700, 5.8, 32.0, 32.0),
    ("M34", "M34", "Per", OPEN, 51.1500, 42.7167, 5.5, 35.0, 35.0),
    ("M35", "M35", "Gem", OPEN, 92.2333, 24.3333, 5.1, 28.0, 28.0),
    ("M39", "M39", "Cyg", OPEN, 323.0500, 48.4333, 4.6, 32.0, 32.0),
    ("M41", "M41", "CMa", OPEN, 101.5000, -20.7500, 4.5, 38.0, 38.0),
    ("M44", "Beehive Cluster", "Cnc", OPEN, 130.1000, 19.6667, 3.7, 95.0, 95.0),
    ("M45", "Pleiades", "Tau", OPEN, 56.7500, 24.1167, 1.6, 110.0, 110.0),
    ("M46", "M46", "Pup", OPEN, 115.4500, -14.8167, 6.1, 27.0, 27.0),
    ("M47", "M47", "Pup", OPEN, 114.1500, -14.5000, 4.4, 30.0, 30.0),
    ("M48", "M48", "Hya", OPEN, 123.4500, -5.7500, 5.5, 54.0, 54.0),
    ("M67", "M67", "Cnc", OPEN, 132.8500, 11.8167, 6.1, 30.0, 30.0),
    ("Hyades", "Hyades", "Tau", OPEN, 66.7500, 15.8667, 0.5, 330.0, 330.0),
    ("NGC869", "Double Cluster", "Per", OPEN, 35.0000, 57.1333, 3.7, 60.0, 60.0),
    # --- nebulae --------------------------------------------------------------
    ("M1", "Crab Nebula", "Tau", REMNANT, 83.6331, 22.0145, 8.4, 6.0, 4.0),
    ("M8", "Lagoon Nebula", "Sgr", NEBULA, 270.9000, -24.3833, 6.0, 90.0, 40.0),
    ("M16", "Eagle Nebula", "Ser", NEBULA, 274.7000, -13.7833, 6.4, 35.0, 28.0),
    ("M17", "Omega Nebula", "Sgr", NEBULA, 275.1960, -16.1833, 6.0, 46.0, 37.0),
    ("M20", "Trifid Nebula", "Sgr", NEBULA, 270.6750, -23.0333, 6.3, 28.0, 28.0),
    ("M42", "Orion Nebula", "Ori", NEBULA, 83.8221, -5.3911, 4.0, 85.0, 60.0),
    ("M43", "De Mairan's Nebula", "Ori", NEBULA, 83.8800, -5.2667, 9.0, 20.0, 15.0),
    ("M78", "M78", "Ori", NEBULA, 86.6908, 0.0792, 8.3, 8.0, 6.0),
    ("M27", "Dumbbell Nebula", "Vul", PLANETARY, 299.9015, 22.7211, 7.5, 8.0, 6.0),
    ("M57", "Ring Nebula", "Lyr", PLANETARY, 283.3960, 33.0292, 8.8, 3.0, 2.0),
    ("M76", "Little Dumbbell", "Per", PLANETARY, 25.5821, 51.5753, 10.1, 3.0, 2.0),
    ("M97", "Owl Nebula", "UMa", PLANETARY, 168.6988, 55.0192, 9.9, 3.0, 3.0),
]


def read_constellation_ids():
    with open(STAR_META, "rb") as handle:
        data = handle.read()
    if data[0:4] != b"NWSM":
        raise ValueError("stars_meta.dat has an invalid header")
    count = struct.unpack_from(">i", data, 8)[0]
    offset = 12
    ids = []
    for _ in range(count):
        length = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        ids.append(data[offset:offset + length].decode("utf-8"))
        offset += length
    return set(ids)


def verify(csv_path):
    """Every object must sit near a HYG star of its own constellation."""
    stars = []
    with open(csv_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            con = (row.get("con") or "").strip()
            ra = (row.get("ra") or "").strip()
            dec = (row.get("dec") or "").strip()
            if not con or not ra or not dec:
                continue
            mag = (row.get("mag") or "").strip()
            try:
                stars.append((con, float(ra) * 15.0, float(dec), float(mag or 9.0)))
            except ValueError:
                continue
    worst = 0.0
    for object_id, _name, con, _type, ra, dec, _mag, _major, _minor in OBJECTS:
        best = 360.0
        for star_con, star_ra, star_dec, _star_mag in stars:
            if star_con != con:
                continue
            delta = abs(star_ra - ra)
            if delta > 180.0:
                delta = 360.0 - delta
            distance = (delta * 0.85) ** 2 + (star_dec - dec) ** 2
            if distance < best:
                best = distance
        best = best ** 0.5
        worst = max(worst, best)
        flag = "ok  " if best <= 2.5 else "FAIL"
        print(f"  {flag} {object_id:<10} {con}  nearest {con} star {best:6.2f} deg away")
        if best > 2.5:
            raise SystemExit(f"{object_id} is not in {con}: check its coordinates")
    print(f"  worst nearest-star distance {worst:.2f} deg")


def write(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(b"NDS1")
        handle.write(struct.pack(">i", len(OBJECTS)))
        for object_id, name, con, kind, ra, dec, mag, major, minor in OBJECTS:
            for text in (object_id, name, con):
                payload = text.encode("utf-8")
                handle.write(struct.pack(">i", len(payload)))
                handle.write(payload)
            handle.write(struct.pack(">ifffff", kind, ra, dec, mag, major, minor))
    return os.path.getsize(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build the StarRadiance deep-sky table")
    parser.add_argument("--out", default=OUT_PATH)
    parser.add_argument("--csv", default=None)
    parser.add_argument("--no-verify", action="store_true")
    args = parser.parse_args(argv)

    ids = read_constellation_ids()
    for object_id, _name, con, *_rest in OBJECTS:
        if con not in ids:
            raise SystemExit(f"{object_id}: unknown constellation id {con}")

    if not args.no_verify:
        csv_path = args.csv or next((path for path in CSV_CANDIDATES if os.path.exists(path)), None)
        if csv_path is None:
            print("  HYG csv not found; skipping verification", file=sys.stderr)
        else:
            print(f"verifying against {csv_path}")
            verify(csv_path)

    size = write(args.out)
    manifest = {
        "objects": len(OBJECTS),
        "types": TYPE_NAMES,
        "bytes": size,
        "epoch": "J2000",
        "source": "Messier / NGC catalogue positions (public domain astronomical data)",
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    print(f"  wrote {args.out} ({size} bytes, {len(OBJECTS)} objects)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
