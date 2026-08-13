package com.titanus2.controls.subdisplay;

import com.titanus2.controls.TrackpadAccessService;
import android.provider.Settings;
import android.text.TextUtils;
import android.content.ComponentName;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Rear dense night face as a WindowManager overlay on display 2.
 * <p>
 * Must sit <b>above</b> SystemUI {@code KEYGUARD_DIALOG} on the secondary display
 * (that was the second clock). Activities sit below keyguard → dual clocks.
 * Palette: pure black + product cyan mono facts (Cube).
 */
public final class SubDisplayFaceOverlay {
    private static final String TAG = "SubDisplayFaceOverlay";
    private static final int STOCK_CYAN = SubDisplayPrefs.NIGHT_CYAN;
    private static final int STOCK_MUTED = 0xFF008899;

    private static SubDisplayFaceOverlay s;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private TextView clock, topLine, midLine, botLine;
    private View rule;
    private LinearLayout col;
    private LinearLayout notifRow;
    private int panelW = SubDisplayHelper.REAR_W;
    private int panelH = SubDisplayHelper.REAR_H;
    private boolean tickOn;
    private Context viewCtx;
    private boolean usedA11y;
    private boolean attaching;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            paint();
            if (tickOn) {
                h.postDelayed(this, SubDisplayPrefs.widgetSeconds(app) ? 500 : 1000);
            }
        }
    };

    private SubDisplayFaceOverlay(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        // Cube is sacred — never draw Face/clock chrome over the lattice.
        // Also refuse when plane tokens say cube (prefs can race on boot).
        if (SubDisplayPrefs.cubeOwnsRear(app)) {
            hide(app);
            Log.i(TAG, "refuse face: cube owns rear");
            return;
        }
        SubDisplayPrefs.Mode m = SubDisplayPrefs.getMode(app);
        if (m != SubDisplayPrefs.Mode.CUSTOM && m != SubDisplayPrefs.Mode.STOCK) {
            hide(app);
            return;
        }
        SubDisplayFaceActivity.dismiss(app);
        boolean a11yUp = TrackpadAccessService.get() != null;
        if (s != null && s.attaching) return;
        if (s != null && s.root != null) {
            if (s.usedA11y || !a11yUp) {
                s.paint();
                s.startTick();
                return;
            }
            s.detachSync();
            s = null;
        } else if (s != null) {
            if (s.attaching) return;
            s.detachSync();
            s = null;
        }
        s = new SubDisplayFaceOverlay(app);
        s.attach();
    }

    public static synchronized void hide(Context c) {
        if (s != null) {
            s.detach();
            s = null;
        }
        SubDisplayFaceActivity.dismiss(c);
    }

    public static synchronized void repaint(Context c) {
        if (c != null && SubDisplayPrefs.cubeOwnsRear(c.getApplicationContext())) {
            hide(c);
            return;
        }
        if (s != null && s.root != null) s.paint();
        else show(c);
    }

    private void attach() {
        attaching = true;
        h.post(() -> {
            try {
                if (root != null) {
                    paint();
                    startTick();
                    return;
                }
                Display rear = SubDisplayHelper.findRear(app);
                if (rear == null) {
                    Log.w(TAG, "no rear display");
                    return;
                }
                measure(rear);

                // 1) Accessibility overlay sits ABOVE KEYGUARD_DIALOG (~191k).
                //    APPLICATION_OVERLAY sits BELOW it (~111k) — that caused dual clocks.
                boolean added = tryAccessibilityOverlay(rear);
                if (!added) {
                    ensureAccessibilityEnabled();
                    // Retry once after enabling (service may not be up yet)
                    TrackpadAccessService svc = TrackpadAccessService.get();
                    if (svc != null) added = tryAccessibilityOverlay(rear);
                }
                if (!added) {
                    added = tryAppOverlay(rear);
                }
                if (!added) {
                    throw new IllegalStateException("no overlay type worked");
                }
                startTick();
                // If we fell back to app overlay, retry a11y once service binds.
                if (!usedA11y) {
                    h.postDelayed(() -> {
                        if (s == this && root != null && !usedA11y
                            && TrackpadAccessService.get() != null) {
                            Log.i(TAG, "retrying a11y overlay upgrade");
                            show(app);
                        }
                    }, 800);
                    h.postDelayed(() -> {
                        if (s == this && root != null && !usedA11y
                            && TrackpadAccessService.get() != null) {
                            show(app);
                        }
                    }, 2500);
                }
            } catch (Exception e) {
                Log.e(TAG, "attach: " + e.getMessage(), e);
                root = null;
                wm = null;
                // no FaceActivity fallback — stacks with overlay
            } finally {
                attaching = false;
            }
        });
    }

    /**
     * TYPE_ACCESSIBILITY_OVERLAY (2032) — only valid from an AccessibilityService.
     * Layer is above SystemUI secondary keyguard; kills dual-clock on DT2W.
     */
    private boolean tryAccessibilityOverlay(Display rear) {
        TrackpadAccessService svc = TrackpadAccessService.get();
        if (svc == null) {
            Log.w(TAG, "Accessibility off — cannot use TYPE_ACCESSIBILITY_OVERLAY");
            return false;
        }
        try {
            Context dctx = svc.createDisplayContext(rear);
            int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            if (Build.VERSION.SDK_INT >= 30) {
                dctx = dctx.createWindowContext(type, null);
            }
            WindowManager w = dctx.getSystemService(WindowManager.class);
            if (w == null) return false;
            if (root == null) root = buildView(dctx);
            WindowManager.LayoutParams lp = buildLp(type);
            lp.alpha = 1f;
            w.addView(root, lp);
            root.setAlpha(1f);
            w.updateViewLayout(root, lp);
            wm = w;
            usedA11y = true;
            Log.i(TAG, "ACCESSIBILITY_OVERLAY on display " + rear.getDisplayId());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "a11y overlay failed: " + e.getMessage());
            try {
                if (wm != null && root != null) wm.removeView(root);
            } catch (Exception ignored) {}
            root = null;
            wm = null;
            return false;
        }
    }

    private boolean tryAppOverlay(Display rear) {
        try {
            Context dctx = app.createDisplayContext(rear);
            int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            if (Build.VERSION.SDK_INT >= 30) {
                dctx = dctx.createWindowContext(type, null);
            }
            WindowManager w = dctx.getSystemService(WindowManager.class);
            if (w == null) return false;
            if (root == null) root = buildView(dctx);
            WindowManager.LayoutParams lp = buildLp(type);
            lp.alpha = 1f;
            w.addView(root, lp);
            root.setAlpha(1f);
            // Still under keyguard — better than nothing
            usedA11y = false;
            Log.w(TAG, "APPLICATION_OVERLAY (under keyguard risk) display "
                + rear.getDisplayId());
            wm = w;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "app overlay failed: " + e.getMessage());
            root = null;
            wm = null;
            return false;
        }
    }

    /** Best-effort: enable TrackpadAccessService so we can cover OS rear keyguard. */
    private void ensureAccessibilityEnabled() {
        try {
            ComponentName me = new ComponentName(app, TrackpadAccessService.class);
            String flat = me.flattenToString();
            String enabled = Settings.Secure.getString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null && enabled.contains(flat)) {
                Settings.Secure.putInt(app.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                return;
            }
            String next = (enabled == null || enabled.isEmpty()) ? flat : (enabled + ":" + flat);
            Settings.Secure.putString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(app.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "enabled accessibility for rear face overlay");
        } catch (Exception e) {
            Log.w(TAG, "cannot enable accessibility: " + e.getMessage());
        }
    }

    private void detach() {
        tickOn = false;
        h.removeCallbacks(tick);
        h.post(this::detachNow);
    }

    private void detachSync() {
        tickOn = false;
        h.removeCallbacks(tick);
        detachNow();
    }

    private void detachNow() {
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Exception ignored) {}
        root = null;
        wm = null;
        usedA11y = false;
    }

    private WindowManager.LayoutParams buildLp(int type) {
        // Do NOT set TURN_SCREEN_ON / KEEP_SCREEN_ON — those wake/keep the *main* panel.
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.setTitle("TitanRearFace");
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.alpha = 1f;
        // Private flags: prevent keyguard from forcing dim on some builds
        try {
            java.lang.reflect.Field f =
                WindowManager.LayoutParams.class.getField("privateFlags");
            int pf = f.getInt(lp);
            // PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY not needed;
            // try SYSTEM_ERROR path for show-for-all-users when system
            f.setInt(lp, pf);
        } catch (Exception ignored) {}
        return lp;
    }

    private void measure(Display rear) {
        DisplayMetrics m = new DisplayMetrics();
        try {
            rear.getRealMetrics(m);
            if (m.widthPixels > 0) panelW = m.widthPixels;
            if (m.heightPixels > 0) panelH = m.heightPixels;
        } catch (Exception ignored) {}
    }

    private View buildView(Context dctx) {
        FrameLayout frame = new FrameLayout(dctx);
        frame.setBackgroundColor(0xFF000000);
        frame.setLayoutParams(new FrameLayout.LayoutParams(panelW, panelH));

        col = new LinearLayout(dctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.max(8, panelW / 28);
        col.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        flp.gravity = Gravity.CENTER;
        frame.addView(col, flp);

        viewCtx = dctx;
        topLine = mk(dctx);
        midLine = mk(dctx);
        botLine = mk(dctx);
        clock = mk(dctx);
        rule = new View(dctx);
        notifRow = new LinearLayout(dctx);
        notifRow.setOrientation(LinearLayout.HORIZONTAL);
        notifRow.setGravity(Gravity.CENTER);
        notifRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rebuildLayout();
        return frame;
    }

    private TextView mk(Context c) {
        TextView t = new TextView(c);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        t.setSingleLine(true);
        t.setMaxLines(1);
        t.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void rebuildLayout() {
        col.removeAllViews();
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(app);
        SubDisplayPrefs.FaceStyle style = mode == SubDisplayPrefs.Mode.STOCK
            ? SubDisplayPrefs.FaceStyle.STATUS
            : SubDisplayPrefs.getFaceStyle(app);
        int gap = Math.max(2, panelH / 100);
        if (style == SubDisplayPrefs.FaceStyle.MINIMAL) {
            col.setGravity(Gravity.CENTER);
            col.addView(clock);
            addNotifIfCustom(gap);
            return;
        }
        if (style == SubDisplayPrefs.FaceStyle.STATUS) {
            // Dense night: meta strip + hairline + time (+ notifs) — no brand footer
            col.setGravity(Gravity.CENTER);
            col.addView(topLine);
            addGap(gap);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Math.max(panelW / 3, 40), Math.max(1, panelH / 250));
            rlp.gravity = Gravity.CENTER_HORIZONTAL;
            rule.setLayoutParams(rlp);
            col.addView(rule);
            addGap(gap * 2);
            col.addView(clock);
            addGap(gap);
            col.addView(midLine);
            addNotifIfCustom(gap);
            return;
        }
        col.setGravity(Gravity.CENTER);
        col.addView(topLine);
        addGap(gap);
        col.addView(clock);
        addGap(gap);
        col.addView(midLine);
        addNotifIfCustom(gap);
    }

    private void addNotifIfCustom(int gap) {
        if (SubDisplayPrefs.getMode(app) != SubDisplayPrefs.Mode.CUSTOM) return;
        if (!SubDisplayPrefs.widgetNotifs(app)) return;
        if (notifRow == null) return;
        addGap(gap);
        col.addView(notifRow);
    }

    private void addGap(int px) {
        View v = new View(app);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        col.addView(v);
    }

    void startTick() {
        tickOn = true;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void paint() {
        if (root == null || clock == null) return;
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(app);
        if (mode != SubDisplayPrefs.Mode.CUSTOM && mode != SubDisplayPrefs.Mode.STOCK) {
            hide(app);
            return;
        }
        // Prefer prefs ink (defaults to product night cyan); stock path same palette
        int ink = SubDisplayPrefs.inkColor(app);
        int muted = SubDisplayPrefs.mutedColor(app);
        int dim = SubDisplayPrefs.dimColor(app);
        if ((ink & 0x00FFFFFF) == 0) {
            ink = STOCK_CYAN;
            muted = STOCK_MUTED;
            dim = 0xFF004455;
        }
        if (root != null) {
            root.setBackgroundColor(0xFF000000);
            root.setAlpha(1f);
        }

        Date now = new Date();
        boolean sec = SubDisplayPrefs.widgetSeconds(app);
        boolean h24 = SubDisplayPrefs.hour24(app);
        String tPat = h24 ? (sec ? "HH:mm:ss" : "HH:mm") : (sec ? "h:mm:ss" : "h:mm");
        String time = new SimpleDateFormat(tPat, Locale.getDefault()).format(now);
        if (!h24) {
            time = time + " " + new SimpleDateFormat("a", Locale.getDefault()).format(now);
        }

        int scale = SubDisplayPrefs.getClockScale(app);
        float[] hFrac = {0.10f, 0.15f, 0.22f, 0.30f, 0.40f};
        float[] wFrac = {0.72f, 0.82f, 0.90f, 0.96f, 0.99f};
        float maxH = panelH * hFrac[scale];
        float maxW = panelW * wFrac[scale];
        float sizePx = fit(time, maxW, maxH, true);
        float minPx = panelH * 0.08f;
        float maxPx = panelH * 0.45f;
        if (sizePx < minPx) sizePx = minPx;
        if (sizePx > maxPx) sizePx = maxPx;

        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clock.setText(time);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        clock.setLetterSpacing(sec ? 0f : 0.06f);
        clock.setIncludeFontPadding(false);
        clock.setSingleLine(true);
        clock.setMaxLines(1);
        clock.requestLayout();
        clock.invalidate();

        String week = new SimpleDateFormat("EEE", Locale.getDefault()).format(now).toUpperCase(Locale.US);
        String date = new SimpleDateFormat("dd MMM", Locale.getDefault()).format(now).toUpperCase(Locale.US);
        int bat = battery();
        String batS = bat >= 0 ? bat + "%" : "—";
        boolean showWeek = SubDisplayPrefs.widgetWeekday(app);
        boolean showDate = SubDisplayPrefs.widgetDate(app);
        boolean showBat = SubDisplayPrefs.widgetBattery(app);
        float metaH = panelH * 0.035f;

        SubDisplayPrefs.FaceStyle style = mode == SubDisplayPrefs.Mode.STOCK
            ? SubDisplayPrefs.FaceStyle.STATUS
            : SubDisplayPrefs.getFaceStyle(app);

        if (style == SubDisplayPrefs.FaceStyle.MINIMAL) {
            setLine(topLine, "", muted, metaH);
            setLine(midLine, "", muted, metaH);
            if (botLine != null) botLine.setVisibility(View.GONE);
            if (rule != null) rule.setVisibility(View.GONE);
            paintNotifs(ink, muted);
            return;
        }
        if (style == SubDisplayPrefs.FaceStyle.STATUS) {
            StringBuilder top = new StringBuilder();
            if (showWeek) top.append(week);
            if (showDate) {
                if (top.length() > 0) top.append(" · ");
                top.append(date);
            }
            if (showBat) {
                if (top.length() > 0) top.append(" · ");
                top.append(batS);
            }
            setLine(topLine, top.toString(), muted, metaH);
            if (rule != null) {
                rule.setBackgroundColor(dim);
                rule.setVisibility(View.VISIBLE);
            }
            setLine(midLine, "", muted, metaH);
            if (botLine != null) botLine.setVisibility(View.GONE);
            paintNotifs(ink, muted);
            return;
        }
        setLine(topLine, showWeek ? week : "", muted, metaH);
        String mid = "";
        if (showDate && showBat) mid = date + "  " + batS;
        else if (showDate) mid = date;
        else if (showBat) mid = batS;
        setLine(midLine, mid, muted, metaH);
        if (botLine != null) botLine.setVisibility(View.GONE);
        if (rule != null) rule.setVisibility(View.GONE);
        paintNotifs(ink, muted);
    }

    /** Up to 3 app icons + missed counts (Custom mode only). */
    private void paintNotifs(int ink, int muted) {
        if (notifRow == null) return;
        if (SubDisplayPrefs.getMode(app) != SubDisplayPrefs.Mode.CUSTOM
            || !SubDisplayPrefs.widgetNotifs(app)) {
            notifRow.setVisibility(View.GONE);
            return;
        }
        Context c = viewCtx != null ? viewCtx : app;
        notifRow.removeAllViews();
        List<SubDisplayNotifs.Entry> list = SubDisplayNotifs.get();
        if (list.isEmpty()) {
            notifRow.setVisibility(View.GONE);
            return;
        }
        notifRow.setVisibility(View.VISIBLE);
        int max = SubDisplayPrefs.notifMaxApps(app);
        int iconPx = SubDisplayPrefs.notifIconPx(app, panelW);
        int gap = Math.max(4, iconPx / 4);
        for (int i = 0; i < list.size() && i < max; i++) {
            SubDisplayNotifs.Entry e = list.get(i);
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(gap, 0, gap, 0);
            cell.setLayoutParams(clp);

            ImageView iv = new ImageView(c);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
            iv.setLayoutParams(ilp);
            Drawable d = e.icon;
            if (d != null) {
                iv.setImageDrawable(d.mutate());
            } else {
                iv.setBackgroundColor(muted);
            }
            cell.addView(iv);

            TextView badge = new TextView(c);
            badge.setText(e.count > 99 ? "99+" : String.valueOf(e.count));
            badge.setTextColor(ink);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(9, iconPx * 0.38f));
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setIncludeFontPadding(false);
            badge.setLetterSpacing(0.02f);
            cell.addView(badge);

            notifRow.addView(cell);
        }
    }


    private void setLine(TextView tv, String text, int color, float wantPx) {
        if (tv == null) return;
        if (text == null || text.isEmpty()) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
            Math.min(fit(text, panelW * 0.92f, wantPx, false), wantPx * 1.15f));
    }

    private float fit(String text, float maxW, float maxH, boolean monoClock) {
        if (text == null || text.isEmpty()) return 12f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(monoClock
            ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            : Typeface.MONOSPACE);
        float lo = 10f, hi = Math.max(14f, maxH);
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) / 2f;
            paint.setTextSize(mid);
            if (paint.measureText(text) <= maxW) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private int battery() {
        try {
            Intent bat = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return -1;
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(100f * level / scale);
        } catch (Exception e) {
            return -1;
        }
    }
}
