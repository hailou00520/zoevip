#!/usr/bin/env python3
"""Extract launcher icons from pulled APKs into res/drawable-nodpi."""

from __future__ import annotations

import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK_DIR = ROOT / "tools" / "icon_extract"
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
AAPT = ROOT.parent / "android-sdk" / "build-tools" / "34.0.0" / "aapt.exe"

MAPPING = {
    "com_attempt_afusekt": "ic_adapt_afusekt",
    "com_feifeiduck_capyplayer": "ic_adapt_capyplayer",
    "com_oumi_utility_media_hub": "ic_adapt_vidhub",
    "com_ximalaya_ting_android": "ic_adapt_ximalaya",
    "com_mt_mtxx_mtxx": "ic_adapt_mtxx",
    "com_xs_fm": "ic_adapt_fanqie_fm",
    "com_dragon_read": "ic_adapt_dragon_read",
    "com_phoenix_read": "ic_adapt_hongguo",
    "com_kylin_read": "ic_adapt_kylin",
    "com_omarea_vtools": "ic_adapt_vtools",
    "com_mountains_hills": "ic_adapt_hills",
    "com_abjlvcha_main": "ic_adapt_lvcha",
}

FLUTTER_HINTS = (
    "assets/flutter_assets/assets/app_icon/",
    "assets/flutter_assets/assets/icon/",
    "assets/flutter_assets/assets/logo",
)

PENALTIES = (
    "notification",
    "status_bar",
    "splash",
    "placeholder",
    "empty",
    "default_avatar",
    "ad_icon",
    "lottie",
    "anim",
    "widget",
    "background",
)


def launcher_icon_path(apk: Path) -> str | None:
    if not AAPT.exists():
        return None
    try:
        out = subprocess.check_output(
            [str(AAPT), "dump", "badging", str(apk)],
            text=True,
            errors="replace",
        )
    except subprocess.CalledProcessError:
        return None

    best_path = None
    best_dpi = -1
    for line in out.splitlines():
        match = re.search(r"application-icon-(\d+):'([^']+)'", line)
        if not match:
            continue
        dpi = int(match.group(1))
        if dpi == 65534:
            continue
        if dpi > best_dpi:
            best_dpi = dpi
            best_path = match.group(2)
    if best_path and not best_path.endswith(".xml"):
        return best_path

    fallback = re.search(r"application-icon-\d+:'([^']+)'", out)
    if fallback and not fallback.group(1).endswith(".xml"):
        return fallback.group(1)
    return None


def score_entry(name: str, size: int) -> int:
    low = name.lower()
    if not (low.endswith(".png") or low.endswith(".webp")):
        return -999
    score = 10 if low.endswith(".png") else 8
    if any(h in low for h in FLUTTER_HINTS):
        score += 120
    if "mipmap-xxxhdpi" in low:
        score += 50
    elif "mipmap-xxhdpi" in low:
        score += 40
    elif "mipmap-xhdpi" in low:
        score += 30
    if "ic_launcher" in low and "foreground" not in low and "background" not in low:
        score += 45
    elif "ic_launcher_foreground" in low:
        score += 35
    elif "launcher" in low or "/icon" in low or "app_icon" in low:
        score += 25
    if "round" in low:
        score += 5
    for bad in PENALTIES:
        if bad in low:
            score -= 40
    score += min(size // 2048, 20)
    return score


def pick_icon(z: zipfile.ZipFile) -> str | None:
    best = None
    best_score = -1
    for info in z.infolist():
        if not (info.filename.endswith(".png") or info.filename.endswith(".webp")):
            continue
        s = score_entry(info.filename, info.file_size)
        if s > best_score:
            best_score = s
            best = info.filename
    return best


def zip_entry_for_icon(z: zipfile.ZipFile, icon_path: str | None) -> str | None:
    if icon_path:
        names = set(z.namelist())
        candidates = [icon_path, icon_path.lstrip("/")]
        for candidate in candidates:
            if candidate in names and not candidate.endswith(".xml"):
                return candidate
        base = Path(icon_path).name
        for name in names:
            if (name.endswith("/" + base) or name == base) and not name.endswith(".xml"):
                return name
    return pick_icon(z)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for apk_stem, out_name in MAPPING.items():
        apk = APK_DIR / f"{apk_stem}.apk"
        if not apk.exists():
            print(f"SKIP missing apk: {apk.name}")
            continue
        with zipfile.ZipFile(apk) as z:
            icon_path = launcher_icon_path(apk)
            chosen = zip_entry_for_icon(z, icon_path)
            if not chosen:
                print(f"FAIL no launcher icon in {apk.name}")
                continue
            data = z.read(chosen)
            ext = ".webp" if chosen.endswith(".webp") else ".png"
            for old in OUT_DIR.glob(f"{out_name}.*"):
                old.unlink()
            dest = OUT_DIR / f"{out_name}{ext}"
            dest.write_bytes(data)
            print(f"OK {out_name}{ext} <- {chosen} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
