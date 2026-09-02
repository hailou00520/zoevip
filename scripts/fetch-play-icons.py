#!/usr/bin/env python3
import re
from pathlib import Path

import requests

OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "drawable-nodpi"

PACKAGES = {
    "com.oumi.utility.media.hub": "ic_adapt_vidhub",
    "com.mt.mtxx.mtxx": "ic_adapt_mtxx",
}


def main() -> None:
    for pkg, name in PACKAGES.items():
        url = f"https://play.google.com/store/apps/details?id={pkg}&hl=en"
        html = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=30).text
        match = re.search(r"https://play-lh\.googleusercontent\.com/[^\"\\s>]+", html)
        if not match:
            print(f"FAIL {pkg}: icon url not found")
            continue
        icon_url = match.group(0)
        data = requests.get(icon_url, timeout=30).content
        dest = OUT / f"{name}.png"
        dest.write_bytes(data)
        print(f"OK {name}.png ({len(data)} bytes)")


if __name__ == "__main__":
    main()
