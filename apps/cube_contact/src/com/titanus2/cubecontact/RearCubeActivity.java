package com.titanus2.cubecontact;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * First-class subdisplay resident: OpenGL prophecy lattice only.
 * No text HUD · no clocks · no SystemUI chrome.
 * Pinch zoom + drag orbit · cool async render path.
 */
public class RearCubeActivity extends Activity {
    public static final String ACTION_DISMISS = "com.titanus2.cubecontact.REAR_DISMISS";
    private static volatile RearCubeActivity sInstance;
    /** Real OpenGL Cube Experience (cube_gl --levitate --mono), not canvas mesh. */
    private CubeGLView gl;
    private Handler lawHandler;
    private Runnable lawPoll;
    private static final int REAR_SEED_PULL_EVERY = 4;
    private int rearSeedPullTicks;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_DISMISS.equals(intent.getAction())) {
                try {
                    if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
                    else finish();
                } catch (Exception e) {
                    try { finish(); } catch (Exception ignored) {}
                }
            }
        }
    };

    public static boolean isLive() { return sInstance != null; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        try { SensorPrefs.ensureDefaultVirtual(this); } catch (Exception ignored) {}
        rearSeedPullTicks = 0;
        try { VirtualSensorSync.promotePeerWhileSeed(this); } catch (Exception ignored) {}
        // Digitizer must target display 2 or spin/zoom never reach this activity.
        try { SubTouchAssoc.ensureAsync(); } catch (Exception ignored) {}
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            // Residual: secondary display still reported fmt=TRANSLUCENT with
            // gray show-through under GL (wallpaper dim already 0). Force pure
            // black opaque + no dim-behind wash every attach.
            forceRearOpaqueBlack();
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try { decor.setBackgroundColor(Color.BLACK); } catch (Exception ignored) {}

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Truth face: peer BrainCube only (no demo densify as "live").
        try {
            CubePlanePrefs.setSource(this, CubePlanePrefs.PLANE_REAR, MatrixSource.PEER);
            CubePlanePrefs.setAutoSpin(this, CubePlanePrefs.PLANE_REAR, true);
        } catch (Exception ignored) {}
        gl = new CubeGLView(this);
        gl.setForceCompact(true);
        gl.setLevitate(true);
        gl.setPlane(CubePlanePrefs.PLANE_REAR);
        gl.setFocusable(true);
        gl.setFocusableInTouchMode(true);
        gl.setClickable(true);
        gl.setOnLongClickListener(v -> {
            try {
                Intent i = new Intent(this, CubeSettingsActivity.class);
                i.putExtra(CubeSettingsActivity.EXTRA_PLANE, CubePlanePrefs.PLANE_REAR);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
            return true;
        });
        root.addView(gl, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // Silent seed promote only — no text on subscreen.
        lawHandler = new Handler(Looper.getMainLooper());
        lawPoll = new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                try {
                    if (!VirtualSensorSync.isLawEnergyPromoted(RearCubeActivity.this)) {
                        rearSeedPullTicks++;
                        if (rearSeedPullTicks >= REAR_SEED_PULL_EVERY) {
                            rearSeedPullTicks = 0;
                            try {
                                VirtualSensorSync.promotePeerWhileSeed(
                                    RearCubeActivity.this);
                            } catch (Exception ignored) {}
                        }
                    } else {
                        rearSeedPullTicks = 0;
                    }
                } catch (Exception ignored) {}
                boolean seed = true;
                try {
                    seed = !VirtualSensorSync.isLawEnergyPromoted(RearCubeActivity.this);
                } catch (Exception ignored) {}
                if (lawHandler != null) {
                    // Cool path: sparse promote, never UI thrash
                    long ms = CubeStability.isCubeHeat(RearCubeActivity.this)
                        ? (seed ? 4000L : 8000L)
                        : (seed ? 2500L : 6000L);
                    lawHandler.postDelayed(this, ms);
                }
            }
        };
        lawHandler.postDelayed(lawPoll, 1500L);

        setContentView(root);
        try {
            gl.post(() -> {
                try {
                    gl.requestFocus();
                    gl.manifestProphecy();
                    gl.requestRender();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rx, new IntentFilter(ACTION_DISMISS), RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(rx, new IntentFilter(ACTION_DISMISS));
            }
        } catch (Exception ignored) {}
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Subdisplay residual: fmt=TRANSLUCENT → gray under cube. */
    private void forceRearOpaqueBlack() {
        try {
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams wp = getWindow().getAttributes();
            if (wp != null) {
                wp.format = android.graphics.PixelFormat.OPAQUE;
                wp.dimAmount = 0f;
                getWindow().setAttributes(wp);
            }
            getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
            if (Build.VERSION.SDK_INT >= 21) {
                getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(Color.BLACK);
                getWindow().setNavigationBarColor(Color.BLACK);
            }
            View decor = getWindow().getDecorView();
            if (decor != null) decor.setBackgroundColor(Color.BLACK);
        } catch (Exception ignored) {}
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        forceRearOpaqueBlack();
    }

    @Override protected void onResume() {
        super.onResume();
        forceRearOpaqueBlack();
        try { SubTouchAssoc.ensureAsync(); } catch (Exception ignored) {}
        if (gl != null) {
            try {
                gl.onResume();
                gl.setPlane(CubePlanePrefs.PLANE_REAR);
                gl.setLevitate(true);
                gl.manifestProphecy();
                gl.requestRender();
            } catch (Exception ignored) {}
        }
        try { CubeSurfacePrefs.apply(this); } catch (Exception ignored) {}
        try {
            if (!VirtualSensorSync.isLawEnergyPromoted(this)) {
                rearSeedPullTicks = 0;
                VirtualSensorSync.promotePeerWhileSeed(this);
            }
        } catch (Exception ignored) {}
        if (lawHandler != null && lawPoll != null) {
            lawHandler.removeCallbacks(lawPoll);
            lawHandler.postDelayed(lawPoll, 2000L);
        }
    }

    @Override protected void onPause() {
        try { if (gl != null) gl.onPause(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (lawHandler != null && lawPoll != null) {
            try { lawHandler.removeCallbacks(lawPoll); } catch (Exception ignored) {}
        }
        lawHandler = null;
        lawPoll = null;
        rearSeedPullTicks = 0;
        try { unregisterReceiver(rx); } catch (Exception ignored) {}
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }
}
