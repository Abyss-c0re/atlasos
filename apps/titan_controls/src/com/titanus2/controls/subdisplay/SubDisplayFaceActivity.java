package com.titanus2.controls.subdisplay;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Rear-only dense night face for 410×502 (black + product cyan mono facts).
 * Never uses SystemUI ambient — that clock is fixed-size and duplicates on main.
 */
public class SubDisplayFaceActivity extends Activity {
    private static final String TAG = "SubDisplayFace";
    private static final String ACTION_DISMISS = "com.titanus2.controls.subdisplay.FACE_DISMISS";
    private static final String ACTION_REPAINT = "com.titanus2.controls.subdisplay.FACE_REPAINT";
    /** Product night cyan ({@link SubDisplayPrefs#NIGHT_CYAN}). */
    private static final int STOCK_CYAN = SubDisplayPrefs.NIGHT_CYAN;
    private static final int STOCK_MUTED = 0xFF008899;

    private static volatile SubDisplayFaceActivity sInstance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView topLine;
    private TextView midLine;
    private TextView botLine;
    private View rule;
    private LinearLayout col;
    private boolean tickOn;
    private int panelW = SubDisplayHelper.REAR_W;
    private int panelH = SubDisplayHelper.REAR_H;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            paint();
            if (tickOn) {
                boolean sec = SubDisplayPrefs.widgetSeconds(SubDisplayFaceActivity.this);
                h.postDelayed(this, sec ? 500 : 1000);
            }
        }
    };

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (ACTION_DISMISS.equals(intent.getAction())) finishAndRemoveTaskSafe();
            else if (ACTION_REPAINT.equals(intent.getAction())) {
                rebuildLayout();
                paint();
            }
        }
    };

    private static boolean faceMode(Context c) {
        if (c != null && SubDisplayPrefs.cubeOwnsRear(c)) return false;
        return SubDisplayPrefs.getMode(c) == SubDisplayPrefs.Mode.CUSTOM;
    }

    public static void show(Context c) {
        Context app = c.getApplicationContext();
        if (!faceMode(app)) {
            dismiss(app);
            return;
        }
        Display rear = SubDisplayHelper.findRear(app);
        if (rear == null) {
            Log.w(TAG, "no rear display");
            return;
        }
        if (sInstance != null) {
            try {
                app.sendBroadcast(new Intent(ACTION_REPAINT).setPackage(app.getPackageName()));
            } catch (Exception ignored) {}
            return;
        }
        try {
            Intent i = new Intent(app, SubDisplayFaceActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(rear.getDisplayId());
                app.startActivity(i, opts.toBundle());
                Log.i(TAG, "launch rear " + rear.getDisplayId()
                    + " mode=" + SubDisplayPrefs.getMode(app));
            }
        } catch (Exception e) {
            Log.w(TAG, "show: " + e.getMessage());
        }
    }

    public static void dismiss(Context c) {
        SubDisplayFaceActivity live = sInstance;
        if (live != null) {
            try { live.finishAndRemoveTaskSafe(); } catch (Exception ignored) {}
        }
        try {
            c.getApplicationContext().sendBroadcast(
                new Intent(ACTION_DISMISS).setPackage(c.getPackageName()));
        } catch (Exception ignored) {}
    }

    private void finishAndRemoveTaskSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
            else finish();
        } catch (Exception e) {
            try { finish(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!faceMode(this)) {
            finishAndRemoveTaskSafe();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Display d = getDisplay();
                if (d != null && d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    Log.w(TAG, "refusing main display");
                    finishAndRemoveTaskSafe();
                    return;
                }
            } catch (Exception ignored) {}
        }

        sInstance = this;
        measurePanel();

        try {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Exception ignored) {}

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Dense: tighter inset on 410px panel
        int pad = Math.max(8, panelW / 28);
        col.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        flp.gravity = Gravity.CENTER;
        root.addView(col, flp);

        topLine = mkText(false);
        midLine = mkText(false);
        botLine = mkText(false);
        clock = mkText(true);
        rule = new View(this);

        setContentView(root);

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_DISMISS);
            f.addAction(ACTION_REPAINT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(rx, f);
            }
        } catch (Exception ignored) {}

        rebuildLayout();
        paint();
    }

    private void measurePanel() {
        try {
            DisplayMetrics m = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
                getDisplay().getRealMetrics(m);
            } else {
                getWindowManager().getDefaultDisplay().getRealMetrics(m);
            }
            if (m.widthPixels > 0) panelW = m.widthPixels;
            if (m.heightPixels > 0) panelH = m.heightPixels;
        } catch (Exception ignored) {}
    }

    private TextView mkText(boolean clockFace) {
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        // Mono facts on OLED — Cube keyboard-first night face
        t.setTypeface(Typeface.MONOSPACE, clockFace ? Typeface.BOLD : Typeface.NORMAL);
        t.setSingleLine(true);
        t.setMaxLines(1);
        t.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void rebuildLayout() {
        col.removeAllViews();
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        SubDisplayPrefs.FaceStyle style = mode == SubDisplayPrefs.Mode.STOCK
            ? SubDisplayPrefs.FaceStyle.STATUS
            : SubDisplayPrefs.getFaceStyle(this);
        int gap = Math.max(2, panelH / 100);

        if (style == SubDisplayPrefs.FaceStyle.MINIMAL) {
            col.setGravity(Gravity.CENTER);
            col.addView(clock);
            return;
        }
        if (style == SubDisplayPrefs.FaceStyle.STATUS) {
            // Dense: one meta strip, hairline, time — no brand footer
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
            return;
        }
        // classic — centered, time dominant but width-capped
        col.setGravity(Gravity.CENTER);
        col.addView(topLine);
        addGap(gap);
        col.addView(clock);
        addGap(gap);
        col.addView(midLine);
    }

    private void addGap(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        col.addView(v);
    }

    private void paint() {
        if (!faceMode(this)) {
            finishAndRemoveTaskSafe();
            return;
        }
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        boolean stock = mode == SubDisplayPrefs.Mode.STOCK;

        int ink, muted, dim;
        if (stock) {
            ink = STOCK_CYAN;
            muted = STOCK_MUTED;
            dim = 0xFF2A5A54;
        } else {
            SubDisplayPrefs.FaceTheme theme = SubDisplayPrefs.getFaceTheme(this);
            ink = theme.ink();
            muted = theme.muted();
            dim = theme.dim();
        }

        Date now = new Date();
        boolean sec = SubDisplayPrefs.widgetSeconds(this);
        boolean h24 = SubDisplayPrefs.hour24(this);
        String tPat = h24 ? (sec ? "HH:mm:ss" : "HH:mm") : (sec ? "h:mm:ss" : "h:mm");
        String time = new SimpleDateFormat(tPat, Locale.getDefault()).format(now);
        if (!h24) {
            time = time + " " + new SimpleDateFormat("a", Locale.getDefault()).format(now);
        }

        int scale = SubDisplayPrefs.getClockScale(this);
        float[] hFrac = {0.10f, 0.15f, 0.22f, 0.30f, 0.40f};
        float[] wFrac = {0.72f, 0.82f, 0.90f, 0.96f, 0.99f};
        float maxH = panelH * hFrac[scale];
        float maxW = panelW * wFrac[scale];
        float sizePx = fitTextSize(time, maxW, maxH, true);
        float minPx = panelH * 0.08f;
        float maxPx = panelH * 0.45f;
        if (sizePx < minPx) sizePx = minPx;
        if (sizePx > maxPx) sizePx = maxPx;

        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clock.setText(time);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        clock.setLetterSpacing(sec ? 0f : 0.06f);

        String week = new SimpleDateFormat("EEE", Locale.getDefault()).format(now).toUpperCase(Locale.US);
        String date = new SimpleDateFormat("dd MMM", Locale.getDefault()).format(now).toUpperCase(Locale.US);
        int bat = batteryPct();
        String batS = bat >= 0 ? bat + "%" : "—";

        boolean showWeek = SubDisplayPrefs.widgetWeekday(this);
        boolean showDate = SubDisplayPrefs.widgetDate(this);
        boolean showBat = SubDisplayPrefs.widgetBattery(this);
        SubDisplayPrefs.FaceStyle style = stock
            ? SubDisplayPrefs.FaceStyle.STATUS
            : SubDisplayPrefs.getFaceStyle(this);

        float metaH = panelH * 0.035f;
        if (style == SubDisplayPrefs.FaceStyle.MINIMAL) {
            topLine.setVisibility(View.GONE);
            midLine.setVisibility(View.GONE);
            botLine.setVisibility(View.GONE);
            if (rule.getParent() != null) rule.setVisibility(View.GONE);
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
            setLine(topLine, top.toString(), muted, metaH, panelW * 0.92f);
            // Thin accent hairline under meta (always; not a brand mark)
            rule.setBackgroundColor(dim);
            rule.setVisibility(View.VISIBLE);
            midLine.setVisibility(View.GONE);
            botLine.setVisibility(View.GONE);
            return;
        }

        // classic — weekday above, date+bat below; no brand line
        setLine(topLine, showWeek ? week : "", muted, metaH, panelW * 0.9f);
        topLine.setVisibility(showWeek ? View.VISIBLE : View.GONE);
        String mid = "";
        if (showDate && showBat) mid = date + "  " + batS;
        else if (showDate) mid = date;
        else if (showBat) mid = batS;
        setLine(midLine, mid, muted, metaH, panelW * 0.9f);
        midLine.setVisibility(mid.isEmpty() ? View.GONE : View.VISIBLE);
        botLine.setVisibility(View.GONE);
        if (rule.getParent() != null) rule.setVisibility(View.GONE);
    }

    private void setLine(TextView tv, String text, int color, float wantPx, float maxW) {
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
            Math.min(fitTextSize(text, maxW, wantPx, false), wantPx * 1.15f));
    }

    private float fitTextSize(String text, float maxW, float maxH, boolean monoClock) {
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

    private int batteryPct() {
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return -1;
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(100f * level / scale);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!faceMode(this)) {
            finishAndRemoveTaskSafe();
            return;
        }
        rebuildLayout();
        paint();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!faceMode(this)) {
            finishAndRemoveTaskSafe();
            return;
        }
        sInstance = this;
        tickOn = true;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        tickOn = false;
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (sInstance == this) sInstance = null;
        try { unregisterReceiver(rx); } catch (Exception ignored) {}
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
