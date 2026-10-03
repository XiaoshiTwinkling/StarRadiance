#!/usr/bin/env python3
"""Checks StarRadiance's translation keys.

1. en_us.json and zh_cn.json must have exactly the same key set.
2. Every hud./screen./key.starradiance.* literal used in the Java sources must exist in both files.
3. No translation may contain a stray '%' (that would crash String.format); use '%%'.

    python tools/check_lang_keys.py
"""

import json
import os
import re
import sys

LANG_DIR = os.path.join("src", "client", "resources", "assets", "starradiance", "lang")
SRC_DIR = "src"
KEY_PATTERN = re.compile(
    r'"((?:(?:hud|screen)\.starradiance\.[A-Za-z0-9_.]+)'
    r'|(?:key\.starradiance\.[A-Za-z0-9_.]+)'
    r'|(?:key\.categories\.starradiance))"'
)


def has_stray_percent(value):
    """True when a '%' is not part of '%%' or a valid String.format conversion."""
    index = 0
    while index < len(value):
        if value[index] != "%":
            index += 1
            continue
        index += 1
        if index < len(value) and value[index] == "%":
            index += 1
            continue
        # optional positional index (e.g. 1$), optional precision (.2), then the conversion letter
        while index < len(value) and (value[index].isdigit() or value[index] == "$"):
            index += 1
        if index < len(value) and value[index] == ".":
            index += 1
            while index < len(value) and value[index].isdigit():
                index += 1
        if index < len(value) and value[index] in "sdfxbn%":
            index += 1
            continue
        return True
    return False


def load(name):
    with open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def main():
    english = load("en_us.json")
    chinese = load("zh_cn.json")
    problems = []

    only_english = sorted(set(english) - set(chinese))
    only_chinese = sorted(set(chinese) - set(english))
    if only_english:
        problems.append("keys only in en_us: " + ", ".join(only_english))
    if only_chinese:
        problems.append("keys only in zh_cn: " + ", ".join(only_chinese))

    used = set()
    for root, _, files in os.walk(SRC_DIR):
        for name in files:
            if not name.endswith(".java"):
                continue
            with open(os.path.join(root, name), encoding="utf-8") as handle:
                used.update(KEY_PATTERN.findall(handle.read()))

    # A literal that ends with a dot is the prefix of a key built at runtime
    # ("hud.starradiance.planet." + name), not a key of its own.
    used = {key for key in used if not key.endswith(".")}
    missing = sorted(key for key in used if key not in english)
    if missing:
        problems.append("keys used in code but missing from lang files: " + ", ".join(missing))

    for name, table in (("en_us", english), ("zh_cn", chinese)):
        for key, value in table.items():
            if isinstance(value, str) and has_stray_percent(value):
                problems.append(f"{name} {key}: stray '%' (escape as %%)")

    if problems:
        for problem in problems:
            print("FAIL " + problem)
        return 1
    print(f"lang ok: {len(english)} keys, {len(used)} referenced from code")
    # Constellation and star names are assembled at runtime ("constellation.starradiance." + id),
    # so they never appear as literals in the sources.
    dynamic = ("constellation.starradiance.", "star.starradiance.", "hud.starradiance.planet.",
               "deepsky.starradiance.", "hud.starradiance.deepsky.type.")
    unused = sorted(key for key in set(english) - used if not key.startswith(dynamic))
    if unused:
        print("note: not referenced from code: " + ", ".join(unused))
    return 0


if __name__ == "__main__":
    sys.exit(main())
