package com.titanus2.usbhid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import com.titanus2.usbhid.ui.UiKit;

/**
 * Full-area relative trackpad for 1440×1440.
 * 1 finger move · tap L · 2-finger tap R · 2-finger vertical scroll.
 * Double-tap: latch left button until next double-tap or HW key activity.
 */
public class SoftPadView extends View {
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastX, lastY;
    private float lastScrollY;
    private boolean tracking;
    private boolean scrollMode;
    private int buttons;
    private int activePointers;
    private int maxPointers;
    private long downMs;
    private float moved;
    private float scrollAcc;
    private final float density;
    private final float gain;
    private float accDx, accDy;
    private long lastSendMs;
    private boolean compact;
    private Runnable onActive;
    private Runnable onIdle;
    /** Left-button latch after double-tap (HID mouse). */
    private boolean leftLatch;
    private long lastTapUpMs;
    private float lastTapX, lastTapY;
    private static final long DOUBLE_TAP_MS = 320L;
    private String lastKeySig = "";
    private final android.os.Handler keyWatch =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable keyPoll = new Runnable() {
        @Override public void run() {
            if (!leftLatch) return;
            String s = keyActivitySig();
            if (!s.isEmpty() && !s.equals(lastKeySig) && keyActivityFresh(s)) {
                clearLeftLatch();
                return;
            }
            lastKeySig = s;
            keyWatch.postDelayed(this, 50);
        }
    };

    public SoftPadView(Context ctx) {
        super(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        density = dm.density;
        // Slightly higher gain on dense 1440 panels so finger travel feels natural
        float sidePx = Math.min(dm.widthPixels, dm.heightPixels);
        float base = 2.4f / Math.max(1f, density);
        // boost a bit when physical panel is large (1440)
        if (sidePx >= 1200) base *= 1.15f;
        gain = Math.max(0.4f, Math.min(1.35f, base));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(2f, density));
        // Theme colors (Cube chrome is OS-wide — do not hardcode black tiles)
        int muted = UiKit.mutedColor(ctx);
        int accent = UiKit.liveAccent(ctx);
        int body = UiKit.liveBody(ctx);
        border.setColor(muted);
        fill.setColor(body);
        guide.setColor(muted);
        guide.setStrokeWidth(1f);
        guide.setAlpha(70);
        label.setColor(muted);
        label.setTextSize(13f * density);
        label.setTextAlign(Paint.Align.CENTER);
        label2.setColor(accent);
        label2.setTextSize(11f * density);
        label2.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    /** Smaller labels for Type-tab mini pad. */
    public void setCompact(boolean c) {
        compact = c;
        if (compact) {
            label.setTextSize(11f * density);
            label2.setTextSize(9f * density);
        } else {
            label.setTextSize(13f * density);
            label2.setTextSize(11f * density);
        }
        invalidate();
    }

    /** Type tab: arm soft HID on finger down, park phys on idle. */
    public void setActivityListeners(Runnable active, Runnable idle) {
        onActive = active;
        onIdle = idle;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawRect(0, 0, w, h, fill);
        c.drawRect(1, 1, w - 1, h - 1, border);
        float cx = w / 2f, cy = h / 2f;
        float arm = (compact ? 18f : 28f) * density;
        c.drawLine(cx, cy - arm, cx, cy + arm, guide);
        c.drawLine(cx - arm, cy, cx + arm, cy, guide);
        if (h >= 48f * density) {
            c.drawText(compact ? "soft pad" : "trackpad", cx, cy - 8f * density, label);
            if (h >= 64f * density) {
                c.drawText(leftLatch
                        ? "L held · 2×tap release"
                        : "tap L · 2×tap hold · 2f scroll",
                    cx, cy + 10f * density, label2);
            }
        }
    }

    private void clearLeftLatch() {
        if (!leftLatch) return;
        leftLatch = false;
        buttons = 0;
        HidControl.mouseButtons(0);
        keyWatch.removeCallbacks(keyPoll);
        invalidate();
    }

    private void armLeftLatch() {
        leftLatch = true;
        buttons = 1;
        HidControl.mouseButtons(1);
        lastKeySig = keyActivitySig();
        keyWatch.removeCallbacks(keyPoll);
        keyWatch.postDelayed(keyPoll, 50);
        invalidate();
    }

    private static String keyActivitySig() {
        String best = "";
        long bestMt = 0;
        for (String path : new String[]{
            "/data/local/tmp/titan2_key_activity",
            "/data/misc/titan2/titan2_key_activity"
        }) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.isFile()) continue;
                long mt = f.lastModified();
                String body = new String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (body.length() < 10) continue;
                boolean dig = true;
                for (int i = 0; i < body.length(); i++) {
                    if (!Character.isDigit(body.charAt(i))) {
                        dig = false;
                        break;
                    }
                }
                if (!dig) continue;
                if (mt >= bestMt) {
                    bestMt = mt;
                    best = mt + ":" + body;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static boolean keyActivityFresh(String sig) {
        try {
            int c = sig.indexOf(':');
            if (c < 0) return false;
            String body = sig.substring(c + 1);
            long n = Long.parseLong(body);
            long act = body.length() >= 11 ? n / 1000L : n;
            long wall = System.currentTimeMillis() / 1000L;
            return wall - act <= 3;
        } catch (Exception e) {
            return false;
        }
    }

    private void flushMove(boolean force) {
        if (accDx == 0f && accDy == 0f) return;
        long now = SystemClock.uptimeMillis();
        // 4ms floor (~250 Hz) — 8ms made Type soft-pad feel lagged on BT
        if (!force && now - lastSendMs < 4) return;
        int dx = Math.round(accDx);
        int dy = Math.round(accDy);
        if (dx == 0 && dy == 0) return;
        accDx -= dx;
        accDy -= dy;
        lastSendMs = now;
        HidControl.mouseMove(dx, dy, buttons);
    }

    private void flushScroll() {
        // ~48 px per wheel notch at density ~3
        float step = 40f * density;
        while (scrollAcc >= step) {
            HidControl.send(new byte[]{0x04, 1, 0, 0});
            scrollAcc -= step;
        }
        while (scrollAcc <= -step) {
            HidControl.send(new byte[]{0x04, (byte) -1, 0, 0});
            scrollAcc += step;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                activePointers = e.getPointerCount();
                if (activePointers > maxPointers) maxPointers = activePointers;
                if (action == MotionEvent.ACTION_DOWN) {
                    tracking = true;
                    scrollMode = false;
                    maxPointers = 1;
                    lastX = e.getX();
                    lastY = e.getY();
                    lastScrollY = e.getY();
                    downMs = SystemClock.uptimeMillis();
                    moved = 0;
                    scrollAcc = 0;
                    // Keep button bit if latched (drag while held)
                    buttons = leftLatch ? 1 : 0;
                    accDx = accDy = 0;
                    if (onActive != null) {
                        try { onActive.run(); } catch (Exception ignored) {}
                    }
                } else if (activePointers >= 2) {
                    // enter scroll mode on second finger
                    scrollMode = true;
                    lastScrollY = e.getY(0);
                    // drop any pending move so we don't jump
                    accDx = accDy = 0;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (scrollMode || e.getPointerCount() >= 2) {
                    scrollMode = true;
                    float y = e.getY(0);
                    float dy = y - lastScrollY;
                    lastScrollY = y;
                    scrollAcc += dy;
                    moved += Math.abs(dy);
                    flushScroll();
                } else {
                    int hist = e.getHistorySize();
                    for (int hi = 0; hi < hist; hi++) {
                        float x = e.getHistoricalX(0, hi);
                        float y = e.getHistoricalY(0, hi);
                        float rdx = (x - lastX) * gain;
                        float rdy = (y - lastY) * gain;
                        lastX = x;
                        lastY = y;
                        moved += Math.abs(rdx) + Math.abs(rdy);
                        accDx += rdx;
                        accDy += rdy;
                    }
                    float x = e.getX(0), y = e.getY(0);
                    float rdx = (x - lastX) * gain;
                    float rdy = (y - lastY) * gain;
                    lastX = x;
                    lastY = y;
                    moved += Math.abs(rdx) + Math.abs(rdy);
                    accDx += rdx;
                    accDy += rdy;
                    flushMove(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (!scrollMode) flushMove(true);
                    else flushScroll();
                    long dt = SystemClock.uptimeMillis() - downMs;
                    boolean cancelled = action == MotionEvent.ACTION_CANCEL;
                    boolean tap = !cancelled && !scrollMode && moved < 18f * density && dt < 300;
                    if (tap && maxPointers >= 2) {
                        // Two-finger tap = right click (never latch)
                        HidControl.mouseButtons(2);
                        HidControl.mouseButtons(0);
                        if (!leftLatch) {
                            buttons = 0;
                        }
                    } else if (tap) {
                        long now = SystemClock.uptimeMillis();
                        float dx = e.getX() - lastTapX;
                        float dy = e.getY() - lastTapY;
                        boolean isDouble = lastTapUpMs > 0
                            && (now - lastTapUpMs) < DOUBLE_TAP_MS
                            && (dx * dx + dy * dy) < (48f * density) * (48f * density);
                        if (isDouble) {
                            if (leftLatch) {
                                clearLeftLatch();
                            } else {
                                armLeftLatch();
                            }
                            lastTapUpMs = 0;
                        } else if (leftLatch) {
                            // Single tap while latched: keep hold, no pulse
                            lastTapUpMs = now;
                            lastTapX = e.getX();
                            lastTapY = e.getY();
                        } else {
                            HidControl.mouseButtons(1);
                            HidControl.mouseButtons(0);
                            lastTapUpMs = now;
                            lastTapX = e.getX();
                            lastTapY = e.getY();
                        }
                    } else if (!leftLatch) {
                        buttons = 0;
                        HidControl.mouseButtons(0);
                    }
                    tracking = false;
                    scrollMode = false;
                    activePointers = 0;
                    maxPointers = 0;
                    accDx = accDy = 0;
                    scrollAcc = 0;
                    if (onIdle != null && !leftLatch) {
                        try { onIdle.run(); } catch (Exception ignored) {}
                    }
                } else {
                    activePointers = Math.max(0, e.getPointerCount() - 1);
                    if (activePointers < 2) {
                        // leave scroll mode when second finger lifts; reset move origin
                        lastX = e.getX(0);
                        lastY = e.getY(0);
                    }
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    public void clickLeft() {
        HidControl.mouseButtons(1);
        HidControl.mouseButtons(0);
    }

    public void clickRight() {
        HidControl.mouseButtons(2);
        HidControl.mouseButtons(0);
    }
}
