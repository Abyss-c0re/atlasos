"""USB Titan diagnostics. No user data u2014 props, package paths, crash/system lines only."""
from __future__ import annotations

import hashlib
import re
from typing import Callable

AdbRun = Callable[[str, str], str]

RE_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.I)
RE_PHONE = re.compile(r"(?<!\d)(?:\+?\d[\d .()-]{8,}\d)")
RE_IMEI = re.compile(r"\b\d{15}\b")
RE_MAC = re.compile(r"\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b")
RE_ANDROID_ID = re.compile(r"\b[0-9a-f]{16}\b")
RE_SSID = re.compile(r"(ssid|SSID|wifi_name)\s*[=:]\s*\S+", re.I)

LOG_GREP = (
    "FATAL EXCEPTION|AndroidRuntime|UnsatisfiedLinkError|TitanFm|"
    "titan2-analog|AUDIO_DEVICE_OUT_WIRED|analog_audio|VERIFY_FAIL|"
    "libaguifmjni|libcutils.so not found"
)


def serial_tag(serial: str) -> str:
    if not serial:
        return "none"
    return hashlib.sha256(serial.encode()).hexdigest()[:8]


def redact(text: str, serial: str = "") -> str:
    s = text or ""
    if serial:
        s = s.replace(serial, "<device>")
    s = RE_EMAIL.sub("<email>", s)
    s = RE_PHONE.sub("<phone>", s)
    s = RE_IMEI.sub("<imei>", s)
    s = RE_MAC.sub("<mac>", s)
    s = RE_SSID.sub(r"\1=<ssid>", s)
    return s


def _one(blob: str, key: str) -> str:
    for line in (blob or "").splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return ""


def collect(serial: str, adb_run: AdbRun) -> dict:
    """Pull machine-state only. Never /sdcard, accounts, contacts, media."""
    script = r"""
echo device=$(getprop ro.product.device)
echo model=$(getprop ro.product.model)
echo lineage=$(getprop ro.lineage.display.version)
echo profile=$(getprop ro.titanus2.profile)
echo usb_audio=$(getprop ro.titanus2.usb_audio)
echo boot=$(getprop sys.boot_completed)
echo analog=$(cat /sys/class/typec/port0-partner/accessory_mode 2>/dev/null)
echo acc_sh=$(test -f /system/bin/titan2-analog-acc.sh && echo 1 || echo 0)
echo acc_dex=$(test -f /system/etc/titan2_audio/acc.dex && echo 1 || echo 0)
echo acc_rc=$(test -f /system/etc/init/titan2-analog-acc.rc && echo 1 || echo 0)
echo policy=$(test -f /system/etc/titan2_audio/audio_policy_configuration.xml && echo 1 || echo 0)
echo fm=$(pm path com.android.fmradio 2>/dev/null | head -1)
echo controls=$(pm path com.titanus2.controls 2>/dev/null | head -1)
echo usbhid=$(pm path com.titanus2.usbhid 2>/dev/null | head -1)
echo treble=$(pm path me.phh.treble.app 2>/dev/null | head -1)
echo wired=$(cmd audio get-connected-output-devices 2>/dev/null)
""".strip()
    blob = redact(adb_run(serial, script), serial)
    crash = redact(
        adb_run(serial, "logcat -d -b crash -t 40 2>/dev/null | tail -40"),
        serial,
    )
    hits = redact(
        adb_run(
            serial,
            "logcat -d -t 250 2>/dev/null | grep -E '%s' | tail -40" % LOG_GREP,
        ),
        serial,
    )
    return {
        "device_tag": serial_tag(serial),
        "device": _one(blob, "device"),
        "model": _one(blob, "model"),
        "lineage": _one(blob, "lineage"),
        "profile": _one(blob, "profile"),
        "usb_audio": _one(blob, "usb_audio"),
        "boot": _one(blob, "boot"),
        "analog": _one(blob, "analog"),
        "acc_sh": _one(blob, "acc_sh"),
        "acc_dex": _one(blob, "acc_dex"),
        "acc_rc": _one(blob, "acc_rc"),
        "policy": _one(blob, "policy"),
        "fm": _one(blob, "fm"),
        "controls": _one(blob, "controls"),
        "usbhid": _one(blob, "usbhid"),
        "treble": _one(blob, "treble"),
        "wired": _one(blob, "wired"),
        "crash": crash[-2500:],
        "log_hits": hits[-2500:],
    }


def detect(snap: dict) -> list[dict]:
    """Rule findings. id is stable for issue dedupe."""
    out: list[dict] = []

    def add(fid: str, title: str, repo: str, detail: str, sev: str = "bug") -> None:
        out.append(
            {
                "id": fid,
                "title": title,
                "repo": repo,
                "detail": detail,
                "severity": sev,
            }
        )

    analog = (snap.get("analog") or "").strip()
    if analog == "analog_audio" and snap.get("acc_sh") != "1":
        add(
            "analog-acc-missing",
            "OEM Type-C analog accessory: analog-acc not in ROM",
            "atlasos",
            "accessory_mode=analog_audio but /system/bin/titan2-analog-acc.sh absent",
        )
    if analog == "analog_audio" and "WIRED_HEADPHONE" not in (snap.get("wired") or ""):
        add(
            "analog-not-routed",
            "OEM Type-C analog present but WIRED_HEADPHONES not connected",
            "atlasos",
            "wired=%s" % (snap.get("wired") or "?"),
        )
    if snap.get("policy") != "1":
        add(
            "usb-audio-policy-missing",
            "USB audio host policy XML missing from /system",
            "atlasos",
            "need /system/etc/titan2_audio/audio_policy_configuration.xml",
        )
    if "UnsatisfiedLinkError" in (snap.get("log_hits") or "") or "libcutils" in (
        snap.get("log_hits") or ""
    ):
        add(
            "fm-jni-namespace",
            "TitanFm native link failed (priv-app / libcutils)",
            "atlasos",
            "log hit UnsatisfiedLinkError or libcutils",
        )
    if not (snap.get("controls") or "").startswith("package:"):
        add(
            "controls-missing",
            "Titan Controls missing on connected device",
            "atlasos",
            "pm path com.titanus2.controls empty",
        )
    if not (snap.get("usbhid") or "").startswith("package:"):
        add(
            "usbhid-missing",
            "Titan USB HID missing on connected device",
            "atlasos",
            "pm path com.titanus2.usbhid empty",
        )
    if "FATAL EXCEPTION" in (snap.get("crash") or ""):
        add(
            "fatal-crash",
            "Crash buffer has FATAL EXCEPTION",
            "atlasos",
            (snap.get("crash") or "")[-400:],
            "crash",
        )
    return out


def format_diag(snap: dict, findings: list[dict], extra: str = "") -> str:
    lines = [
        "DIAG  device_tag=%s  %s  %s"
        % (snap.get("device_tag"), snap.get("model") or snap.get("device"), snap.get("lineage") or ""),
        "profile=%s  usb_audio=%s  analog=%s  wired=%s"
        % (snap.get("profile"), snap.get("usb_audio"), snap.get("analog"), snap.get("wired")),
        "acc_sh=%s acc_dex=%s acc_rc=%s policy=%s"
        % (snap.get("acc_sh"), snap.get("acc_dex"), snap.get("acc_rc"), snap.get("policy")),
        "controls=%s  usbhid=%s  fm=%s"
        % (
            "yes" if (snap.get("controls") or "").startswith("package:") else "no",
            "yes" if (snap.get("usbhid") or "").startswith("package:") else "no",
            "yes" if (snap.get("fm") or "").startswith("package:") else "no",
        ),
        "",
    ]
    if not findings:
        lines.append("No rule findings.")
    else:
        lines.append("Findings (%d)" % len(findings))
        for f in findings:
            lines.append("  [%s] %s u2014 %s" % (f["id"], f["title"], f["detail"]))
    if extra:
        lines.append("")
        lines.append(extra)
    return "\n".join(lines).rstrip() + "\n"
