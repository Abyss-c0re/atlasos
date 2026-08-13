package com.titanus2.ime;

import android.inputmethodservice.InputMethodService;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Product system IME — hardware keyboard first (Titan 2).
 * Phase 2: map HW keys via KeyCharacterMap into the focused editor.
 * Spec: docs/project/TITAN_IME.md
 */
public class TitanInputMethodService extends InputMethodService {

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) {
            return true;
        }
        if (tryCommitKey(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handledKey(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean tryCommitKey(int keyCode, KeyEvent event) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            ic.deleteSurroundingText(1, 0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && (ei.imeOptions & EditorInfo.IME_MASK_ACTION) != 0) {
                ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
            } else {
                ic.commitText("\n", 1);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            ic.commitText(" ", 1);
            return true;
        }
        if (!handledKey(keyCode)) {
            return false;
        }
        KeyCharacterMap kcm = event.getKeyCharacterMap();
        if (kcm == null) {
            kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        }
        int c = kcm.get(keyCode, event.getMetaState());
        if (c == 0) {
            return false;
        }
        if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return false;
        }
        ic.commitText(String.valueOf((char) c), 1);
        return true;
    }

    private static boolean handledKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DEL
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE) {
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_GRAVE:
                return true;
            default:
                return false;
        }
    }
}
