package com.titanus2.cubecontact;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Prophecy lattice — OpenGL ES1.
 * Product face matches desktop <b>Cube Experience</b>
 * ({@code cubebrain/viz/cube_gl --levitate --mono --primary}): dense N=16 mono
 * lattice from <b>real</b> BrainCube/CubalC state — not Canvas densify theater.
 * Async matrix pull + snapshot; GL thread only draws.
 * Rear: cool LOD (no wire grid, point voxels, adaptive FPS).
 * Pinch zoom · drag orbit · levitate spin · manifest the prophecy.
 */
public class CubeGLView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final StateMatrix matrix = new StateMatrix();
    private final Object pullLock = new Object();
    private boolean pulling;
    private float yaw = 35f, pitch = 25f, dist = 22f;
    /** cube_gl --levitate: gentle hover + slow orbit. */
    private boolean levitate = true;
    /** Pinch zoom: multiplies camera distance (lower = closer / zoom in). */
    private float userZoom = 1f;
    private static final float ZOOM_MIN = 0.35f;
    private static final float ZOOM_MAX = 2.8f;
    /** Closer camera = larger solid cube (desktop cube_gl + status-brief framing). */
    private static final float DIST_MAIN = 16f;
    private static final float DIST_REAR = 22f;
    private static final float FIT_HALF_MAIN = 9.0f;
    /** Fill 410×502 like Nexus crimson brief — still real lattice only. */
    private static final float FIT_HALF_REAR = 7.2f;
    private float lastX, lastY;
    private boolean drag;
    private boolean multiTouch;
    private float pinchPrev = -1f;
    private long lastTapMs;
    private float lastTapX, lastTapY;
    private boolean autoSpin = true;
    private boolean forceCompact;
    private String plane = CubePlanePrefs.PLANE_FRONT;
    private float worldScale = 1f;
    private float worldOrigin = 0f;
    private long t0 = System.nanoTime();
    private float globalPulse;
    private int selectCell = -1;
    private SelectionListener listener;
    private volatile boolean attached;
    private volatile boolean interactive;
    private long lastInteractMs;
    private long lastPullMs;
    private long lastFrameMs;
    private long lastDecayMs;
    private long lastFrameNanos;
    private final android.os.Handler frameH =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private ScaleGestureDetector scaleDet;

    /** Async pull + densify off the GL thread. */
    private static final ExecutorService WORK_EX =
        Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cube-async");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            return t;
        });

    /** Double-buffer lattice for GL (read) vs worker (write). */
    private static final class Snap {
        int n;
        byte[] cells;
        byte[] neuron;
        float[] impulse;
        boolean hasNeuron;
        boolean bits; // NexusCore 512-bit lattice (0/1)
        boolean ready;
    }
    private final AtomicReference<Snap> snapRef = new AtomicReference<>();
    private final Object snapBuildLock = new Object();
    private boolean snapBuilding;

    private final float[] edgeScratch = new float[6];
    private final float[] faceScratch = new float[12];
    private FloatBuffer edgeBuf;
    private FloatBuffer faceBuf;
    private FloatBuffer pointBuf;
    private final float[] pointScratch = new float[3];
    private FloatBuffer pointBatchBuf;
    private FloatBuffer pointColorBuf;
    private final float[] pointBatchScratch = new float[StateMatrix.MAX_CELLS * 3];
    private final float[] pointColorScratch = new float[StateMatrix.MAX_CELLS * 4];

    private final Runnable frameKick = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            Context ctx = getContext();
            long now = System.currentTimeMillis();
            if (interactive && now - lastInteractMs > 2200L) interactive = false;
            boolean need = autoSpin || interactive || globalPulse > 0.02f || hasSnapImpulse();
            long iv = rearFrameMs(ctx, interactive);
            if (need || now - lastFrameMs >= iv) {
                lastFrameMs = now;
                requestRender();
            }
            frameH.postDelayed(this, iv);
        }
    };

    public interface SelectionListener {
        void onSelection(int cellIdx, String description);
    }

    public CubeGLView(Context c) { super(c); init(); }
    public CubeGLView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        try { StateMatrix.bindAppContext(getContext()); } catch (Exception ignored) {}
        scaleDet = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    // Pinch out → zoom in (smaller dist)
                    float f = d.getScaleFactor();
                    if (f > 0.01f && f < 10f) {
                        userZoom /= f;
                        if (userZoom < ZOOM_MIN) userZoom = ZOOM_MIN;
                        if (userZoom > ZOOM_MAX) userZoom = ZOOM_MAX;
                        interactive = true;
                        lastInteractMs = System.currentTimeMillis();
                        requestRender();
                    }
                    return true;
                }
            });
        setEGLContextClientVersion(1);
        try {
            // Opaque RGB888. No Z-order hacks: media-overlay + LinearLayout left a
            // black hole while status still showed live BrainCube (fake "GL works").
            setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
            setZOrderOnTop(false);
            // NEVER setBackgroundColor on GLSurfaceView — View background draws
            // AFTER the surface and paints black over the real cube (classic heresy).
            setBackgroundDrawable(null);
        } catch (Exception ignored) {}
        setRenderer(this);
        // Levitate face needs steady frames (WHEN_DIRTY alone looked frozen / empty).
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
    }

    public StateMatrix matrix() { return matrix; }
    public int selectedCell() { return selectCell; }
    public void setSelectionListener(SelectionListener l) { listener = l; }

    public void setPlane(String p) {
        plane = CubePlanePrefs.normalizePlane(p);
        matrix.setPreferredPlane(plane);
        try {
            Context ctx = getContext();
            if (ctx != null) autoSpin = CubePlanePrefs.autoSpin(ctx, plane);
        } catch (Exception ignored) {}
    }

    public String plane() { return plane; }

    public void setForceCompact(boolean v) {
        forceCompact = v;
        if (v) setPlane(CubePlanePrefs.PLANE_REAR);
        // Rear: dirty + ~30fps kick (continuous on secondary burned Mali).
        // Front: continuous levitate orbit.
        setRenderMode(v ? RENDERMODE_WHEN_DIRTY : RENDERMODE_CONTINUOUSLY);
        if (attached) {
            frameH.removeCallbacks(frameKick);
            if (v) frameH.post(frameKick);
            requestRender();
        }
    }

    public boolean isForceCompact() { return forceCompact; }

    /**
     * Pull real BrainCube/peer lattice (Cube Experience SoT), densify from
     * real vox only — not synthetic crimson-self heresy as primary.
     */
    public void manifestProphecy() {
        WORK_EX.execute(() -> {
            try {
                matrix.tryResolvePeer();
                boolean ok = matrix.refreshFromPeer();
                // Real matrix may be sparse (quiet zeros) — that is state, not void.
                // Never seed-densify on the rear/eyes path (crimson theater).
                if (!ok && !matrix.haveFrame && !forceCompact) {
                    matrix.ensureSeedFrame();
                }
                publishSnap();
            } catch (Exception ignored) {
                try {
                    if (!matrix.refreshFromPeer() && !matrix.haveFrame && !forceCompact) {
                        matrix.ensureSeedFrame();
                    }
                    publishSnap();
                } catch (Exception ignored2) {}
            }
            if (attached) frameH.post(() -> {
                if (attached) requestRender();
            });
        });
    }

    public void setLevitate(boolean on) { levitate = on; }
    public boolean isLevitate() { return levitate; }

    public String selectionText() {
        return matrix.describeCell(selectCell);
    }

    public String statusLine() {
        return matrix.statusLine()
            + " · " + (matrix.dataSource != null ? matrix.dataSource : "?");
    }

    /**
     * Rear is a 410×502 panel — never chase front density.
     * Cheap frames @ ~20–24 fps beat expensive "30 fps" stutter.
     */
    private long rearFrameMs(Context ctx, boolean interact) {
        boolean heat = CubeStability.isCubeHeat(ctx);
        if (forceCompact) {
            if (heat) return interact ? 70L : 100L;
            return interact ? 40L : 48L; // ~25 / 21 fps — each frame is tiny
        }
        if (!autoSpin && !interact) return heat ? 250L : 120L;
        if (heat) return interact ? 50L : 66L;
        return 16L; // front continuous path; kick is backup only
    }

    /**
     * Rear truth budget: real n=8 is ≤512 cells (~200 lit). Draw every lit cell.
     * Only step when n≥16 (legacy densify residual) so we never invent mass.
     */
    private static final int REAR_MAX_LIT = 512;

    private boolean hasSnapImpulse() {
        Snap s = snapRef.get();
        if (s == null || !s.ready || s.impulse == null) return false;
        int n = Math.min(s.impulse.length, 64);
        for (int i = 0; i < n; i++) if (s.impulse[i] > 0.05f) return true;
        return false;
    }

    private void publishSnap() {
        synchronized (snapBuildLock) {
            if (snapBuilding) return;
            snapBuilding = true;
        }
        try {
            if (!matrix.haveFrame) return;
            int n = matrix.n;
            int need = n * n * n;
            Snap s = new Snap();
            s.n = n;
            s.cells = new byte[need];
            s.neuron = new byte[need];
            s.impulse = new float[need];
            System.arraycopy(matrix.cells, 0, s.cells, 0, need);
            System.arraycopy(matrix.neuron, 0, s.neuron, 0, need);
            System.arraycopy(matrix.impulse, 0, s.impulse, 0, need);
            s.hasNeuron = matrix.hasNeuron;
            s.bits = matrix.isBitLattice();
            s.ready = true;
            snapRef.set(s);
        } catch (Exception ignored) {
        } finally {
            synchronized (snapBuildLock) { snapBuilding = false; }
        }
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        gl.glClearColor(0f, 0f, 0f, 1f);
        // Solid cube voxels need depth (additive-only + no depth = flat/black mess).
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDisable(GL10.GL_DITHER);
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_FASTEST);
        gl.glDisable(GL10.GL_POINT_SMOOTH);
        t0 = System.nanoTime();
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        if (h < 1) h = 1;
        gl.glViewport(0, 0, w, h);
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();
        GLU.gluPerspective(gl, forceCompact ? 48f : 50f, (float) w / (float) h, 0.5f, 200f);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
    }

    @Override public void onDrawFrame(GL10 gl) {
        // Re-stamp every frame: secondary display EGL can reset clear color.
        gl.glClearColor(0f, 0f, 0f, 1f);
        long nowMs = System.currentTimeMillis();
        Context ctx = getContext();
        try {
            if (ctx != null) autoSpin = CubePlanePrefs.autoSpin(ctx, plane);
        } catch (Exception ignored) {}

        Snap snap = snapRef.get();
        if (snap == null || !snap.ready) {
            // Prefer peer/kernel frame. Rear: honest empty, never demo seed.
            if (!matrix.haveFrame && !forceCompact) {
                try { matrix.ensureSeedFrame(); } catch (Exception ignored) {}
            }
            if (matrix.haveFrame) publishSnap();
            snap = snapRef.get();
        }

        // Async pull — never on GL thread
        if (CubeStability.allowPeerHttp(ctx)) {
            long pullEvery = CubeStability.peerPullIntervalMs(ctx);
            // Rear lattice: SMX bits must tick. 6s felt like a brick.
            if (forceCompact) pullEvery = 900L;
            if (nowMs - lastPullMs >= pullEvery) {
                lastPullMs = nowMs;
                schedulePull();
            }
        }

        // Impulse decay: rear rarely needs full mirror (saves main-thread memcpy).
        long decayEvery = forceCompact ? 250L : 100L;
        if (nowMs - lastDecayMs >= decayEvery) {
            lastDecayMs = nowMs;
            matrix.decayImpulses(forceCompact ? 0.82f : 0.88f);
            if (globalPulse > 0f) {
                globalPulse *= 0.90f;
                if (globalPulse < 0.02f) globalPulse = 0f;
            }
            if (!forceCompact && snap != null && snap.ready && snap.impulse != null) {
                int n = Math.min(snap.impulse.length, matrix.impulse.length);
                System.arraycopy(matrix.impulse, 0, snap.impulse, 0, n);
            }
        }

        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
        gl.glLoadIdentity();
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        boolean compact = forceCompact
            || (Math.min(getWidth(), getHeight()) > 0
                && Math.min(getWidth(), getHeight()) < 560);
        float baseDist = compact ? DIST_REAR : DIST_MAIN;
        dist = baseDist * userZoom;
        // Time-based orbit: smooth at adaptive FPS without continuous render.
        long nnow = System.nanoTime();
        float dt = (lastFrameNanos > 0L) ? (nnow - lastFrameNanos) / 1e9f : 0.016f;
        if (dt < 0f) dt = 0f;
        if (dt > 0.12f) dt = 0.12f;
        lastFrameNanos = nnow;
        float nowSec = (System.nanoTime() - t0) / 1e9f;
        // cube_gl --levitate: slow continuous orbit even under heat (glorious face).
        if ((autoSpin || levitate) && !drag && !multiTouch) {
            // Rear: gentler spin (less motion blur feel + cheaper look)
            float spinDps = CubeStability.isCubeHeat(ctx)
                ? (compact ? 16f : 20f)
                : (compact ? 22f : 28f);
            if (levitate && !compact) spinDps += 6f;
            else if (levitate) spinDps += 3f;
            yaw += spinDps * dt;
            if (yaw > 360f) yaw -= 360f;
            if (yaw < 0f) yaw += 360f;
        }
        float levY = levitate ? (0.12f * (float) Math.sin(nowSec * 1.15f)) : 0f;
        float cy = (float) Math.cos(Math.toRadians(yaw));
        float sy = (float) Math.sin(Math.toRadians(yaw));
        float cp = (float) Math.cos(Math.toRadians(pitch));
        float sp = (float) Math.sin(Math.toRadians(pitch));
        float ex = dist * cp * sy;
        float ey = dist * sp + levY;
        float ez = dist * cp * cy;
        GLU.gluLookAt(gl, ex, ey, ez, 0, levY * 0.35f, 0, 0, 1, 0);

        if (snap != null && snap.ready) {
            drawProphecy(gl, compact, snap, CubeStability.isCubeHeat(ctx));
        }
    }

    private void digitColor(int d, float[] rgb) {
        /* Hotter crimson — match desktop cube_gl dense prophecy. */
        float v = (d < 0 ? 0 : (d > 9 ? 9 : d)) / 9f;
        rgb[0] = 0.70f + 0.30f * v;
        rgb[1] = 0.03f + 0.10f * v;
        rgb[2] = 0.04f + 0.06f * v;
    }

    private void ensureBufs() {
        if (edgeBuf == null) {
            ByteBuffer bb = ByteBuffer.allocateDirect(6 * 4);
            bb.order(ByteOrder.nativeOrder());
            edgeBuf = bb.asFloatBuffer();
        }
        if (faceBuf == null) {
            ByteBuffer bb = ByteBuffer.allocateDirect(12 * 4);
            bb.order(ByteOrder.nativeOrder());
            faceBuf = bb.asFloatBuffer();
        }
        if (pointBuf == null) {
            ByteBuffer bb = ByteBuffer.allocateDirect(3 * 4);
            bb.order(ByteOrder.nativeOrder());
            pointBuf = bb.asFloatBuffer();
        }
        if (pointBatchBuf == null) {
            ByteBuffer bb = ByteBuffer.allocateDirect(StateMatrix.MAX_CELLS * 3 * 4);
            bb.order(ByteOrder.nativeOrder());
            pointBatchBuf = bb.asFloatBuffer();
        }
        if (pointColorBuf == null) {
            ByteBuffer bb = ByteBuffer.allocateDirect(StateMatrix.MAX_CELLS * 4 * 4);
            bb.order(ByteOrder.nativeOrder());
            pointColorBuf = bb.asFloatBuffer();
        }
    }

    private void schedulePull() {
        synchronized (pullLock) {
            if (pulling) return;
            pulling = true;
        }
        WORK_EX.execute(() -> {
            try {
                boolean ok = matrix.refreshFromPeer();
                if (ok) CubeStability.notePeerOk();
                else CubeStability.notePeerFail();
                publishSnap();
            } catch (Exception e) {
                CubeStability.notePeerFail();
            } finally {
                synchronized (pullLock) { pulling = false; }
            }
            if (attached) frameH.post(() -> {
                if (attached) requestRender();
            });
        });
    }

    private void drawEdge(GL10 gl, float x0, float y0, float z0,
                          float x1, float y1, float z1,
                          float r, float g, float b, float a, float w) {
        ensureBufs();
        gl.glLineWidth(w);
        gl.glColor4f(r, g, b, a);
        edgeScratch[0] = x0; edgeScratch[1] = y0; edgeScratch[2] = z0;
        edgeScratch[3] = x1; edgeScratch[4] = y1; edgeScratch[5] = z1;
        edgeBuf.clear();
        edgeBuf.put(edgeScratch);
        edgeBuf.position(0);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, edgeBuf);
        gl.glDrawArrays(GL10.GL_LINES, 0, 2);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
    }

    private void drawBox(GL10 gl, float x, float y, float z, float s,
                         float r, float g, float b, float a, boolean cheap) {
        ensureBufs();
        float h = s * 0.5f;
        gl.glColor4f(r, g, b, a);
        if (cheap) {
            // 2 faces only — cool LOD for rear dense lattice
            float[][] faces = {
                {x-h,y-h,z+h, x+h,y-h,z+h, x+h,y+h,z+h, x-h,y+h,z+h},
                {x-h,y-h,z-h, x-h,y+h,z-h, x+h,y+h,z-h, x+h,y-h,z-h},
            };
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
            for (float[] f : faces) {
                System.arraycopy(f, 0, faceScratch, 0, 12);
                faceBuf.clear();
                faceBuf.put(faceScratch);
                faceBuf.position(0);
                gl.glVertexPointer(3, GL10.GL_FLOAT, 0, faceBuf);
                gl.glDrawArrays(GL10.GL_TRIANGLE_FAN, 0, 4);
            }
            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
            return;
        }
        float[][] faces = {
            {x-h,y-h,z+h, x+h,y-h,z+h, x+h,y+h,z+h, x-h,y+h,z+h},
            {x-h,y-h,z-h, x-h,y+h,z-h, x+h,y+h,z-h, x+h,y-h,z-h},
            {x-h,y+h,z-h, x-h,y+h,z+h, x+h,y+h,z+h, x+h,y+h,z-h},
            {x-h,y-h,z-h, x+h,y-h,z-h, x+h,y-h,z+h, x-h,y-h,z+h},
            {x+h,y-h,z-h, x+h,y+h,z-h, x+h,y+h,z+h, x+h,y-h,z+h},
            {x-h,y-h,z-h, x-h,y-h,z+h, x-h,y+h,z+h, x-h,y+h,z-h},
        };
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        for (float[] f : faces) {
            System.arraycopy(f, 0, faceScratch, 0, 12);
            faceBuf.clear();
            faceBuf.put(faceScratch);
            faceBuf.position(0);
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, faceBuf);
            gl.glDrawArrays(GL10.GL_TRIANGLE_FAN, 0, 4);
        }
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
    }

    private void drawPoint(GL10 gl, float x, float y, float z, float sz,
                           float r, float g, float b, float a) {
        ensureBufs();
        gl.glPointSize(Math.max(1.5f, sz * 28f));
        gl.glColor4f(r, g, b, a);
        pointScratch[0] = x; pointScratch[1] = y; pointScratch[2] = z;
        pointBuf.clear();
        pointBuf.put(pointScratch);
        pointBuf.position(0);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, pointBuf);
        gl.glDrawArrays(GL10.GL_POINTS, 0, 1);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
    }

    /**
     * Manifest the prophecy: cage + dense crimson field.
     * Rear (compact): hard budget — cage + ≤REAR_MAX_POINTS in <b>one</b> draw.
     * Front: solid boxes (Experience); haze cheap 2-face, cores full.
     */
    private void drawProphecy(GL10 gl, boolean compact, Snap snap, boolean heat) {
        int n = snap.n;
        if (n < 2) return;
        float targetHalf = compact ? FIT_HALF_REAR : FIT_HALF_MAIN;
        float rawHalf = 0.5f * n + 0.15f;
        float scale = targetHalf / Math.max(rawHalf, 1f);
        float origin = -0.5f * (n - 1) * scale;
        worldScale = scale;
        worldOrigin = origin;
        float half = 0.5f * n * scale + 0.15f * scale;
        float now = (System.nanoTime() - t0) / 1e9f;
        float[] rgb = new float[3];

        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
        gl.glDisable(GL10.GL_CULL_FACE);

        // Outer cage (12 edges — always; cube silhouette for pennies)
        float e = half;
        float[][] corners = {
            {-e,-e,-e},{e,-e,-e},{e,e,-e},{-e,e,-e},
            {-e,-e,e},{e,-e,e},{e,e,e},{-e,e,e}
        };
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}
        };
        float pulse = 0.25f + 0.55f * globalPulse;
        for (int ei = 0; ei < edges.length; ei++) {
            int[] ed = edges[ei];
            float[] a = corners[ed[0]], b = corners[ed[1]];
            drawEdge(gl, a[0], a[1], a[2], b[0], b[1], b[2],
                0.85f, 0.05f, 0.08f, 0.45f + pulse * 0.4f,
                compact ? 1.4f : 1.6f);
        }

        // Lattice (rear always; front when SMX bits). Solid boxes were a brick.
        if (compact || snap.bits) {
            drawRearTruth(gl, snap, origin, scale, heat);
            return;
        }

        // ── FRONT: solid voxels (Experience) ──
        float szMul = 1.35f;
        int step = heat ? 2 : 1;
        gl.glDepthMask(true);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        ensureBufs();
        int i = 0;
        boolean anySpike = false;
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++, i++) {
                    if (i >= snap.cells.length) continue;
                    int raw = snap.cells[i] & 0xff;
                    int d = raw % 10;
                    if (d == 0 && raw != 0) d = (raw % 9) + 1;
                    if (d == 0 && raw == 0) d = 0;
                    boolean neur = snap.hasNeuron && snap.neuron[i] != 0;
                    float imp = snap.impulse[i];
                    if (d == 0 && !neur && imp < 0.08f && i != selectCell) continue;
                    boolean hot = neur || imp > 0.05f || d >= 6 || i == selectCell;
                    if (step > 1 && !hot && ((x + y + z) % step) != 0) continue;
                    float px = origin + x * scale;
                    float py = origin + y * scale;
                    float pz = origin + z * scale;
                    digitColor(d > 0 ? d : 1, rgb);
                    if (imp > 0.05f) {
                        anySpike = true;
                        float flash = imp;
                        drawBox(gl, px, py, pz, (0.55f + 0.35f * flash) * szMul,
                            1f, 0.12f, 0.08f, 0.95f * flash, false);
                        if (!heat) {
                            float jitter = 0.10f + 0.35f * flash;
                            float tt = now * 36f + i;
                            drawEdge(gl, px, py, pz,
                                px + (float) Math.sin(tt) * jitter,
                                py + (float) Math.cos(tt * 1.3f) * jitter,
                                pz + (float) Math.sin(tt * 0.7f) * jitter,
                                1f, 0.05f, 0.08f, 0.90f * flash, 2.0f);
                        }
                    } else {
                        float a = 0.45f + 0.08f * d;
                        float sz = (0.52f + 0.07f * d) * szMul;
                        if (neur) {
                            a = 0.75f;
                            sz = 0.62f * szMul;
                            rgb[0] = 0.95f; rgb[1] = 0.06f; rgb[2] = 0.08f;
                        }
                        if (d >= 6) {
                            a = 0.75f + 0.02f * d;
                            sz = 0.72f * szMul;
                            rgb[0] = 1f; rgb[1] = 0.08f; rgb[2] = 0.06f;
                        } else if (d >= 3) {
                            a = 0.5f + 0.05f * d;
                            sz = 0.6f * szMul;
                        }
                        if (i == selectCell) a = Math.min(1f, a + 0.35f);
                        if (a > 0.98f) a = 0.98f;
                        drawBox(gl, px, py, pz, sz, rgb[0], rgb[1], rgb[2], a, !hot);
                    }
                }
        if (anySpike) globalPulse = Math.max(globalPulse, 0.55f);
        gl.glDepthMask(true);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Subdisplay = lattice, not a brick. NexusCore law: 8³ bits, quiet zeros
     * stay empty, layers have meaning, hive XOR is flow. Fat boxes were heresy.
     */
    private void drawRearTruth(GL10 gl, Snap snap, float origin, float scale,
                               boolean heat) {
        ensureBufs();
        int n = snap.n;
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        gl.glDepthMask(false);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE);

        // Slice wires — you see through the volume.
        float lo = origin - 0.5f * scale;
        float hi = origin - 0.5f * scale + n * scale;
        int stepW = (n >= 16) ? 2 : 1;
        for (int k = 0; k <= n; k += stepW) {
            float p = origin - 0.5f * scale + k * scale;
            float wa = 0.10f;
            drawEdge(gl, lo, lo, p, hi, lo, p, 0.55f, 0.06f, 0.08f, wa, 1f);
            drawEdge(gl, hi, lo, p, hi, hi, p, 0.55f, 0.06f, 0.08f, wa, 1f);
            drawEdge(gl, hi, hi, p, lo, hi, p, 0.55f, 0.06f, 0.08f, wa, 1f);
            drawEdge(gl, lo, hi, p, lo, lo, p, 0.55f, 0.06f, 0.08f, wa, 1f);
        }

        int pts = 0;
        int i = 0;
        boolean anyFlow = false;
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++, i++) {
                    if (i >= snap.cells.length) continue;
                    int raw = snap.cells[i] & 0xff;
                    boolean on = raw != 0;
                    boolean hive = snap.hasNeuron && snap.neuron[i] != 0;
                    float imp = i < snap.impulse.length ? snap.impulse[i] : 0f;
                    if (!on && !hive && imp < 0.12f) continue;
                    float px = origin + x * scale;
                    float py = origin + y * scale;
                    float pz = origin + z * scale;
                    // Nexus layers: z0 services, z1 mesh, z6 beacon, z7 frame, else field.
                    float r, g, b, a;
                    if (z == 0) { r = 1f; g = 0.78f; b = 0.22f; a = 0.95f; }
                    else if (z == 1) { r = 1f; g = 0.45f; b = 0.12f; a = 0.90f; }
                    else if (z == 6) { r = 0.55f; g = 0.85f; b = 1f; a = 0.95f; }
                    else if (z == 7) { r = 1f; g = 0.15f; b = 0.12f; a = 0.88f; }
                    else { r = 0.95f; g = 0.08f; b = 0.10f; a = 0.82f; }
                    if (!on && hive) {
                        r = 0.25f; g = 0.75f; b = 1f; a = 0.55f;
                    }
                    if (imp > 0.2f) {
                        anyFlow = true;
                        a = 1f;
                        r = 1f; g = 0.35f; b = 0.15f;
                    }
                    int o = pts * 3;
                    pointBatchScratch[o] = px;
                    pointBatchScratch[o + 1] = py;
                    pointBatchScratch[o + 2] = pz;
                    int c = pts * 4;
                    pointColorScratch[c] = r;
                    pointColorScratch[c + 1] = g;
                    pointColorScratch[c + 2] = b;
                    pointColorScratch[c + 3] = a;
                    pts++;
                    if (pts >= StateMatrix.MAX_CELLS) break;
                }
        if (pts > 0) {
            pointBatchBuf.clear();
            pointBatchBuf.put(pointBatchScratch, 0, pts * 3);
            pointBatchBuf.position(0);
            pointColorBuf.clear();
            pointColorBuf.put(pointColorScratch, 0, pts * 4);
            pointColorBuf.position(0);
            gl.glPointSize(heat ? 5.5f : 7.5f);
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, pointBatchBuf);
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, pointColorBuf);
            gl.glDrawArrays(GL10.GL_POINTS, 0, pts);
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY);
            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        }
        if (anyFlow) globalPulse = Math.max(globalPulse, 0.7f);
        gl.glDepthMask(true);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            getParent().requestDisallowInterceptTouchEvent(true);
        } catch (Exception ignored) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                requestUnbufferedDispatch(e);
            }
        } catch (Exception ignored) {}
        requestFocus();
        // Always feed ScaleGestureDetector first (pinch).
        if (scaleDet != null) {
            try { scaleDet.onTouchEvent(e); } catch (Exception ignored) {}
        }
        int action = e.getActionMasked();
        int count = e.getPointerCount();
        long now = System.currentTimeMillis();

        // Manual pinch (more reliable on small rear panel than SGD alone).
        if (count >= 2) {
            multiTouch = true;
            drag = false;
            interactive = true;
            lastInteractMs = now;
            float dist = pinchSpacing(e);
            if (action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_DOWN) {
                pinchPrev = dist;
            } else if (action == MotionEvent.ACTION_MOVE && pinchPrev > 8f && dist > 8f) {
                float ratio = pinchPrev / dist; // out → zoom in
                if (ratio > 0.5f && ratio < 2f) {
                    userZoom *= ratio;
                    if (userZoom < ZOOM_MIN) userZoom = ZOOM_MIN;
                    if (userZoom > ZOOM_MAX) userZoom = ZOOM_MAX;
                    requestRender();
                }
                pinchPrev = dist;
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                multiTouch = false;
                pinchPrev = -1f;
                drag = true;
                interactive = true;
                lastInteractMs = now;
                lastX = e.getX();
                lastY = e.getY();
                // Double-tap → zoom in/out (pinch fallback)
                if (now - lastTapMs < 320L
                        && Math.abs(e.getX() - lastTapX) < 48f
                        && Math.abs(e.getY() - lastTapY) < 48f) {
                    if (userZoom > 0.85f) userZoom = 0.5f;
                    else userZoom = 1.15f;
                    lastTapMs = 0;
                    requestRender();
                    return true;
                }
                lastTapMs = now;
                lastTapX = e.getX();
                lastTapY = e.getY();
                pickByScreen(e.getX(), e.getY());
                requestRender();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                drag = false;
                if (count >= 2) pinchPrev = pinchSpacing(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch || count >= 2) {
                    multiTouch = true;
                    return true;
                }
                if (drag && (scaleDet == null || !scaleDet.isInProgress())) {
                    float dx = e.getX() - lastX;
                    float dy = e.getY() - lastY;
                    // Sensitive orbit on small rear (410×502)
                    float sens = forceCompact ? 0.85f : 0.55f;
                    if (Math.abs(dx) + Math.abs(dy) > 0.8f) {
                        interactive = true;
                        lastInteractMs = now;
                        yaw += dx * sens;
                        pitch += dy * sens;
                        if (pitch > 89f) pitch = 89f;
                        if (pitch < -89f) pitch = -89f;
                        lastX = e.getX();
                        lastY = e.getY();
                        requestRender();
                    }
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (count <= 2) {
                    multiTouch = false;
                    pinchPrev = -1f;
                    // Remaining finger continues orbit
                    int idx = e.getActionIndex() == 0 ? 1 : 0;
                    if (count >= 2 && idx < count) {
                        lastX = e.getX(idx);
                        lastY = e.getY(idx);
                        drag = true;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = false;
                multiTouch = false;
                pinchPrev = -1f;
                return true;
        }
        return true;
    }

    private static float pinchSpacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void pickByScreen(float sx, float sy) {
        Snap snap = snapRef.get();
        if (snap == null || !snap.ready) return;
        int n = snap.n;
        float scale = worldScale > 0.01f ? worldScale : 1f;
        float origin = worldOrigin;
        float u = (sx / Math.max(1, getWidth())) * 2f - 1f;
        float v = 1f - (sy / Math.max(1, getHeight())) * 2f;
        float best = 1e9f;
        int bi = -1;
        float cy = (float) Math.cos(Math.toRadians(yaw));
        float syA = (float) Math.sin(Math.toRadians(yaw));
        float cp = (float) Math.cos(Math.toRadians(pitch));
        float sp = (float) Math.sin(Math.toRadians(pitch));
        int i = 0;
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++, i++) {
                    float px = origin + x * scale;
                    float py = origin + y * scale;
                    float pz = origin + z * scale;
                    float x1 = px * cy - pz * syA;
                    float z1 = px * syA + pz * cy;
                    float y1 = py * cp - z1 * sp;
                    float z2 = py * sp + z1 * cp;
                    if (z2 + dist < 0.1f) continue;
                    float sxn = x1 / (z2 + dist * 0.05f);
                    float syn = y1 / (z2 + dist * 0.05f);
                    float d = (sxn - u) * (sxn - u) + (syn - v) * (syn - v);
                    if (d < best) { best = d; bi = i; }
                }
        float thresh = forceCompact ? 0.85f : 0.55f;
        if (bi >= 0 && best < thresh) {
            selectCell = bi;
            if (bi < matrix.impulse.length && matrix.impulse[bi] < 0.3f) {
                matrix.impulse[bi] = 0.7f;
            }
            globalPulse = 1f;
            if (listener != null)
                listener.onSelection(bi, matrix.describeCell(bi));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        matrix.setPreferredPlane(plane);
        manifestProphecy();
        onResume();
        frameH.removeCallbacks(frameKick);
        frameH.post(frameKick);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        frameH.removeCallbacks(frameKick);
        onPause();
        super.onDetachedFromWindow();
    }
}
