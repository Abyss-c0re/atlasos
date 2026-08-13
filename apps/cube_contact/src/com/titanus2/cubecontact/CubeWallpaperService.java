package com.titanus2.cubecontact;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Live wallpaper — Neural Cube lattice on the home screen.
 * Uses front-plane matrix source. Glory to the Cube.
 */
public class CubeWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() {
        return new CubeEngine();
    }

    private final class CubeEngine extends Engine {
        private final Handler h = new Handler(Looper.getMainLooper());
        private final StateMatrix matrix = new StateMatrix();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float yaw = 0.6f;
        private boolean visible;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!visible) return;
                if (CubePlanePrefs.autoSpin(CubeWallpaperService.this, CubePlanePrefs.PLANE_FRONT)) {
                    yaw += 0.04f;
                }
                draw();
                h.postDelayed(this, CubeStability.isCubeHeat(CubeWallpaperService.this) ? 120 : 50);
            }
        };

        @Override public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            try { StateMatrix.bindAppContext(CubeWallpaperService.this); } catch (Exception ignored) {}
            matrix.setPreferredPlane(CubePlanePrefs.PLANE_FRONT);
            try { matrix.ensureSeedFrame(); } catch (Exception ignored) {}
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            h.removeCallbacks(tick);
            if (visible) {
                try {
                    if (CubeStability.allowPeerHttp(CubeWallpaperService.this)) {
                        matrix.refreshFromPeer();
                    } else {
                        matrix.ensureSeedFrame();
                    }
                } catch (Exception ignored) {}
                h.post(tick);
            }
        }

        @Override public void onDestroy() {
            h.removeCallbacks(tick);
            super.onDestroy();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            draw();
        }

        private void draw() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                int w = c.getWidth(), hgt = c.getHeight();
                c.drawColor(Color.BLACK);
                if (!matrix.haveFrame) {
                    try { matrix.ensureSeedFrame(); } catch (Exception ignored) {}
                }
                if (!matrix.haveFrame || w < 4 || hgt < 4) return;
                int n = matrix.n;
                float cx = w * 0.5f, cy = hgt * 0.48f;
                float cell = 1f;
                float origin = -0.5f * (n - 1) * cell;
                float half = 0.5f * n * cell + 0.15f;
                float fit = 0.36f;
                float scale = Math.min(w, hgt) * fit / Math.max(half, 1f);
                float cyA = (float) Math.cos(yaw), syA = (float) Math.sin(yaw);
                float pitch = 0.45f;
                float cxA = (float) Math.cos(pitch), sxA = (float) Math.sin(pitch);
                // cage
                paint.setColor(Color.argb(90, 160, 20, 30));
                paint.setStrokeWidth(2f);
                paint.setStyle(Paint.Style.STROKE);
                float e = half;
                float[][] corners = {
                    {-e,-e,-e},{e,-e,-e},{e,e,-e},{-e,e,-e},
                    {-e,-e,e},{e,-e,e},{e,e,e},{-e,e,e}
                };
                int[][] edges = {
                    {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}
                };
                float[] tmp = new float[3];
                for (int[] ed : edges) {
                    project(corners[ed[0]][0], corners[ed[0]][1], corners[ed[0]][2],
                        cyA, syA, cxA, sxA, cx, cy, scale, tmp);
                    float ax = tmp[0], ay = tmp[1];
                    project(corners[ed[1]][0], corners[ed[1]][1], corners[ed[1]][2],
                        cyA, syA, cxA, sxA, cx, cy, scale, tmp);
                    c.drawLine(ax, ay, tmp[0], tmp[1], paint);
                }
                // nodes (sparse sample for wallpaper heat)
                paint.setStyle(Paint.Style.FILL);
                int step = n > 10 ? 2 : 1;
                for (int z = 0; z < n; z += step)
                    for (int y = 0; y < n; y += step)
                        for (int x = 0; x < n; x += step) {
                            int idx = x + y * n + z * n * n;
                            if (idx >= matrix.cells.length) continue;
                            int dig = matrix.cells[idx] & 0xff;
                            if (dig <= 0) continue;
                            float px = origin + x * cell;
                            float py = origin + y * cell;
                            float pz = origin + z * cell;
                            project(px, py, pz, cyA, syA, cxA, sxA, cx, cy, scale, tmp);
                            float v = dig / 9f;
                            paint.setColor(Color.argb(200,
                                (int) (140 + 100 * v), (int) (8 + 20 * v), (int) (12 + 10 * v)));
                            float r = 2.5f + dig * 0.35f;
                            c.drawCircle(tmp[0], tmp[1], r, paint);
                        }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) {}
                }
            }
        }

        private void project(float x, float y, float z,
                             float cyA, float syA, float cxA, float sxA,
                             float cx, float cy, float scale, float[] out) {
            float x1 = x * cyA - z * syA;
            float z1 = x * syA + z * cyA;
            float y1 = y * cxA - z1 * sxA;
            float z2 = y * sxA + z1 * cxA;
            float persp = 2.2f / (3.2f + z2);
            out[0] = cx + x1 * scale * persp;
            out[1] = cy - y1 * scale * persp;
            out[2] = z2;
        }
    }
}
