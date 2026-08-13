package com.titanus2.atlas;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.terminal.TerminalColors;
import com.termux.view.TerminalView;

/** Apply user font size + fg/bg/cursor colors to Termux view + emulator. */
public final class TermTheme {
    private TermTheme() {}

    public static void applyScheme(Context c) {
        int fg = AtlasPrefs.fgColor(c);
        int bg = AtlasPrefs.bgColor(c);
        int cur = AtlasPrefs.cursorColor(c);
        int[] d = TerminalColors.COLOR_SCHEME.mDefaultColors;
        d[TextStyle.COLOR_INDEX_FOREGROUND] = fg;
        d[TextStyle.COLOR_INDEX_BACKGROUND] = bg;
        d[TextStyle.COLOR_INDEX_CURSOR] = cur;
        d[0] = bg;
        d[7] = fg;
        d[15] = fg;
    }

    public static void applyToView(Context c, TerminalView termView, View chromeRoot) {
        applyScheme(c);
        if (termView == null) return;
        int sp = AtlasPrefs.fontSp(c);
        int px = Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics()));
        if (px < 8) px = 8;
        termView.setTextSize(px);
        termView.setBackgroundColor(AtlasPrefs.bgColor(c));
        if (chromeRoot != null) {
            chromeRoot.setBackgroundColor(AtlasPrefs.bgColor(c));
        }
        termView.invalidate();
    }

    public static void applyToSession(Context c, TerminalSession session, TerminalView termView) {
        if (session == null) return;
        applyScheme(c);
        TerminalEmulator em = session.getEmulator();
        if (em != null) {
            int fg = AtlasPrefs.fgColor(c);
            int bg = AtlasPrefs.bgColor(c);
            int cur = AtlasPrefs.cursorColor(c);
            em.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg;
            em.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg;
            em.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cur;
            try {
                session.onColorsChanged();
            } catch (Exception ignored) {
            }
        }
        if (termView != null) {
            termView.setBackgroundColor(AtlasPrefs.bgColor(c));
            termView.onScreenUpdated();
            termView.invalidate();
        }
    }

    public static void styleChromeText(Context c, TextView tv) {
        if (tv == null) return;
        tv.setTextColor(AtlasUi.chromeOnTerm(c));
    }
}
