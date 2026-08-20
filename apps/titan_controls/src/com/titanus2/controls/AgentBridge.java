package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes control files the boot agent polls.
 * <p>OS control plane: {@code /data/misc/titan2} (init {@code titan2-ctrl.rc}).
 * Not user storage. Apps write private dirs + OS plane when allowed.
 * pad-agent merges by mtime. Never touch {@code /sdcard}.
 */
public final class AgentBridge {
    /** Canonical OS control directory (not MediaStore / Files root). */
    public static final String OS_CTRL = "/data/misc/titan2";
    /**
     * 13.41: after SELinux denies open/write on {@link #OS_CTRL}, skip that path
     * for a while so a11y_live / km heartbeats do not flood avc: denied (rootless
     * lab_rootless + priv_app cannot write system_data_file plane).
     * Parity with HID {@code osCtrlReadBackoffUntilMs}.
     */
    private static volatile long osCtrlBackoffUntilMs;
    private static final long OS_CTRL_BACKOFF_MS = 60_000L;

    public static final String PAD_MODE = "titan2_pad_mode";       // off | trackpad | mouse
    public static final String PAD_CLICK = "titan2_pad_click";     // 0|1 tap-to-click
    /**
     * 1 = reserve top key-row strip (Shift/Sym/Back/Recents/Fn/Alt) for cursor
     * L/R via touchpadd; lower surface is the main pad. 0 = full pad surface.
     * Product default 1 (OEM-like). Applies in mouse mode + HID temporary pad.
     */
    public static final String PAD_TOP_ROW_CURSOR = "titan2_pad_top_row_cursor"; // 0|1
    /**
     * 1 = standalone top-row cursor only (no lower pad pointer). Only when
     * pad_mode=off; mouse/trackpad force this off to avoid dual ownership.
     */
    public static final String PAD_TOP_ROW_ONLY = "titan2_pad_top_row_only"; // 0|1
    /** 1 = pad axes follow display rotation (shared with USB HID / hid_bridge). */
    public static final String PAD_FOLLOW_ORIENT = "titan2_pad_follow_orient"; // 0|1
    /** Surface rotation 0..3 written by apps; agent may refresh. */
    public static final String PAD_ROTATION = "titan2_pad_rotation";
    /** Monotonic pad mode generation — HID/hid_bridge re-grab on change. */
    public static final String PAD_EPOCH = "titan2_pad_epoch";
    /** One-shot re-grab request for hid_bridge (non-zero = force rediscover). */
    public static final String PAD_REGRAB = "titan2_pad_regrab";
    public static final String LED_LEVEL = "titan2_keyled_brightness";
    public static final String LED_TIMEOUT = "titan2_keyled_timeout";
    public static final String LEGACY_PAD = "titan2_touchpad_enabled";
    public static final String FN_MODE = "titan2_fn_mode";         // stock | ctrl
    /**
     * Named specials owner: alt | sym | fn | custom.
     * Used when {@link #CHAR_MOD_SCAN} is empty. Product default: sym.
     */
    public static final String CHAR_MOD = "titan2_char_mod";       // alt | sym | fn | custom
    /**
     * Linux scan code of the physical specials modifier (any key).
     * Empty/0 → derive from {@link #CHAR_MOD}. When set, that key is ALT_RIGHT
     * (KCM specials); former Alt is freed. Agent + a11y both honor this.
     */
    public static final String CHAR_MOD_SCAN = "titan2_char_mod_scan";
    /**
     * How Sym/Alt specials produce glyphs (12.77 / 13.86):
     * {@code kcm} = pass ALT_RIGHT to TitanKey.kcm (product default);
     * {@code inject} = a11y intercepts specials key + letter → KeyActions
     * (clipboard path; opt-in / per-app).
     */
    public static final String SPECIALS_METHOD = "titan2_specials_method"; // inject | kcm
    public static final String NOTIF_BLINK_ENABLE = "titan2_notif_blink_enable"; // 0|1
    public static final String NOTIF_BLINK = "titan2_notif_blink";               // 0|1 active alerts
    public static final String NOTIF_BLINK_LEVEL = "titan2_notif_blink_level";   // 1-7 peak brightness
    public static final String NOTIF_MODE = "titan2_notif_mode";                 // solid|blink|breathe
    public static final String NOTIF_PERIOD_MS = "titan2_notif_period_ms";       // cycle ms
    public static final String NOTIF_ON_MS = "titan2_notif_on_ms";               // on-time ms (0=solid)
    public static final String NOTIF_PREVIEW_UNTIL = "titan2_notif_preview_until"; // epoch sec
    /** One-shot: enable_adb | enable_wireless_adb | disable_wireless_adb */
    public static final String DEV_ACTION = "titan2_dev_action";
    /**
     * Firewall cube one-shot for pad-agent 2.214+:
     * {@code enable|disable|apply|reset|deny-uid|allow-uid|deny-bin|allow-bin|deny-svc|allow-svc}.
     * Written after Atlas biometrics (master) or direct for deny rows; agent runs titan2-fw as root.
     */
    public static final String FW_ACTION = "titan2_fw_action";
    /** pad-agent writes: "on 192.168.x.x:5555" | "off" */
    public static final String STATUS_WIRELESS_ADB = "/data/local/tmp/titan2_wireless_adb_status";
    public static final String SUBDISPLAY_ON = "titan2_subdisplay_on"; // 0|1
    public static final String SUBDISPLAY_BRI = "titan2_subdisplay_bri"; // 0.0-1.0 float
    public static final String SUBDISPLAY_ID = "titan2_subdisplay_id"; // display id hint
    /**
     * One-shot apply stamp (epoch ms or any changing token). pad-agent 2.65+
     * clears LAST_SUBDISP and re-applies panel + sub_mode inhibit.
     */
    public static final String SUBDISPLAY_APPLY = "titan2_subdisplay_apply";
    /**
     * Rear usage: {@code face} (clock, digitizer parked) | {@code apps}
     * (independent rear UI, digitizer live) | {@code off}.
     */
    public static final String SUB_MODE = "titan2_sub_mode";
    /** 1 = inhibit rear sub_touch (default face); 0 = live (apps mode). */
    public static final String SUBTOUCH_INHIBIT = "titan2_subtouch_inhibit";
    /** 1 = freeze physical pad / host mouse while typing (QA typing lock). */
    public static final String PAD_CURSOR_PAUSE = "titan2_pad_cursor_pause";
    /**
     * 1 = TrackpadAccessService connected (Key a11y live). pad-agent uses this
     * so screen-on shared side getevent can fall back to KEY_FIRE when a11y is dead.
     */
    public static final String A11Y_LIVE = "titan2_a11y_live";
    /**
     * 1 = keyguard / credential surface. pad-apply parks touchpadd (SoT
     * {@code titan2_input_lock}). Must be written by a11y — CE stays true after
     * first unlock so lockscreen is not “CE down”.
     */
    public static final String INPUT_LOCK = "titan2_input_lock";
    /**
     * Display plane for Cube dual-DPI:
     * {@code tablet} = physical size + cube dens (Settings two-pane SW≥600);
     * {@code phone_launcher} = phone SW&lt;600 so Launcher3 DeviceProfile is phone
     * (larger home clock/cells) while apps other than home use tablet again.
     * Applied by pad-agent ({@code wm size}/{@code wm density}).
     */
    public static final String UI_PLANE = "titan2_ui_plane"; // tablet | phone_launcher

    // --- IMS / telephony / BT (TrebleApp-inspired; applied by pad-agent root) ---
    public static final String IMS_MTK = "titan2_ims_mtk";                 // 0|1 → persist.sys.phh.ims.mtk
    public static final String IMS_FORCE_VOLTE = "titan2_ims_force_volte"; // 0|1 → dbg.volte/vt/wfc ovr
    public static final String IMS_BINDER = "titan2_ims_binder";           // 0|1 → allow_binder_thread_on_incoming_calls
    public static final String IMS_REQUEST_NET = "titan2_ims_request_net"; // 0|1 hint only
    /** One-shot: rebind|create_apn|install|heal|force_lte (pad-agent 2.144+ slot-aware). */
    public static final String IMS_ACTION = "titan2_ims_action";
    public static final String TEL_FORCE_5G = "titan2_tel_force_5g";
    public static final String TEL_DISABLE_VCI = "titan2_tel_disable_vci";
    public static final String TEL_RESTART_RIL = "titan2_tel_restart_ril";
    public static final String TEL_PATCH_SMSC = "titan2_tel_patch_smsc";
    public static final String BT_SYSBTA = "titan2_bt_sysbta";
    public static final String BT_DISABLE_APCF = "titan2_bt_disable_apcf";
    /** TrebleApp Misc SoT. Plane file optional; heal honors only if present. */
    public static final String BT_WA = "titan2_bt_workaround";
    public static final String BT_ESCO = "titan2_bt_esco";                 // 0|8|16|24|32

    public static final String STATUS_AGENT = "/data/local/tmp/titan2_agent_status";
    public static final String STATUS_PAD = "/data/local/tmp/titan2_pad_status";
    public static final String STATUS_LED = "/data/local/tmp/titan2_led_status";
    public static final String STATUS_FN = "/data/local/tmp/titan2_fn_status";
    public static final String STATUS_CHAR = "/data/local/tmp/titan2_char_status";
    public static final String STATUS_IMS = "/data/local/tmp/titan2_ims_status";
    public static final String KEY_ACTIVITY = "/data/local/tmp/titan2_key_activity";

    /**
     * Max age for pad-agent status as live (B1/B2 {@code agent_ok}).
     * Lock-steal uses ~20s; allow slow ticks under load.
     */
    public static final long AGENT_HB_MAX_AGE_MS = 45_000L;

    /**
     * pad-agent liveness for B1/B2 probes (13.80+).
     * <p>SoT is status <strong>mtime</strong> + identity prefix — not the
     * {@code ok} token. Mid-loop {@code log "heal ghost…"} overwrites the
     * per-tick {@code ok i=N} line while the agent is still alive; requiring
     * {@code ok} false-failed B2 ({@code agent_stale}) under cool-park heal.
     */
    public static final class AgentLive {
        public final boolean ok;
        public final long ageSec;
        public final String lineSnippet;

        public AgentLive(boolean ok, long ageSec, String lineSnippet) {
            this.ok = ok;
            this.ageSec = ageSec;
            this.lineSnippet = lineSnippet != null ? lineSnippet : "?";
        }
    }

    /**
     * Read world-readable {@link #STATUS_AGENT}: live when file is fresh and
     * stamped by pad-agent (any mid-loop diagnostic line counts).
     */
    public static AgentLive agentLive() {
        File f = new File(STATUS_AGENT);
        if (!f.isFile()) {
            return new AgentLive(false, 9999, "missing");
        }
        long ageMs = System.currentTimeMillis() - f.lastModified();
        if (ageMs < 0) ageMs = 0;
        long ageSec = ageMs / 1000L;
        String line = "";
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            try {
                String s = br.readLine();
                if (s != null) line = s.trim();
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
            // unreadable → not live (SELinux / race)
        }
        String snip = line.length() > 72 ? line.substring(0, 72) : line;
        snip = snip.replace(' ', '_');
        // Identity: pad-agent stamps "pad-agent VER …" (2.35+ also embeds "live")
        boolean identity = line.startsWith("pad-agent")
            || line.contains(" pad-agent ")
            || line.contains("live ok")
            || line.contains(" live ");
        // Legacy 2.34 "ok i=N" still accepted when identity missed (truncated)
        if (!identity && (line.contains(" ok i=") || line.contains(" ok "))) {
            identity = true;
        }
        boolean fresh = ageMs <= AGENT_HB_MAX_AGE_MS;
        // Empty line with fresh mtime: treat as not live (corrupt/truncated)
        if (line.isEmpty()) identity = false;
        return new AgentLive(identity && fresh, ageSec, snip.isEmpty() ? "?" : snip);
    }

    /**
     * Key a11y liveness for B2 probes (13.81+).
     * <p>SoT is {@link #A11Y_LIVE} plane body {@code 1} + status <strong>mtime</strong>
     * (TrackpadAccessService 4s heartbeat). Enabled-services list alone is a
     * listed-but-dead residual (post-wipe / package replace / heat kill) — B2
     * must not PASS while specials inject is dead.
     */
    public static AgentLive a11yLive() {
        File f = new File("/data/local/tmp", A11Y_LIVE);
        if (!f.isFile() && osCtrlAllowed()) {
            f = new File(OS_CTRL, A11Y_LIVE);
        }
        if (!f.isFile()) {
            return new AgentLive(false, 9999, "missing");
        }
        long ageMs = System.currentTimeMillis() - f.lastModified();
        if (ageMs < 0) ageMs = 0;
        long ageSec = ageMs / 1000L;
        String line = "";
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            try {
                String s = br.readLine();
                if (s != null) line = s.trim();
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
            return new AgentLive(false, ageSec, "unreadable");
        }
        // Body must be live 1 (0 = deliberately dead after destroy / user off)
        boolean on = "1".equals(line) || "true".equalsIgnoreCase(line)
            || "on".equalsIgnoreCase(line);
        boolean fresh = ageMs <= AGENT_HB_MAX_AGE_MS;
        String snip = on ? (fresh ? "1" : "1_stale") : (line.isEmpty() ? "empty" : line);
        if (snip.length() > 24) snip = snip.substring(0, 24);
        return new AgentLive(on && fresh, ageSec, snip);
    }

    private AgentBridge() {}

    /** True when OS plane open/write is worth trying (not in SELinux backoff). */
    public static boolean osCtrlAllowed() {
        return System.currentTimeMillis() >= osCtrlBackoffUntilMs;
    }

    /** Call after SELinux/IO failure on {@link #OS_CTRL} (HostLayout + AgentBridge). */
    public static void noteOsCtrlDenied() {
        osCtrlBackoffUntilMs = System.currentTimeMillis() + OS_CTRL_BACKOFF_MS;
    }

    /**
     * World-mode chmod on shared tmp/OS plane files is often SELinux-denied
     * (shell_data_file / system_data_file) and only floods avc: setattr — skip it.
     */
    private static void maybeWorldMode(File f) {
        if (f == null) return;
        String p = f.getPath();
        if (p == null) return;
        if (p.startsWith(OS_CTRL) || p.startsWith("/data/local/tmp")) return;
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(true, false);
    }

    private static boolean isOsCtrlPath(File f) {
        if (f == null) return false;
        String p = f.getPath();
        return p != null && p.startsWith(OS_CTRL);
    }

    public static List<File> targets(Context ctx, String name) {
        List<File> list = new ArrayList<>();
        // tmp first: world 0666 lab plane; OS plane may be SELinux-denied (13.41)
        list.add(new File("/data/local/tmp", name));
        if (osCtrlAllowed()) {
            list.add(new File(OS_CTRL, name));
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) list.add(new File(ext, name));
        } catch (Exception ignored) {}
        list.add(new File(ctx.getFilesDir(), name));
        return list;
    }

    /** Write name=value to OS plane + app paths. Returns true if any write ok. */
    public static boolean put(Context ctx, String name, String value) {
        if (value == null) value = "";
        // P0 B1: never publish system chrome on physical side plane files.
        // pad-agent KEY_FIRE reads titan2_km_side_* — Home here = system Home.
        if (name != null && (name.startsWith("titan2_km_side_func_")
                || name.startsWith("titan2_km_side_func2_"))) {
            String slot = name.startsWith("titan2_km_") ? name.substring("titan2_km_".length()) : name;
            if (KeyMapPrefs.isSystemChromeAction(value)) {
                value = KeyMapPrefs.factoryDefault(slot);
            }
        }
        // 12.02: never force-rewrite CE when body already matches. Prior forceCe
        // for host_layout/km always openFileOutput'd → mtime thrash every publish
        // → pad-agent InputReader rebind (multi-letter spam / heat).
        // Still write when CE is empty/missing (stale empty titan2_km_screen_off).
        File ceFile = new File(ctx.getFilesDir(), name);
        String ceBody = ceFile.isFile() ? readLine(ceFile.getPath()) : null;
        boolean ceOk = ceBody != null && value.equals(ceBody.trim());
        // Skip bulk rewrites when CE + merged plane already hold value —
        // but still heal tmp/OS if they disagree (12.63: a11y_live tmp stuck
        // at 0 while Global/CE say 1 → pad-agent dual sides before any-1 fix).
        if (ceOk) {
            String cur = get(ctx, name, null);
            if (value.equals(cur)) {
                // 12.73: a11y_live heartbeat (Controls 12.70) must bump mtime even
                // when body stays "1". healPlaneFile skips same-body writes → plane
                // froze → pad-agent 1.63 stale-20s treated bound a11y as dead →
                // KEY_FIRE + a11y dual sides (B1 multi Specials residual).
                if (A11Y_LIVE.equals(name) || SPECIALS_METHOD.equals(name)) {
                    // 13.41: tmp + Global only on heartbeat when OS plane denied
                    // 13.86: specials_method also force-touches so inject↔kcm
                    // first UI tap always dirties pad-agent mtime (no 2nd click).
                    touchPlaneFile(new File("/data/local/tmp", name), value);
                    if (osCtrlAllowed()) {
                        touchPlaneFile(new File(OS_CTRL, name), value);
                    }
                    mirrorGlobal(ctx, name, value);
                    return true;
                }
                healPlaneFile(new File("/data/local/tmp", name), value);
                if (osCtrlAllowed()) {
                    healPlaneFile(new File(OS_CTRL, name), value);
                }
                mirrorGlobal(ctx, name, value);
                return true;
            }
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        int ok = 0;

        // App private CE — skip if body already matches (preserve mtime)
        if (!ceOk) {
            try {
                FileOutputStream fos = ctx.openFileOutput(name, Context.MODE_PRIVATE);
                fos.write(data);
                fos.close();
                ok++;
            } catch (Exception ignored) {}
        } else {
            ok++;
        }

        // Prefer tmp + CE + external; OS plane only when not in SELinux backoff (13.41).
        // skip legacy /sdcard/titan2 for writes.
        List<File> writeTargets = new ArrayList<>(4);
        writeTargets.add(new File("/data/local/tmp", name));
        if (osCtrlAllowed()) {
            writeTargets.add(new File(OS_CTRL, name));
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) writeTargets.add(new File(ext, name));
        } catch (Exception ignored) {}
        writeTargets.add(new File(ctx.getFilesDir(), name));

        for (File f : writeTargets) {
            if (f == null) continue;
            try {
                // Skip individual plane if content already matches
                if (f.isFile()) {
                    String existing = readLine(f.getPath());
                    if (existing != null && value.equals(existing.trim())) {
                        ok++;
                        continue;
                    }
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    parent.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    parent.setWritable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    parent.setExecutable(true, false);
                }
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                maybeWorldMode(f);
                ok++;
            } catch (Exception e) {
                if (isOsCtrlPath(f)) noteOsCtrlDenied();
            }
        }
        // Settings.Global mirror for unrooted lab/bench + HostLayout read path.
        // KeyMapPrefs.publishToAgent only hit files before — km plane was null in
        // `settings get global titan2_km_*` while /data/local/tmp still held values.
        if (mirrorGlobal(ctx, name, value)) ok++;
        return ok > 0;
    }

    /**
     * Mirror control plane into Settings.Global when name is a plane key.
     * No-op if Global already matches (avoids content-observer thrash).
     *
     * @return true if a write was attempted successfully
     */
    public static boolean mirrorGlobal(Context ctx, String name, String value) {
        if (ctx == null || !isGlobalPlaneName(name)) return false;
        if (value == null) value = "";
        try {
            String cur = Settings.Global.getString(ctx.getContentResolver(), name);
            if (value.equals(cur)) return true;
            Settings.Global.putString(ctx.getContentResolver(), name, value);
            return true;
        } catch (Exception ignored) {
            try {
                Settings.System.putString(ctx.getContentResolver(), name, value);
                return true;
            } catch (Exception ignored2) {
                return false;
            }
        }
    }

    /**
     * Plane names mirrored to Settings.Global (parity with
     * {@link HostLayoutController} writePlane). Needs WRITE_SECURE_SETTINGS.
     */
    public static boolean isGlobalPlaneName(String name) {
        if (name == null) return false;
        switch (name) {
            case "titan2_usb_hid_session":
            case "titan2_usb_hid_keys":
            case "titan2_usb_hid_grab":
            case "titan2_usb_hid_mouse":
            case "titan2_usb_hid_local_input":
            case "titan2_host_layout":
            case "titan2_host_layout_keys_pause":
            case "titan2_usb_hid_keys_pause":
            case PAD_MODE:
            case PAD_CURSOR_PAUSE:
            case A11Y_LIVE:
            case UI_PLANE:
            case DEV_ACTION:
                // 12.54: one-shot reload_agent / wireless ADB must hit Global so
                // pad-agent read_first is not blocked by empty T2 clear shells.
                return true;
            case SUBDISPLAY_ON:
            case SUBDISPLAY_BRI:
            case SUBDISPLAY_ID:
            case SUBDISPLAY_APPLY:
            case SUB_MODE:
            case "titan2_dt2w":
                // 13.85: rear panel plane hits Global so pad-agent 2.46
                // _read_subdisplay_on Global fallback + rootless SELinux deny
                // on T2 still see Off/On (not file-only residual).
                // 15.3: apply stamp + sub_mode (face|apps|off) for agent 2.65.
                // 15.29: optional main DT2W plane for pad-agent apply_dt2w.
                return true;
            case SPECIALS_METHOD:
            case CHAR_MOD:
            case FN_MODE:
                // 13.23: method/layout plane must hit Global so pad-agent + UI
                // agree after inject↔kcm (KCM Sym silent when plane stuck inject).
                return true;
            default:
                return name.startsWith("titan2_km_") || name.startsWith("titan2_host_");
        }
    }

    /**
     * Read control value: newest mtime wins across OS plane + app paths
     * (same merge rule as pad-agent / USB HID PadModeClient).
     * Explicit clears: {@code 0}, {@code null}, {@code -}, {@code clear}.
     * Note: {@code off} is a <b>valid</b> value for {@link #PAD_MODE} (and similar
     * toggles) — it must not be treated as a clear token or the UI shows Off while
     * HID/agent still run mouse/trackpad.
     * Note: {@code none} is a <b>valid</b> action (side short, BT WA) — not a clear.
     */
    public static String get(Context ctx, String name, String def) {
        long bestMt = -1;
        String best = null;
        for (File f : targets(ctx, name)) {
            if (f == null || !f.isFile()) continue;
            String v = readLine(f.getPath());
            if (v == null) continue;
            v = v.trim();
            if (v.isEmpty() || isClearToken(name, v)) {
                // Newer clear must beat older stale values (e.g. char_mod_scan=251)
                if (f.lastModified() >= bestMt) {
                    bestMt = f.lastModified();
                    best = null;
                }
                continue;
            }
            long mt = f.lastModified();
            if (mt >= bestMt) {
                bestMt = mt;
                best = v;
            }
        }
        // Global fallback when files missing/stale (lab SELinux deny on tmp)
        if (best == null && ctx != null && isGlobalPlaneName(name)) {
            try {
                String g = Settings.Global.getString(ctx.getContentResolver(), name);
                if (g != null) {
                    g = g.trim();
                    if (!g.isEmpty() && !isClearToken(name, g)) return g;
                }
            } catch (Exception ignored) {}
        }
        return best != null ? best : def;
    }

    /**
     * True for explicit clear tokens written to control files.
     * Do not include {@code off} — pad mode uses off|trackpad|mouse as real states.
     * Do not include {@code none} — side short / BT WA use none as a real action.
     * Do not treat {@code 0} as clear for {@link #A11Y_LIVE} (0 = dead is valid).
     */
    public static boolean isClearToken(String v) {
        return isClearToken(null, v);
    }

    public static boolean isClearToken(String name, String v) {
        if (v == null) return true;
        String t = v.trim().toLowerCase();
        if (t.isEmpty() || "null".equals(t) || "-".equals(t) || "clear".equals(t)) {
            return true;
        }
        // 12.63: a11y_live 0/1 are both real states (not clear tokens)
        if (A11Y_LIVE.equals(name)) return false;
        // 13.85: binary 0|1 planes — "0" is real Off/false (not clear).
        // Without this, put("0") always rewrites (get returns null → thrash)
        // and dual-plane heal (pad-agent 2.46) can disagree with Controls Off
        // after ST/T2 mtime fight residual.
        if (isBinaryZeroOnePlane(name)) return false;
        return "0".equals(t);
    }

    /**
     * Plane keys whose body is a real {@code 0}|{@code 1} toggle.
     * {@code "0"} must never be a clear token (see {@link #isClearToken}).
     */
    public static boolean isBinaryZeroOnePlane(String name) {
        if (name == null) return false;
        switch (name) {
            case SUBDISPLAY_ON:
            case PAD_CLICK:
            case PAD_FOLLOW_ORIENT:
            case PAD_CURSOR_PAUSE:
            case SUBTOUCH_INHIBIT:
            case NOTIF_BLINK_ENABLE:
            case NOTIF_BLINK:
            case LEGACY_PAD:
            case IMS_MTK:
            case IMS_FORCE_VOLTE:
            case IMS_BINDER:
            case IMS_REQUEST_NET:
            case TEL_FORCE_5G:
            case TEL_DISABLE_VCI:
            case TEL_RESTART_RIL:
            case TEL_PATCH_SMSC:
            case BT_SYSBTA:
            case BT_DISABLE_APCF:
            case "titan2_usb_hid_session":
            case "titan2_usb_hid_keys":
            case "titan2_usb_hid_grab":
            case "titan2_usb_hid_mouse":
            case "titan2_usb_hid_local_input":
            case "titan2_usb_hid_keys_pause":
            case "titan2_host_layout_keys_pause":
            case "titan2_keycode_inject_pause":
                return true;
            default:
                return false;
        }
    }

    /** Write value to one plane file only if missing or body differs. */
    private static void healPlaneFile(File f, String value) {
        if (f == null || value == null) return;
        if (isOsCtrlPath(f) && !osCtrlAllowed()) return;
        try {
            if (f.isFile()) {
                String existing = readLine(f.getPath());
                if (existing != null && value.equals(existing.trim())) return;
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(value.getBytes(StandardCharsets.UTF_8));
            }
            maybeWorldMode(f);
        } catch (Exception e) {
            if (isOsCtrlPath(f)) noteOsCtrlDenied();
        }
    }

    /** Always rewrite plane file so mtime advances (a11y_live heartbeat). */
    private static void touchPlaneFile(File f, String value) {
        if (f == null || value == null) return;
        if (isOsCtrlPath(f) && !osCtrlAllowed()) return;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(value.getBytes(StandardCharsets.UTF_8));
            }
            maybeWorldMode(f);
        } catch (Exception e) {
            if (isOsCtrlPath(f)) noteOsCtrlDenied();
        }
    }

    /**
     * Newest mtime across control-plane paths for {@code name}, or 0 if none.
     * Used so named CHAR_MOD can beat a stale CHAR_MOD_SCAN.
     */
    public static long newestMtime(Context ctx, String name) {
        long best = 0;
        for (File f : targets(ctx, name)) {
            if (f == null || !f.isFile()) continue;
            long mt = f.lastModified();
            if (mt > best) best = mt;
        }
        return best;
    }

    /** Delete control file from all write targets (one-shot signals / clear scan). */
    public static void clear(Context ctx, String name) {
        try {
            ctx.deleteFile(name);
        } catch (Exception ignored) {}
        // Write clear token so mtime wins over stale OS-plane values
        put(ctx, name, "0");
        // put already wrote planes; only clean empty legacy external leftovers
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(ext, name);
                // keep "0" file from put; also try delete of empty legacy
                if (f.exists() && f.length() == 0) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        } catch (Exception ignored) {}
        // do not delete after put — "0" must remain as newest clear
    }

    public static String readStatus(String path) {
        return readLine(path);
    }

    static String readLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            br.close();
            return line;
        } catch (IOException e) {
            return null;
        }
    }

    /** Last activity write (ms) — throttle mid-word File I/O. */
    private static volatile long lastKeyActivityMs;

    /**
     * Bump key activity so pad-agent keeps keyboard backlight for idle timeout.
     * 14.01: throttle 200ms (was 1s) so mid-word typing never drops the light
     * before the next EV_KEY bump from pad-agent getevent.
     */
    public static void bumpKeyActivity(Context ctx) {
        long nowMs = System.currentTimeMillis();
        // 50ms throttle keeps LED + typing-lock edges responsive mid-word.
        if (lastKeyActivityMs > 0 && (nowMs - lastKeyActivityMs) < 50L) return;
        lastKeyActivityMs = nowMs;
        // Unix **seconds** — pad-agent LED idle is `now - last_act` in seconds.
        // Ms stamps made age look huge → idle_timeout, keyboard light off (2.157).
        // Typing-watch also edges on file mtime/content; seconds are enough.
        byte[] data = String.valueOf(nowMs / 1000L).getBytes(StandardCharsets.UTF_8);
        // 13.41: tmp always; OS plane only outside SELinux backoff
        File[] paths = osCtrlAllowed()
            ? new File[]{ new File(KEY_ACTIVITY), new File(OS_CTRL, "titan2_key_activity") }
            : new File[]{ new File(KEY_ACTIVITY) };
        for (File f : paths) {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(data);
                fos.close();
                maybeWorldMode(f);
            } catch (Exception e) {
                if (isOsCtrlPath(f)) noteOsCtrlDenied();
            }
        }
    }
}
