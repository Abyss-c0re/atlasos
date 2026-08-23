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
    assert marker_unit("==> GSI systemimage (vanilla)") == 0.12
    assert marker_unit("exported pin: /tmp/x.img") == 0.92
    from cube_flasher import fmt_secs, estimate_flash, is_usb_serial

    assert fmt_secs(0) == "0s"
    assert fmt_secs(90) == "1m30s"
    assert estimate_flash(keep_data=True) > 0
    assert is_usb_serial("ABC123")
    assert not is_usb_serial("10.0.0.1:5555")
    assert not is_usb_serial("emulator-5554")
    assert not is_usb_serial("")
    from titan_rom import parse_flash_ts, parse_receipt_text, format_rom_summary, format_rom_report

    rec = parse_receipt_text(
        "# FLASH_DONE 20260823T151454Z\n"
        "super=/tmp/Titan2-LOS-EEA-fm-super.img\n"
        "wipe=0\n"
        "atlasos=c33f763\n"
    )
    assert rec["ts"] == "20260823T151454Z"
    assert rec["pin"] == "Titan2-LOS-EEA-fm-super.img"
    assert rec["wipe"] == "0"
    assert rec["atlasos"] == "c33f763"
    assert parse_flash_ts("20260823T151454Z").year == 2026
    props = {
        "ro.product.device": "Titan_2",
        "ro.lineage.display.version": "23-20260823-VANILLA-EXT4-GSI",
    }
    assert "Titan_2" in format_rom_summary(props, rec)
    report = format_rom_report(props, rec, ["c2b1617 analog acc"], [])
    assert "Updates since last flash (1)" in report
    assert "c2b1617 analog acc" in report
    from titan_diag import redact, detect, serial_tag
    from titan_issues import origin_repo, git_bin
    from pathlib import Path

    assert "<email>" in redact("mail me@x.com please")
    assert "ABC123" not in redact("serial ABC123 here", "ABC123")
    assert serial_tag("ABC123") == serial_tag("ABC123")
    assert serial_tag("ABC123") != serial_tag("OTHER")
    found = detect({
        "analog": "analog_audio",
        "acc_sh": "0",
        "wired": "[BUILTIN_SPEAKER]",
        "policy": "1",
        "log_hits": "",
        "crash": "",
        "controls": "package:/system/priv-app/TitanControls",
        "usbhid": "package:/system/priv-app/TitanUsbHid",
    })
    ids = {f["id"] for f in found}
    assert "analog-acc-missing" in ids
    assert "analog-not-routed" in ids
    assert Path(git_bin()).name == "git"
    from titan_fix import validate_diff, is_abyss_core, extract_diff
    bad = validate_diff("diff --git a/etc/passwd b/etc/passwd\n")
    assert bad
    ok = validate_diff(
        "diff --git a/patches/bin/titan2-analog-acc.sh b/patches/bin/titan2-analog-acc.sh\n"
        "--- a/patches/bin/titan2-analog-acc.sh\n"
        "+++ b/patches/bin/titan2-analog-acc.sh\n"
        "@@ -1 +1 @@\n"
        "-old\n"
        "+new\n"
    )
    assert ok == ""
    assert is_abyss_core("Abyss-c0re")
    assert is_abyss_core("abyss-core")
    assert not is_abyss_core("someone-else")
    d = extract_diff("note\n```diff\ndiff --git a/patches/x b/patches/x\n+hi\n```\n")
    assert d.startswith("diff --git")
    print("progress parser ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
