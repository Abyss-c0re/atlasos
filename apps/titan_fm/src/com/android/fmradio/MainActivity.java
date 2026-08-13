package com.android.fmradio;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Minimal FM UI: frequency, power, seek, speaker, presets.
 * DeviceDefault / Settings look — no Cube tile chrome.
 */
public final class MainActivity extends Activity implements FmEngine.Listener {
    private static final String PREFS = "titan_fm";
    private static final String KEY_FREQ = "freq";
    private static final String KEY_SPK = "speaker";
    private static final String KEY_ANT = "antenna";
    private static final String KEY_PRESETS = "presets";
    private static final int REQ_RECORD = 100;

    private FmEngine mEngine;
    private TextView mFreqView;
    private TextView mRdsView;
    private TextView mMicBanner;
    private TextView mStatusView;
    private Button mPowerBtn;
    private SeekBar mSeek;
    private Switch mSpeakerSw;
    private Switch mAntennaSw;
    private LinearLayout mPresetRow;
    /** After permission dialog, finish the user's Power intent. */
    private boolean mPendingPowerOn;
    private final android.os.Handler mRdsTimer = new android.os.Handler();
    private final Runnable mRdsTick = new Runnable() {
        @Override
        public void run() {
            if (mEngine != null && mEngine.isPowered()) {
                mEngine.pollRdsOnce();
            }
            refreshMicGate();
            mRdsTimer.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = label("FM Radio", 22, true);
        root.addView(title);

        mFreqView = label("97.5", 48, true);
        mFreqView.setGravity(Gravity.CENTER);
        mFreqView.setPadding(0, dp(12), 0, dp(4));
        root.addView(mFreqView);

        mRdsView = label("—", 14, false);
        mRdsView.setGravity(Gravity.CENTER);
        root.addView(mRdsView);

        mMicBanner = label("", 13, true);
        mMicBanner.setGravity(Gravity.CENTER);
        mMicBanner.setPadding(dp(8), dp(8), dp(8), dp(8));
        mMicBanner.setVisibility(android.view.View.GONE);
        root.addView(mMicBanner);

        mSeek = new SeekBar(this);
        mSeek.setMax(205);
        mSeek.setPadding(dp(8), dp(16), dp(8), dp(8));
        root.addView(mSeek);

        // Big primary control — not squeezed into a 5-way row (cube + fat finger).
        mPowerBtn = btn("Power");
        mPowerBtn.setMinHeight(dp(56));
        mPowerBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        powerLp.topMargin = dp(8);
        powerLp.bottomMargin = dp(8);
        root.addView(mPowerBtn, powerLp);

        LinearLayout row1 = row();
        Button stepDown = btn("−0.1");
        Button seekDown = btn("≪");
        Button seekUp = btn("≫");
        Button stepUp = btn("+0.1");
        row1.addView(stepDown, weight());
        row1.addView(seekDown, weight());
        row1.addView(seekUp, weight());
        row1.addView(stepUp, weight());
        root.addView(row1);

        mSpeakerSw = new Switch(this);
        mSpeakerSw.setText("Speaker");
        mSpeakerSw.setChecked(p.getBoolean(KEY_SPK, true));
        mSpeakerSw.setPadding(0, dp(12), 0, dp(4));
        root.addView(mSpeakerSw);

        mAntennaSw = new Switch(this);
        mAntennaSw.setText("Internal antenna");
        mAntennaSw.setChecked(p.getInt(KEY_ANT, 1) == 1);
        mAntennaSw.setPadding(0, dp(4), 0, dp(8));
        root.addView(mAntennaSw);

        TextView presetsTitle = label("Presets", 16, true);
        presetsTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(presetsTitle);

        mPresetRow = row();
        root.addView(mPresetRow);
        Button savePreset = btn("Save current");
        root.addView(savePreset);

        mStatusView = label("idle", 12, false);
        mStatusView.setPadding(0, dp(16), 0, 0);
        mStatusView.setTypeface(Typeface.MONOSPACE);
        root.addView(mStatusView);

        float freq = p.getFloat(KEY_FREQ, 97.5f);
        setFreqUi(freq);
        rebuildPresets(p.getString(KEY_PRESETS, "87.5,91.0,97.5,100.0,103.5,106.0"));

        // Process-wide engine (held by FmService while playing — survives screen-off).
        mEngine = FmEngine.get(this);
        mEngine.setUiListener(this);
        mEngine.setSpeaker(mSpeakerSw.isChecked());
        // Prefer auto antenna from devices; switch only overrides when user flips it.
        if (!mAntennaSw.isChecked()) {
            mEngine.setAutoAntenna(true);
        } else {
            mEngine.setAntenna(1);
        }
        mEngine.tune(freq);
        // Keep service process warm when UI is open
        FmService.ensureRunning(this);

        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float f = FmEngine.FREQ_MIN + progress * FmEngine.FREQ_STEP;
                setFreqUi(f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float f = FmEngine.FREQ_MIN + seekBar.getProgress() * FmEngine.FREQ_STEP;
                mEngine.tune(f);
                persistFreq(f);
            }
        });

        mPowerBtn.setOnClickListener(v -> onPowerClicked());
        stepDown.setOnClickListener(v -> {
            mEngine.step(false);
            persistFreq(mEngine.getFrequency());
        });
        stepUp.setOnClickListener(v -> {
            mEngine.step(true);
            persistFreq(mEngine.getFrequency());
        });
        seekDown.setOnClickListener(v -> mEngine.seek(false));
        seekUp.setOnClickListener(v -> mEngine.seek(true));
        mSpeakerSw.setOnCheckedChangeListener((b, on) -> {
            mEngine.setSpeaker(on);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SPK, on).apply();
        });
        mAntennaSw.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                mEngine.setAntenna(1);
            } else {
                mEngine.setAutoAntenna(true);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_ANT, on ? 1 : 0).apply();
        });
        savePreset.setOnClickListener(v -> {
            float f = mEngine.getFrequency();
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String cur = sp.getString(KEY_PRESETS, "");
            String add = String.format(java.util.Locale.US, "%.1f", f);
            if (!cur.contains(add)) {
                cur = cur.isEmpty() ? add : cur + "," + add;
                sp.edit().putString(KEY_PRESETS, cur).apply();
                rebuildPresets(cur);
                Toast.makeText(this, "Saved " + add, Toast.LENGTH_SHORT).show();
            }
        });

        // Warm permission once so Power is one tap later
        if (!hasRecordPerm()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
        }
        refreshMicGate();

        if (getIntent() != null && getIntent().getBooleanExtra("auto_on", false)) {
            mPendingPowerOn = true;
            tryStartPower();
        }
    }

    /** UI Power / Stop. Never silent-return without status. */
    private void onPowerClicked() {
        if (mEngine == null) {
            status("engine missing");
            return;
        }
        if (mEngine.isPowered()) {
            mPendingPowerOn = false;
            status("stopping…");
            FmService.powerOff(this);
            return;
        }
        mPendingPowerOn = true;
        tryStartPower();
    }

    private void tryStartPower() {
        if (mEngine == null) return;
        float freq = mEngine.getFrequency();
        MicCaptureGate.State mic = MicCaptureGate.check(this);
        refreshMicGate();
        if (mic == MicCaptureGate.State.PRIVACY_ON
                || mic == MicCaptureGate.State.MIC_MUTED
                || mic == MicCaptureGate.State.APP_OPS_DENIED) {
            status(MicCaptureGate.message(mic));
            Toast.makeText(this, MicCaptureGate.message(mic), Toast.LENGTH_LONG).show();
            mPendingPowerOn = false;
            return;
        }
        if (!hasRecordPerm() || mic == MicCaptureGate.State.PERMISSION_DENIED) {
            status("requesting mic for FM capture…");
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            // If dialog is blocked (keyguard / already granted as priv-app), retry once.
            mPowerBtn.postDelayed(() -> {
                if (!mPendingPowerOn || mEngine == null || mEngine.isPowered()) return;
                MicCaptureGate.State again = MicCaptureGate.check(this);
                refreshMicGate();
                if (again != MicCaptureGate.State.OK) {
                    status(MicCaptureGate.message(again));
                    mPendingPowerOn = false;
                    return;
                }
                status("starting…");
                FmService.powerOn(this, mEngine.getFrequency());
                mPendingPowerOn = false;
            }, 400);
            return;
        }
        status("starting…");
        // FGS owns playback — activity can die on screen-off without killing radio.
        FmService.powerOn(this, freq);
        mPendingPowerOn = false;
    }

    private void refreshMicGate() {
        MicCaptureGate.State s = MicCaptureGate.check(this);
        if (mMicBanner == null) return;
        if (s == MicCaptureGate.State.OK) {
            mMicBanner.setVisibility(android.view.View.GONE);
            mMicBanner.setText("");
            return;
        }
        mMicBanner.setVisibility(android.view.View.VISIBLE);
        mMicBanner.setText(MicCaptureGate.message(s));
        mMicBanner.setBackgroundColor(0x33FF5252);
        mMicBanner.setTextColor(Color.WHITE);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD) return;
        boolean ok = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        refreshMicGate();
        if (ok) {
            status("mic permission ok");
        } else {
            status(MicCaptureGate.message(MicCaptureGate.State.PERMISSION_DENIED));
        }
        if (mPendingPowerOn && mEngine != null && !mEngine.isPowered()) {
            tryStartPower();
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || mEngine == null) return;
        if (intent.getBooleanExtra("auto_on", false)) {
            mPendingPowerOn = true;
            tryStartPower();
        }
        if (intent.getBooleanExtra("auto_off", false)) {
            mPendingPowerOn = false;
            FmService.powerOff(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRdsTimer.removeCallbacks(mRdsTick);
        mRdsTimer.postDelayed(mRdsTick, 2000);
        // Re-attach UI listener (service may own engine while we were away)
        mEngine = FmEngine.get(this);
        mEngine.setUiListener(this);
        onPower(mEngine.isPowered());
        setFreqUi(mEngine.getFrequency());
        refreshMicGate();
    }

    @Override
    protected void onPause() {
        mRdsTimer.removeCallbacks(mRdsTick);
        // Detach UI only — service keeps engine + FGS alive with screen off.
        if (mEngine != null) {
            mEngine.setUiListener(null);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mRdsTimer.removeCallbacks(mRdsTick);
        // NEVER release engine here — that was killing FM on screen-off.
        if (mEngine != null) {
            mEngine.setUiListener(null);
        }
        super.onDestroy();
    }

    @Override
    public void onState(String line) {
        status(line);
    }

    @Override
    public void onPower(boolean on) {
        if (mPowerBtn != null) {
            mPowerBtn.setText(on ? "Stop" : "Power");
        }
        if (on) {
            mPendingPowerOn = false;
        }
    }

    @Override
    public void onFrequency(float mhz) {
        setFreqUi(mhz);
        persistFreq(mhz);
    }

    @Override
    public void onRds(String ps, String rt) {
        if (mRdsView == null) return;
        String s = (ps == null || ps.isEmpty()) ? "—" : ps;
        if (rt != null && !rt.isEmpty()) s = s + "  ·  " + rt;
        mRdsView.setText(s);
    }

    private void status(String line) {
        if (mStatusView != null) mStatusView.setText(line);
    }

    private void setFreqUi(float mhz) {
        if (mFreqView != null) {
            mFreqView.setText(String.format(java.util.Locale.US, "%.1f", mhz));
        }
        if (mSeek != null) {
            int prog = Math.round((mhz - FmEngine.FREQ_MIN) / FmEngine.FREQ_STEP);
            if (prog < 0) prog = 0;
            if (prog > mSeek.getMax()) prog = mSeek.getMax();
            if (mSeek.getProgress() != prog) mSeek.setProgress(prog);
        }
    }

    private void persistFreq(float f) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_FREQ, f).apply();
    }

    private void rebuildPresets(String csv) {
        mPresetRow.removeAllViews();
        if (csv == null || csv.isEmpty()) return;
        for (String part : csv.split(",")) {
            final String s = part.trim();
            if (s.isEmpty()) continue;
            Button b = btn(s);
            b.setOnClickListener(v -> {
                try {
                    float f = Float.parseFloat(s);
                    mEngine.tune(f);
                    persistFreq(f);
                    if (mEngine.isPowered()) {
                        // Keep FGS notif / service in sync with tune
                        Intent ti = new Intent(this, FmService.class)
                                .setAction(FmService.ACTION_TUNE)
                                .putExtra(FmService.EXTRA_FREQ, f);
                        if (Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(ti);
                        } else {
                            startService(ti);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            });
            mPresetRow.addView(b, weight());
        }
    }

    private boolean hasRecordPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        return lp;
    }

    private TextView label(String t, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button btn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
