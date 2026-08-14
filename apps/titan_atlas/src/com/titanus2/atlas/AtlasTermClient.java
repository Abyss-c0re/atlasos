package com.titanus2.atlas;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * Atlas input — thin client over TerminalView.
 *
 * Proven path (screencap 0.9.25):
 *   input text → PTY works
 *   KEYCODE_DEL via TerminalView.onKeyDown → erase works
 *   ExtraKeys must dispatch the same virtual KeyEvents (not a second write path)
 *
 * Shift (T-013 / 15.57): TitanKey / EventHub can leave META_SHIFT or CAPS stuck.
 * Atlas Shift is <b>momentary only while held</b> — no one-shot capital after bare
 * tap (that felt like Caps Lock after boot). TerminalView must not trust
 * event.isShiftPressed() alone.
 *
 * INPUT_REV bumped with every intentional input change + known_good snapshot.
 */
public final class AtlasTermClient implements TerminalViewClient, TerminalSessionClient {
    private static final String TAG = "AtlasTerm";
    public static final String INPUT_REV = "0.9.75-shift-momentary";

    public interface Host {
        void onSessionFinished(int code);
        void onAuthText(String chunk);
        Context context();
        TerminalView terminalView();
        boolean preferHardwareKeys();
    }

    private final Host host;
    private boolean stickyCtrl;
    private boolean stickyAlt;
    /** Soft ExtraKeys shift — unused for HW; kept for parity clear paths. */
    private boolean stickyShift;
    private boolean stickyFn;
    /** Physical SHIFT_LEFT/RIGHT currently down (momentary). */
    private boolean physShiftDown;
    private long physShiftDownAt;
    private boolean physShiftSawLetter;
    /**
     * Legacy one-shot flag — always false after 15.57 (momentary-only Shift).
     * Kept so clear paths still wipe residual from older sessions.
     */
    private boolean oneShotShift;
    private TerminalSession boundSession;

    public AtlasTermClient(Host host) {
        this.host = host;
    }

    public void bindSession(TerminalSession session) {
        this.boundSession = session;
        authTranscriptLen = 0;
    }

    public boolean isStickyCtrl() { return stickyCtrl; }
    public boolean isStickyAlt() { return stickyAlt; }

    public void toggleStickyCtrl() { stickyCtrl = !stickyCtrl; }
    public void toggleStickyAlt() { stickyAlt = !stickyAlt; }

    /**
     * Extra-key row: inject virtual keyboard KeyEvents into TerminalView
     * (same path as hardware / IME sendKeyEvent). Proven: KEYCODE_DEL works.
     */
    public void sendExtraKey(String key) {
        if (key == null) return;
        switch (key) {
            case "CTRL":
                toggleStickyCtrl();
                return;
            case "ALT":
                toggleStickyAlt();
                return;
            case "/":
                writeRaw("/");
                return;
            case "-":
                writeRaw("-");
                return;
            default:
                break;
        }

        int keyCode = extraKeyToKeyCode(key);
        if (keyCode != 0) {
            dispatchVirtualKey(keyCode);
            // one-shot sticky after a real key
            if (keyCode != KeyEvent.KEYCODE_CTRL_LEFT && keyCode != KeyEvent.KEYCODE_ALT_LEFT) {
                // don't clear on modifiers we don't dispatch
            }
            if (!isToggleOnly(key)) {
                // clear sticky after arrow/letter specials that used it via readControlKey
                // (TerminalView already read sticky during onKeyDown)
                stickyCtrl = false;
                stickyAlt = false;
                stickyShift = false;
            }
            return;
        }

        if (key.length() == 1) writeRaw(key);
    }

    private static boolean isToggleOnly(String key) {
        return "CTRL".equals(key) || "ALT".equals(key);
    }

    private void dispatchVirtualKey(int keyCode) {
        TerminalView v = host.terminalView();
        if (v == null) {
            // fallback raw
            fallbackWrite(keyCode);
            return;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0,
            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        v.onKeyDown(keyCode, down);
        v.onKeyUp(keyCode, up);
        v.requestFocus();
    }

    private void fallbackWrite(int keyCode) {
        TerminalSession session = boundSession;
        if (session == null || !session.isRunning()) return;
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            session.write("\u007f");
            return;
        }
        // last resort: no KeyHandler here without emulator; DEL only
    }

    private void writeRaw(String s) {
        TerminalSession session = boundSession;
        if (session == null || !session.isRunning() || s == null) return;
        if (stickyCtrl && s.length() == 1) {
            char c = Character.toLowerCase(s.charAt(0));
            if (c >= 'a' && c <= 'z') {
                session.write(new String(new char[] { (char) (c - 'a' + 1) }));
                stickyCtrl = false;
                return;
            }
        }
        if (stickyAlt && s.length() == 1) {
            session.write("\033" + s);
            stickyAlt = false;
            return;
        }
        session.write(s);
    }

    private static int extraKeyToKeyCode(String key) {
        switch (key) {
            case "ESC": return KeyEvent.KEYCODE_ESCAPE;
            case "TAB": return KeyEvent.KEYCODE_TAB;
            case "ENTER": return KeyEvent.KEYCODE_ENTER;
            case "BKSP":
            case "DEL": return KeyEvent.KEYCODE_DEL;
            case "HOME": return KeyEvent.KEYCODE_MOVE_HOME;
            case "END": return KeyEvent.KEYCODE_MOVE_END;
            case "PGUP": return KeyEvent.KEYCODE_PAGE_UP;
            case "PGDN": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "↑": return KeyEvent.KEYCODE_DPAD_UP;
            case "↓": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "→": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "←": return KeyEvent.KEYCODE_DPAD_LEFT;
            default: return 0;
        }
    }

    @Override public float onScale(float scale) { return 1.0f; }

    @Override public void onSingleTapUp(MotionEvent e) {
        TerminalView v = host.terminalView();
        if (v != null) v.requestFocus();
    }

    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        // Force erase — sticky ExtraKeys Ctrl must not turn BS into ^H.
        if (session != null && keyCode == KeyEvent.KEYCODE_DEL) {
            session.write("\u007f");
            return true;
        }
        if (isShiftKey(keyCode)) {
            if (e != null && e.getRepeatCount() == 0) {
                physShiftDown = true;
                physShiftDownAt = SystemClock.uptimeMillis();
                physShiftSawLetter = false;
                // New press cancels prior one-shot residue.
                oneShotShift = false;
                stickyShift = false;
            }
            // Consume: TerminalView must not treat Shift as a printable / stick meta.
            return true;
        }
        if (physShiftDown && isTypingLetterKey(keyCode)) {
            physShiftSawLetter = true;
        }
        // Everything else: TerminalView.handleKeyCode / inputCodePoint
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (isShiftKey(keyCode)) {
            // Momentary only: bare tap must not arm capital mode (user: Shift≠Caps).
            physShiftDown = false;
            stickyShift = false;
            oneShotShift = false;
            return true;
        }
        if (!isModifierKey(keyCode)) {
            stickyCtrl = false;
            stickyAlt = false;
            stickyShift = false;
            oneShotShift = false;
        }
        return false;
    }

    private static boolean isShiftKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    private static boolean isTypingLetterKey(int keyCode) {
        return keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z;
    }

    private static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_SYM:
            case KeyEvent.KEYCODE_NUM:
                return true;
            default:
                return false;
        }
    }

    @Override public boolean onLongPress(MotionEvent event) { return false; }

    @Override public boolean readControlKey() { return stickyCtrl; }
    @Override public boolean readAltKey() { return stickyAlt; }

    /**
     * Authoritative shift for TerminalView: <b>physical hold only</b>.
     * Never returns true from stuck KeyEvent meta or bare-tap residue.
     */
    @Override public boolean readShiftKey() {
        return physShiftDown;
    }

    @Override public boolean readFnKey() { return stickyFn; }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        if (stickyCtrl && ctrlDown && codePoint >= 1 && codePoint <= 26) stickyCtrl = false;
        if (stickyAlt && codePoint > 0) stickyAlt = false;
        // One-shot Shift is consumed by the first glyph (letter or special).
        if (oneShotShift && codePoint > 0) {
            oneShotShift = false;
            stickyShift = false;
        }
        return false;
    }

    @Override public void onEmulatorSet() {}

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "trace", e); }

    /** Last transcript length already forwarded for device-auth scrape. */
    private int authTranscriptLen;

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        TerminalView v = host.terminalView();
        if (v != null) v.onScreenUpdated();
        // Terminal text is the login UI — no host scrape / device-code bar.
    }

    public void resetAuthScrape() {
        authTranscriptLen = 0;
    }

    @Override public void onTitleChanged(TerminalSession changedSession) {}

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        // Ignore exit of an old PTY after And↔Deb switch (finishIfRunning → SIGKILL -9
        // was painting "exit -9" over a healthy new Deb session).
        if (finishedSession != null && boundSession != null
            && finishedSession != boundSession) {
            return;
        }
        int code = finishedSession != null ? finishedSession.getExitStatus() : -1;
        host.onSessionFinished(code);
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        Context c = host.context();
        if (c == null || text == null) return;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("atlas", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        Context c = host.context();
        if (c == null || session == null) return;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() < 1) return;
        CharSequence t = cd.getItemAt(0).coerceToText(c);
        if (t != null && t.length() > 0) session.write(t.toString());
    }

    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) {
        TerminalView v = host.terminalView();
        if (v != null) v.onScreenUpdated();
    }
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return null; }
}
