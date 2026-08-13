package com.titanus2.cubecontact;

import android.graphics.Color;
import android.service.dreams.DreamService;
import android.widget.FrameLayout;

/**
 * Daydream / screensaver — full-screen Neural Cube lattice.
 * Uses front-plane matrix source. Energy flows while the device dreams.
 */
public class CubeDreamService extends DreamService {
    private CubeGLView gl;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        gl = new CubeGLView(this);
        gl.setPlane(CubePlanePrefs.PLANE_FRONT);
        root.addView(gl, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (gl != null) {
            try { gl.onResume(); } catch (Exception ignored) {}
            try { gl.matrix().ensureSeedFrame(); } catch (Exception ignored) {}
            gl.requestRender();
        }
    }

    @Override public void onDreamingStopped() {
        if (gl != null) {
            try { gl.onPause(); } catch (Exception ignored) {}
        }
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (gl != null) {
            try { gl.onPause(); } catch (Exception ignored) {}
            gl = null;
        }
        super.onDetachedFromWindow();
    }
}
