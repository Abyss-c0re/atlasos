#!/usr/bin/env python3
"""Collapse AtlasOS + workshop copies to one SoT file + relative symlinks.

SoT:
  patches/bin/<name>              titan2-*.sh atlas-*.sh apt-hybrid.sh
  patches/init/<name>             *.rc
  apps/titan_atlas/assets/bin/    atlas native ELFs (enter, auth, sudo, …)
  packages/gsi_product/prebuilt_touchpadd/  packed touchpadd ELF + idc
  packages/titan_usb_hid_system/  HID gadget scripts
  apps/<app>/permissions/         privapp XML

Duplicates in prebuilt_*, assets, magisk modules become relative links.
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

A = Path(__file__).resolve().parents[1]
W = Path(os.environ.get("WORKSHOP", A.parent / "titanus2")).resolve()

ELF_NAMES = {
    "atlas",
    "atlas-auth",
    "atlas-auth-askpass",
    "atlas-enter",
    "atlas-enterd",
    "atlas-lpctl",
    "atlas-seatd",
    "atlas-seat-input",
    "atlas-seat-pull",
    "atlas-seat-push",
    "atlas-sudo",
    "ptyexec",
    "bash",
    "su",
    "sudo",
    "quest-usbip-host",
    "atlas-heal-home",
}

SCAN_A = [
    A / "patches/bin",
    A / "patches/init",
    A / "packages/gsi_product/prebuilt_sysbin",
    A / "packages/gsi_product/prebuilt_atlas",
    A / "packages/gsi_product/prebuilt_touchpadd",
    A / "packages/gsi_product/prebuilt_usb_hid",
    A / "packages/gsi_product/prebuilt_apps",
    A / "packages/titan_atlas/scripts",
    A / "apps/titan_atlas/assets/bin",
    A / "packages/titan_usb_hid_system",
]

SCAN_W_EXTRA = [
    W / "packages/magisk_titan2_pad_agent/system/bin",
    W / "packages/magisk_titan2_atlas_hybrid/system/bin",
    W / "packages/magisk_titan2_touchpadd/system/bin",
    W / "packages/magisk_titan2_usb_hid/system/bin",
]


def real_file(p: Path) -> bool:
    return p.is_file() and not p.is_symlink()


def collect(name: str) -> list[Path]:
    out = []
    for root in SCAN_A + [r for r in SCAN_W_EXTRA if r.exists()]:
        p = root / name
        if p.is_file() or p.is_symlink():
            out.append(p)
    return out


def sot_for(name: str, copies: list[Path]) -> Path:
    reals = [p for p in copies if real_file(p) and p.is_relative_to(A)]
    if name in ELF_NAMES:
        pref = A / "apps/titan_atlas/assets/bin" / name
        if pref.exists():
            return pref
    if name == "titan2-touchpadd":
        return A / "packages/gsi_product/prebuilt_touchpadd/titan2-touchpadd"
    if name.endswith(".rc"):
        pref = A / "patches/init" / name
        if pref.exists() or reals:
            return pref if pref.exists() else max(reals, key=lambda p: p.stat().st_size)
    if name.endswith(".xml") and name.startswith("privapp-"):
        for p in reals:
            if "apps/" in str(p) and "permissions" in str(p):
                return p
    if name.endswith(".sh") or name.startswith("titan2-") or name.startswith("atlas-") or name == "apt-hybrid.sh":
        pref = A / "patches/bin" / name
        if pref.exists() and real_file(pref):
            return pref
        if reals:
            winner = max(reals, key=lambda p: p.stat().st_size)
            return winner
    if name in {"enable_hid.sh", "service.sh", "titan2-usb-hid-service.sh"}:
        return A / "packages/titan_usb_hid_system" / name
    if reals:
        return max(reals, key=lambda p: p.stat().st_size)
    # all already links — pick first atlasos target
    for p in copies:
        if str(p).startswith(str(A)):
            return p.resolve()
    return copies[0].resolve()


def ensure_sot(sot: Path, winner: Path) -> None:
    sot.parent.mkdir(parents=True, exist_ok=True)
    if sot.resolve() == winner.resolve() and real_file(sot):
        return
    if sot == winner:
        return
    if not sot.exists() or sot.is_symlink() or (
        real_file(sot) and real_file(winner) and sot.stat().st_size < winner.stat().st_size
    ):
        if sot.exists() or sot.is_symlink():
            sot.unlink()
        shutil.copy2(winner, sot)


def relink(sot: Path, dup: Path) -> None:
    if dup.resolve() == sot.resolve() and dup.is_symlink():
        return
    if dup == sot:
        return
    rel = os.path.relpath(sot, dup.parent)
    if dup.exists() or dup.is_symlink():
        dup.unlink()
    dup.symlink_to(rel)
    print(f"  link {dup} -> {rel}")


def names_to_collapse() -> set[str]:
    names: set[str] = set()
    for root in SCAN_A:
        if not root.exists():
            continue
        for p in root.iterdir():
            if p.name.startswith("."):
                continue
            if p.suffix in {".bp", ".example", ".md", ".url"}:
                continue
            if p.name in {"Android.bp", "README.md", "TitanAtlas.apk"}:
                continue
            names.add(p.name)
    # plus magisk extras
    for root in SCAN_W_EXTRA:
        if not root.exists():
            continue
        for p in root.iterdir():
            if p.suffix in {".sh", ".rc"} or p.name.startswith("atlas") or p.name.startswith("titan2-"):
                names.add(p.name)
    return names


def main() -> int:
    n = 0
    for name in sorted(names_to_collapse()):
        copies = collect(name)
        if len(copies) < 2 and name not in ELF_NAMES:
            continue
        if not copies:
            continue
        winner_candidates = [p for p in copies if real_file(p)]
        if not winner_candidates:
            continue
        winner = max(winner_candidates, key=lambda p: p.stat().st_size)
        sot = sot_for(name, copies)
        if not sot.exists() or sot.is_symlink() or (
            real_file(sot) and real_file(winner) and sot.stat().st_size < winner.stat().st_size
        ):
            ensure_sot(sot, winner)
        # if SoT was a smaller patches/bin, we may have overwritten with winner
        if not real_file(sot):
            ensure_sot(sot, winner)
        print(f"SoT {name} = {sot.relative_to(A) if sot.is_relative_to(A) else sot}")
        for dup in copies:
            if dup.resolve() == sot.resolve() and not dup.is_symlink() and dup == sot:
                continue
            if dup == sot:
                continue
            relink(sot, dup)
            n += 1
    print(f"relinked {n} duplicates")
    return 0


if __name__ == "__main__":
    sys.exit(main())
