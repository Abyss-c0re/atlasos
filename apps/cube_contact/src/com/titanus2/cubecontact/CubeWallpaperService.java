package com.titanus2.cubecontact;

import android.content.Context;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Live wallpaper — same OpenGL Neural Cube as the app
 * ({@code CubeGLView} / cube_gl --levitate --mono). Not the Canvas point-cage.
 */
public class CubeWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() {
        return new CubeEngine();
    }

    private final class CubeEngine extends Engine {
        private WallGL gl;

        /**
         * GLSurfaceView that draws on the wallpaper Engine surface.
         * Same renderer / lattice / levitate as Neural Cube in-app.
         */
        private final class WallGL extends CubeGLView {
            WallGL(Context c) { super(c); }

            @Override public SurfaceHolder getHolder() {
                return CubeEngine.this.getSurfaceHolder();
            }
        }

        @Override public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(false);
            setOffsetNotificationsEnabled(false);
            gl = new WallGL(CubeWallpaperService.this);
            gl.setPlane(CubePlanePrefs.PLANE_FRONT);
            gl.setLevitate(true);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (gl == null) return;
            if (visible) {
                gl.startEngineFace();
            } else {
                gl.stopEngineFace();
            }
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (gl != null) gl.stopEngineFace();
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            if (gl != null) {
                gl.stopEngineFace();
                gl = null;
            }
            super.onDestroy();
        }
    }
}
