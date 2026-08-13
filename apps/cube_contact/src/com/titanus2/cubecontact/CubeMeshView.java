package com.titanus2.cubecontact;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.lang.Thread;

/**
 * Lattice from live StateMatrix. Tap a cell → selection (what it represents).
 * Projection + nodes mirror desktop cube_gl (cage + lattice + digit nodes + impulses).
 */
public class CubeMeshView extends View {
    public interface SelectionListener {
        void onSelection(int cellIdx, String description);
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StateMatrix matrix = new StateMatrix();
    /** Idle orbit (rad) — gentle auto-spin; drag still works. */
    private float yaw = (float) Math.toRadians(35);
    private float pitch = (float) Math.toRadians(25);
    private boolean autoSpin = true;
    private boolean forceCompact;
    private long t0 = System.nanoTime();
    private final float[] tmp = new float[3];
    private final float[] screenX = new float[StateMatrix.MAX_CELLS];
    private final float[] screenY = new float[StateMatrix.MAX_CELLS];
    private final float[] screenZ = new float[StateMatrix.MAX_CELLS];
    private boolean haveProj;
    private int selectCell = -1;
    private int hoverCell = -1;
    private float globalPulse;
    private SelectionListener listener;

    private final Object pullLock = new Object();
    private boolean pulling;

    private long lastPullMs;
    private static final java.util.concurrent.ExecutorService PULL_EX =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mesh-matrix-pull");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            Context ctx = getContext();
            matrix.decayImpulses(0.88f);
            if (globalPulse > 0f) {
                globalPulse *= 0.90f;
                if (globalPulse < 0.02f) globalPulse = 0f;
            }
            boolean impulse = globalPulse > 0.02f;
            if (!impulse) {
                int need = Math.min(matrix.n * matrix.n * matrix.n, StateMatrix.MAX_CELLS);
                for (int i = 0; i < need; i++) {
                    if (matrix.impulse[i] > 0.05f) { impulse = true; break; }
                }
            }
            if (autoSpin) {
                // Keep orbit readable under heat (old 0.008 rad looked frozen).
                float spin = CubeStability.isCubeHeat(ctx) ? 0.022f : 0.032f;
                yaw += spin;
            }
            boolean anim = autoSpin || CubeStability.allowAnimation(ctx, false, impulse);
            if (anim) invalidate();
            long now = System.currentTimeMillis();
            // 1.70 residual: under thermal severe skip peer HTTP (file/kernel SoT).
            // 1.69 closed EDGE multi-pull but mesh still refreshFromPeer every 8s.
            if (CubeStability.allowPeerHttp(ctx)) {
                long every = CubeStability.peerPullIntervalMs(ctx);
                if (now - lastPullMs >= every) {
                    lastPullMs = now;
                    synchronized (pullLock) {
                        if (!pulling) {
                            pulling = true;
                            PULL_EX.execute(() -> {
                                try {
                                    boolean ok = matrix.refreshFromPeer();
                                    if (ok) CubeStability.notePeerOk();
                                    else CubeStability.notePeerFail();
                                    if (ok) h.post(() -> {
                                        if (isAttachedToWindow()) invalidate();
                                    });
                                } finally {
                                    synchronized (pullLock) { pulling = false; }
                                }
                            });
                        }
                    }
                }
            }
            h.postDelayed(this, anim ? 100 : 250);
        }
    };

    public CubeMeshView(Context c) { super(c); init(); }
    public CubeMeshView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        // 1.12: bind app Context so mesh path LAW persist uses real filesDir.
        try { StateMatrix.bindAppContext(getContext()); } catch (Exception ignored) {}
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.2f);
        fill.setStyle(Paint.Style.FILL);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(2.5f);
        selPaint.setColor(Color.argb(220, 255, 220, 80));
    }

    public StateMatrix matrix() { return matrix; }
    public int selectedCell() { return selectCell; }
    public void setSelectionListener(SelectionListener l) { listener = l; }
    public void setForceCompact(boolean v) { forceCompact = v; }

    /** Match CubeGLView plane API (front/rear prefs + source). */
    public void setPlane(String p) {
        matrix.setPreferredPlane(CubePlanePrefs.normalizePlane(p));
        try {
            Context ctx = getContext();
            if (ctx != null) autoSpin = CubePlanePrefs.autoSpin(ctx, matrix.preferredPlane());
        } catch (Exception ignored) {}
    }

    /** Force dense crimson + peer pull (BrainCube eyes). */
    public void manifestProphecy() {
        PULL_EX.execute(() -> {
            try {
                matrix.ensureCrimsonLattice();
                matrix.refreshFromPeer();
                matrix.ensureCrimsonLattice();
            } catch (Exception ignored) {
                try { matrix.ensureCrimsonLattice(); } catch (Exception ignored2) {}
            }
            h.post(() -> {
                if (isAttachedToWindow()) invalidate();
            });
        });
    }

    public void selectSub(int i) {
        matrix.selectSub(i);
        fireSelection();
        invalidate();
    }

    public void nextSub(int dir) {
        matrix.nextSub(dir);
        fireSelection();
        invalidate();
    }

    public String statusLine() {
        StateMatrix.SubCube sc = matrix.currentSub();
        return matrix.statusLine()
            + " · " + sc.name
            + (matrix.model.isEmpty() ? "" : " · " + matrix.model);
    }

    public String selectionText() {
        return matrix.describeCell(selectCell >= 0 ? selectCell : hoverCell);
    }

    private void fireSelection() {
        if (listener != null) {
            int idx = selectCell >= 0 ? selectCell : hoverCell;
            listener.onSelection(idx, matrix.describeCell(idx));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        t0 = System.nanoTime();
        // 1.93: always seed crimson then BrainCube — Canvas path never black void.
        try { matrix.ensureCrimsonLattice(); } catch (Exception ignored) {}
        manifestProphecy();
        h.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN
                || e.getAction() == MotionEvent.ACTION_MOVE) {
            int idx = pickCell(e.getX(), e.getY());
            hoverCell = idx;
            if (e.getAction() == MotionEvent.ACTION_DOWN && idx >= 0) {
                selectCell = idx;
                if (matrix.impulse[idx] < 0.3f) matrix.impulse[idx] = 0.6f;
                fireSelection();
            }
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private int pickCell(float sx, float sy) {
        if (!haveProj || !matrix.haveFrame) return -1;
        int n = matrix.n;
        int need = n * n * n;
        int best = -1;
        float bestD = 48f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < need; i++) {
            float dx = screenX[i] - sx;
            float dy = screenY[i] - sy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            // prefer front cells
            d += screenZ[i] * 2f;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void digitColor(int d, float[] rgb) {
        /* hotter crimson — match desktop dense prophecy */
        float v = (d < 0 ? 0 : (d > 9 ? 9 : d)) / 9f;
        rgb[0] = 0.55f + 0.45f * v;
        rgb[1] = 0.02f + 0.08f * v;
        rgb[2] = 0.03f + 0.05f * v;
    }

    private void project(float x, float y, float z, float cx, float cy, float scale, float[] out) {
        float cyA = (float) Math.cos(yaw), syA = (float) Math.sin(yaw);
        float cxA = (float) Math.cos(pitch), sxA = (float) Math.sin(pitch);
        float x1 = x * cyA - z * syA;
        float z1 = x * syA + z * cyA;
        float y1 = y * cxA - z1 * sxA;
        float z2 = y * sxA + z1 * cxA;
        float persp = 2.2f / (3.2f + z2);
        out[0] = cx + x1 * scale * persp;
        out[1] = cy - y1 * scale * persp;
        out[2] = z2;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), hgt = getHeight();
        if (w < 4 || hgt < 4) return;
        float cx = w * 0.5f, cy = hgt * 0.48f;
        haveProj = false;

        if (!matrix.haveFrame) {
            line.setColor(Color.argb(80, 120, 30, 40));
            line.setStrokeWidth(1f);
            c.drawLine(cx - 40, cy, cx + 40, cy, line);
            c.drawLine(cx, cy - 40, cx, cy + 40, line);
            return;
        }

        int n = matrix.n;
        float cell = 1.0f;
        float origin = -0.5f * (n - 1) * cell;
        float half = 0.5f * n * cell + 0.15f;
        // Compact rear panel: fit cage + dense lattice with margin.
        boolean compact = forceCompact || Math.min(w, hgt) < 560;
        float fit = compact ? 0.22f : 0.40f;
        float scale = Math.min(w, hgt) * fit / Math.max(half, 1f);
        float now = (System.nanoTime() - t0) / 1e9f;
        float[] rgb = new float[3];

        // outer cage (cube_gl)
        float e = half;
        float[][] corners = {
            {-e,-e,-e},{e,-e,-e},{e,e,-e},{-e,e,-e},
            {-e,-e,e},{e,-e,e},{e,e,e},{-e,e,e}
        };
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}
        };
        float pulse = 0.15f + 0.55f * globalPulse;
        line.setStrokeWidth(1.4f);
        line.setColor(Color.argb((int) ((0.25f + pulse * 0.4f) * 255), 140, 5, 12));
        for (int[] ed : edges) {
            project(corners[ed[0]][0], corners[ed[0]][1], corners[ed[0]][2], cx, cy, scale, tmp);
            float ax = tmp[0], ay = tmp[1];
            project(corners[ed[1]][0], corners[ed[1]][1], corners[ed[1]][2], cx, cy, scale, tmp);
            c.drawLine(ax, ay, tmp[0], tmp[1], line);
        }

        // lattice wires
        line.setStrokeWidth(1f);
        line.setColor(Color.argb(22, 70, 0, 8));
        for (int z = 0; z <= n; z++) {
            for (int y = 0; y <= n; y++) {
                float py = origin + (y - 0.5f) * cell;
                float pz = origin + (z - 0.5f) * cell;
                project(origin - 0.5f * cell, py, pz, cx, cy, scale, tmp);
                float ax = tmp[0], ay = tmp[1];
                project(origin + (n - 0.5f) * cell, py, pz, cx, cy, scale, tmp);
                c.drawLine(ax, ay, tmp[0], tmp[1], line);
            }
        }
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                float px = origin + (x - 0.5f) * cell;
                float pz = origin + (z - 0.5f) * cell;
                project(px, origin - 0.5f * cell, pz, cx, cy, scale, tmp);
                float ax = tmp[0], ay = tmp[1];
                project(px, origin + (n - 0.5f) * cell, pz, cx, cy, scale, tmp);
                c.drawLine(ax, ay, tmp[0], tmp[1], line);
            }
        }
        for (int y = 0; y <= n; y++) {
            for (int x = 0; x <= n; x++) {
                float px = origin + (x - 0.5f) * cell;
                float py = origin + (y - 0.5f) * cell;
                project(px, py, origin - 0.5f * cell, cx, cy, scale, tmp);
                float ax = tmp[0], ay = tmp[1];
                project(px, py, origin + (n - 0.5f) * cell, cx, cy, scale, tmp);
                c.drawLine(ax, ay, tmp[0], tmp[1], line);
            }
        }

        // project all cells for hit-test + draw nodes
        int i = 0;
        int need = n * n * n;
        for (int z = 0; z < n; z++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++, i++) {
                    float px = origin + x * cell;
                    float py = origin + y * cell;
                    float pz = origin + z * cell;
                    project(px, py, pz, cx, cy, scale, tmp);
                    screenX[i] = tmp[0];
                    screenY[i] = tmp[1];
                    screenZ[i] = tmp[2];
                }
            }
        }
        haveProj = true;

        i = 0;
        for (int z = 0; z < n; z++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++, i++) {
                    int raw = matrix.cells[i] & 0xff;
                    int d = raw % 10;
                    if (d == 0 && raw != 0) d = (raw % 9) + 1;
                    boolean neur = matrix.hasNeuron && matrix.neuron[i] != 0;
                    float imp = matrix.impulse[i];
                    boolean marked = (i == selectCell || i == hoverCell);
                    // Always paint some mass under heat — never full-skip void lattice.
                    if (d == 0 && !neur && imp < 0.08f && !marked) {
                        if ((i % 7) != 0) continue;
                        d = 3; // sparse ambient so cube still glows
                    }

                    float sx = screenX[i], sy = screenY[i];
                    digitColor(d > 0 ? d : 1, rgb);

                    if (imp > 0.05f) {
                        float flash = imp;
                        float jitter = (0.12f + 0.45f * flash) * cell;
                        float t = now * 40f + i;
                        float ox = (float) Math.sin(t) * jitter;
                        float oy = (float) Math.cos(t * 1.3f) * jitter;
                        float oz = (float) Math.sin(t * 0.7f) * jitter;
                        project(origin + x * cell + ox, origin + y * cell + oy,
                            origin + z * cell + oz, cx, cy, scale, tmp);
                        line.setStrokeWidth(2.5f);
                        line.setColor(Color.argb((int) (240 * flash), 255, 12, 20));
                        c.drawLine(sx, sy, tmp[0], tmp[1], line);
                        /* solid hot voxel */
                        float hs = 5f + 8f * flash;
                        fill.setColor(Color.argb((int) (230 * flash), 255, 30, 25));
                        c.drawRect(sx - hs, sy - hs, sx + hs, sy + hs, fill);
                    } else {
                        /* dense solid crimson (not airy dots) */
                        float af = 0.35f + 0.08f * d;
                        if (neur) af = 0.6f;
                        if (d >= 6) af = 0.85f;
                        else if (d >= 3) af = 0.55f + 0.05f * d;
                        if (marked) af = Math.min(1f, af + 0.25f);
                        int a = Math.min(255, (int) (af * 255));
                        fill.setColor(Color.argb(Math.max(70, a),
                            (int) (rgb[0] * 255), (int) (rgb[1] * 255), (int) (rgb[2] * 255)));
                        float hs = 3.5f + 1.1f * d;
                        if (d >= 6) hs = 6.5f;
                        c.drawRect(sx - hs, sy - hs, sx + hs, sy + hs, fill);
                    }
                    if (i == selectCell) {
                        float hs = 7f;
                        c.drawRect(sx - hs, sy - hs, sx + hs, sy + hs, selPaint);
                    } else if (i == hoverCell) {
                        selPaint.setAlpha(120);
                        c.drawRect(sx - 6f, sy - 6f, sx + 6f, sy + 6f, selPaint);
                        selPaint.setAlpha(220);
                    }
                }
            }
        }
    }
}
