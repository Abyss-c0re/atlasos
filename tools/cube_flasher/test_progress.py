#!/usr/bin/env python3
"""Sanity: live parser never treats leftover 100% as complete."""
from cube_flasher import live_unit, marker_unit


def main() -> int:
    assert live_unit("[  0% ]") == 0.0
    assert live_unit("[ 47% ]") == 0.47
    assert live_unit("[ 99% ]") == 0.99
    assert live_unit("[100% ]") is None
    assert live_unit("READY 100%") is None
    assert live_unit("build complete 100%") is None
    assert live_unit("Sending sparse 'super' 3/10 (262140 KB)") == 0.3
    assert live_unit("Sending 'super' 61%") == 0.61
    assert live_unit("   12%") == 0.12
    assert live_unit("   100%") is None
    assert marker_unit("=== kitchen cook profile=lab_rootless") == 0.03
    assert marker_unit("lpunpack stock super...") == 0.18
    assert marker_unit("Flashing super...") == 0.18
    print("progress parser ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
