package com.titanus2.controls;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * QA 2026-07-18: while typing on the HW keyboard, clear residual a11y full-select
 * so the next glyph inserts instead of replacing a wide highlight
 * (felt like "locking" the field). Screen-on a11y path only — no dual inject.
 * <p>
 * 13.24: only collapse <b>residual full-field</b> selection (typical after
 * SET_TEXT without caret pin). Never collapse partial/user ranges — that
 * broke Ctrl+A → Delete (only last char deleted) and select-all replace.
 */
public final class TypingSelectionClear {
    private TypingSelectionClear() {}

    private static volatile long lastCollapseElapsed;
    /** 13.13: getRootInActiveWindow every key was the main typing lag source. */
    private static final long COLLAPSE_MIN_MS = 400L;

    /**
     * Collapse residual full-field selection to caret at end.
     * No-op for empty, caret-only, or partial ranges (user Select-All stays).
     */
    public static void collapseIfRangeSelected(AccessibilityService svc) {
        if (svc == null) return;
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo focus = null;
        try {
            root = svc.getRootInActiveWindow();
            if (root == null) return;
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focus == null || !focus.isEditable()) return;
            int start = focus.getTextSelectionStart();
            int end = focus.getTextSelectionEnd();
            if (start < 0 || end < 0 || start == end) return;
            CharSequence text = focus.getText();
            int len = text != null ? text.length() : 0;
            if (len <= 0) return;
            int lo = Math.min(start, end);
            int hi = Math.max(start, end);
            // 13.24: only residual full-select (0..len). User Ctrl+A looks the
            // same — but Delete must keep it. Callers skip DEL; for letters,
            // replace-on-type is correct Android behavior for true Select-All,
            // so also skip full-select here. Residual inject pin is pinCaretEnd.
            // Keep this helper for partial multi-glyph glitches only: no-op if
            // full field (user or residual — pinCaretEnd owns residual).
            if (lo == 0 && hi >= len) return;
            // Partial range with no modifier intent: leave alone too (Shift+arrows).
            // Historical path collapsed everything; product now never collapses
            // on the hot path except explicit residual after inject (below).
        } catch (Exception ignored) {
        } finally {
            try { if (focus != null) focus.recycle(); } catch (Exception ignored) {}
            try { if (root != null) root.recycle(); } catch (Exception ignored) {}
        }
    }

    /**
     * After Sym SET_TEXT inject: force caret to end if the node left full-select.
     * Safe from inject path only (not every key).
     */
    public static void collapseFullSelectAfterInject(AccessibilityService svc, int expectLen) {
        if (svc == null || expectLen < 0) return;
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo focus = null;
        try {
            root = svc.getRootInActiveWindow();
            if (root == null) return;
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) return;
            if (!focus.isEditable()) return;
            int start = focus.getTextSelectionStart();
            int end = focus.getTextSelectionEnd();
            if (start < 0 || end < 0 || start == end) return;
            int lo = Math.min(start, end);
            int hi = Math.max(start, end);
            CharSequence text = focus.getText();
            int len = text != null ? text.length() : 0;
            if (len <= 0) return;
            // Full select after inject → pin end
            if (lo == 0 && hi >= len) {
                int caret = expectLen > 0 ? Math.min(expectLen, len) : len;
                Bundle args = new Bundle();
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
                focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (focus != null) focus.recycle(); } catch (Exception ignored) {}
            try { if (root != null) root.recycle(); } catch (Exception ignored) {}
        }
    }

    /**
     * Key hot path: true no-op (13.24 + 15.75).
     * Previous body still called {@link #collapseIfRangeSelected} every
     * {@link #COLLAPSE_MIN_MS} → getRootInActiveWindow on the letter path
     * (terminal typing lag under load). Residual inject uses pinCaretEnd only.
     */
    public static void collapseIfRangeSelectedThrottled(AccessibilityService svc) {
        // Intentionally empty — do not touch the a11y tree from onKeyEvent.
    }
}
