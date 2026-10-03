#!/usr/bin/env python3
"""Resume-capable parallel downloader for the slow NASA SVS host.

A single connection to svs.gsfc.nasa.gov is throttled to ~20 KB/s, so big TIFFs are
split into ranges fetched concurrently. Each range is written to <out>.partN and the
parts are concatenated on success; re-running resumes the incomplete parts.

    python tools/parallel_download.py <url> <out> [threads]
"""

import os
import sys
import threading
import urllib.request


def content_length(url):
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers["Content-Length"])


def fetch_range(url, start, end, path, idx, log):
    import time
    want = end - start + 1
    for attempt in range(40):
        try:
            have = os.path.getsize(path) if os.path.exists(path) else 0
            if have >= want:
                return
            s = start + have
            req = urllib.request.Request(url, headers={"Range": f"bytes={s}-{end}"})
            with urllib.request.urlopen(req, timeout=300) as r, open(path, "ab") as f:
                while True:
                    block = r.read(1 << 16)
                    if not block:
                        break
                    f.write(block)
            if os.path.getsize(path) >= want:
                return
        except Exception as exc:  # noqa: BLE001 - retry any transient failure
            log(f"  part {idx} attempt {attempt + 1}: {exc}")
            time.sleep(min(2 + attempt, 10))
    raise RuntimeError(f"part {idx} failed after retries")


def main():
    url, out = sys.argv[1], sys.argv[2]
    threads = int(sys.argv[3]) if len(sys.argv) > 3 else 16

    def log(msg):
        print(msg, flush=True)

    total = content_length(url)
    log(f"{os.path.basename(out)}: {total / 1e6:.1f} MB, {threads} threads")

    chunk = (total + threads - 1) // threads
    parts = []
    workers = []
    for i in range(threads):
        start = i * chunk
        end = min(total - 1, start + chunk - 1)
        if start > end:
            break
        path = f"{out}.part{i}"
        parts.append(path)
        t = threading.Thread(target=fetch_range, args=(url, start, end, path, i, log))
        t.start()
        workers.append(t)

    done = [0]

    def progress():
        log(f"  ... {sum(os.path.getsize(p) for p in parts if os.path.exists(p)) / 1e6:.1f} MB")

    for t in workers:
        t.join()

    if os.path.exists(out):
        os.remove(out)
    with open(out, "wb") as sink:
        for path in parts:
            with open(path, "rb") as f:
                sink.write(f.read())
            os.remove(path)
    log(f"{out}: complete, {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
