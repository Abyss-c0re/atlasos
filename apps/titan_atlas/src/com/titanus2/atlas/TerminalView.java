package com.titanus2.atlas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;

/**
 * C termgrid TUI.
 * Tap = mouse click. Drag = select text. Long-press = paste clipboard into PTY (nano/vim style).
 */
public class TerminalView extends View {
    public interface Host {
        void onTermInput(String data);
        void onCopyText(String text);
        void onPasteRequest();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint selPaint = new Paint();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(8192);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Host host;
    private boolean ok;
    private boolean gridReady;
    private float cellW, cellH, baseline;
    private int lastCols, lastRows;
    private final Runnable redraw = this::invalidate;

    /* selection: 0-based cell coords, inclusive */
    private int selC0 = -1, selR0 = -1, selC1 = -1, selR1 = -1;
    private boolean selecting;
    private float downX, downY;
    private long downAt;
    private boolean longPasteFired;
    private final Runnable longPaste = () -> {
        if (!selecting && !longPasteFired && host != null) {
            longPasteFired = true;
            host.onPasteRequest();
        }
    };

    public TerminalView(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF0A0A0A);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        selPaint.setColor(0x6680CBC4);
        measureCell();
    }

    public void setHost(Host h) { host = h; }

    public boolean initNative(java.io.File so) {
        ok = TermGrid.ensureLoaded(so);
        if (ok && getWidth() > 0 && getHeight() > 0) ensureGrid();
        return ok;
    }

    private void ensureGrid() {
        if (!ok || gridReady) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int cols = Math.max(40, (int) (w / cellW));
        int rows = Math.max(12, (int) (h / cellH));
        if (cols > 160) cols = 160;
        if (rows > 60) rows = 60;
        lastCols = cols;
        lastRows = rows;
        TermGrid.nativeInit(cols, rows);
        gridReady = true;
        flushPending();
    }

    private void flushPending() {
        if (!gridReady || pending.size() == 0) return;
        byte[] b = pending.toByteArray();
        pending.reset();
        TermGrid.nativeFeed(b);
    }

    public void feed(byte[] data) {
        if (!ok || data == null || data.length == 0) return;
        ensureGrid();
        if (!gridReady) {
            try { pending.write(data); } catch (Exception ignored) {}
            return;
        }
        flushPending();
        TermGrid.nativeFeed(data);
        if (TermGrid.nativeDirty()) post(redraw);
    }

    public void reset() {
        pending.reset();
        clearSelection();
        if (ok && gridReady) TermGrid.nativeReset();
        invalidate();
    }

    public void clearSelection() {
        selC0 = selR0 = selC1 = selR1 = -1;
        selecting = false;
        invalidate();
    }

    /** Visible screen as plain text (cell buffer — not raw ANSI). */
    public String screenText() {
        if (!ok || !gridReady) return "";
        int rows = TermGrid.nativeRows();
        int cols = TermGrid.nativeCols();
        StringBuilder sb = new StringBuilder(rows * (cols + 1));
        for (int y = 0; y < rows; y++) {
            int[] row = TermGrid.nativeRow(y);
            if (row == null || row.length < 1) {
                sb.append('\n');
                continue;
            }
            int n = row[0];
            int end = n;
            while (end > 0) {
                int ch = row[1 + (end - 1) * 3];
                if (ch > 0 && ch != ' ') break;
                end--;
            }
            for (int x = 0; x < end && x < cols; x++) {
                int ch = row[1 + x * 3];
                if (ch <= 0) ch = ' ';
                if (Character.isValidCodePoint(ch)) {
                    sb.appendCodePoint(ch);
                } else {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Selected region as plain text (0-based inclusive). */
    public String selectionText() {
        if (selC0 < 0 || !gridReady) return "";
        int c0 = Math.min(selC0, selC1);
        int c1 = Math.max(selC0, selC1);
        int r0 = Math.min(selR0, selR1);
        int r1 = Math.max(selR0, selR1);
        int cols = TermGrid.nativeCols();
        int rows = TermGrid.nativeRows();
        StringBuilder sb = new StringBuilder();
        for (int y = r0; y <= r1 && y < rows; y++) {
            int[] row = TermGrid.nativeRow(y);
            if (row == null) {
                sb.append('\n');
                continue;
            }
            int n = row[0];
            for (int x = c0; x <= c1 && x < cols; x++) {
                int ch = ' ';
                if (x < n) ch = row[1 + x * 3];
                if (ch <= 0) ch = ' ';
                if (Character.isValidCodePoint(ch)) sb.appendCodePoint(ch);
                else sb.append(' ');
            }
            if (y < r1) sb.append('\n');
        }
        return sb.toString();
    }

    public boolean hasSelection() {
        return selC0 >= 0 && (selC0 != selC1 || selR0 != selR1 || selecting);
    }

    private void measureCell() {
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = fm.descent - fm.ascent + 2f;
        cellW = paint.measureText("W");
        if (cellW < 1f) cellW = 10f;
        baseline = -fm.ascent + 1f;
    }

    /** 0-based cell */
    private int[] cell0(float x, float y) {
        int cols = gridReady ? TermGrid.nativeCols() : Math.max(1, lastCols);
        int rows = gridReady ? TermGrid.nativeRows() : Math.max(1, lastRows);
        int c = (int) (x / cellW);
        int r = (int) (y / cellH);
        if (c < 0) c = 0;
        if (r < 0) r = 0;
        if (c >= cols) c = cols - 1;
        if (r >= rows) r = rows - 1;
        return new int[] { c, r };
    }

    private void sendMouseClick(int col1, int row1) {
        if (host == null) return;
        /* 1-based for xterm */
        host.onTermInput("\u001b[<0;" + col1 + ";" + row1 + "M");
        host.onTermInput("\u001b[<0;" + col1 + ";" + row1 + "m");
    }

    private boolean inSelection(int c, int r) {
        if (selC0 < 0) return false;
        int c0 = Math.min(selC0, selC1);
        int c1 = Math.max(selC0, selC1);
        int r0 = Math.min(selR0, selR1);
        int r1 = Math.max(selR0, selR1);
        return r >= r0 && r <= r1 && c >= c0 && c <= c1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!ok) return super.onTouchEvent(e);
        ensureGrid();
        int[] cell = cell0(e.getX(), e.getY());
        int c = cell[0];
        int r = cell[1];
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = SystemClock.uptimeMillis();
                longPasteFired = false;
                selecting = false;
                selC0 = selC1 = c;
                selR0 = selR1 = r;
                handler.removeCallbacks(longPaste);
                handler.postDelayed(longPaste, 500);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (dx * dx + dy * dy > cellW * cellW) {
                    handler.removeCallbacks(longPaste);
                    selecting = true;
                    selC1 = c;
                    selR1 = r;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPaste);
                if (longPasteFired) {
                    clearSelection();
                    return true;
                }
                if (selecting) {
                    selC1 = c;
                    selR1 = r;
                    String t = selectionText();
                    if (host != null && t != null && !t.isEmpty()) {
                        host.onCopyText(t);
                    }
                    selecting = false;
                    invalidate();
                    return true;
                }
                /* short tap → mouse click for TUI buttons */
                clearSelection();
                sendMouseClick(c + 1, r + 1);
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPaste);
                selecting = false;
                clearSelection();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!gridReady) {
            ensureGrid();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF0A0A0A);
        if (!ok) {
            paint.setColor(0xFFFFAB91);
            canvas.drawText("termgrid native missing", 12, 40, paint);
            return;
        }
        if (!gridReady) {
            paint.setColor(0xFF90A4AE);
            canvas.drawText("…", 12, 40, paint);
            return;
        }
        int rows = TermGrid.nativeRows();
        int cols = TermGrid.nativeCols();
        for (int y = 0; y < rows; y++) {
            int[] row = TermGrid.nativeRow(y);
            if (row == null || row.length < 1) continue;
            int n = row[0];
            float py = y * cellH;
            for (int x = 0; x < cols; x++) {
                float px = x * cellW;
                if (inSelection(x, y)) {
                    canvas.drawRect(px, py, px + cellW, py + cellH, selPaint);
                }
                if (x >= n) continue;
                int ch = row[1 + x * 3];
                int fg = row[2 + x * 3];
                int bg = row[3 + x * 3];
                if (bg != 0 && (bg & 0x00FFFFFF) != 0x000A0A0A && !inSelection(x, y)) {
                    bgPaint.setColor(bg);
                    canvas.drawRect(px, py, px + cellW, py + cellH, bgPaint);
                }
                if (ch <= 0 || ch == ' ') continue;
                paint.setColor(fg != 0 ? fg : 0xFFECEFF1);
                char[] one = Character.toChars(ch);
                canvas.drawText(one, 0, one.length, px, py + baseline, paint);
            }
        }
        int cx = TermGrid.nativeCx();
        int cy = TermGrid.nativeCy();
        if (cx >= 0 && cy >= 0 && cy < rows) {
            paint.setColor(0xFF80CBC4);
            float px = cx * cellW;
            float py = cy * cellH;
            canvas.drawRect(px, py + cellH - 2f, px + cellW, py + cellH, paint);
        }
        TermGrid.nativeClearDirty();
    }
}
