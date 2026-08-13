package com.sinux.pocketboard.input.handler;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import com.sinux.pocketboard.PocketBoardIME;
import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.mapping.SymPadKeyAction;
import com.sinux.pocketboard.input.mapping.SymPadKeyMapping;
import com.sinux.pocketboard.input.mapping.SymPadKeyMappingValue;
import com.sinux.pocketboard.input.mapping.SymPadMappingManager;
import com.sinux.pocketboard.utils.InputUtils;
import com.sinux.pocketboard.utils.ToastMessageUtils;

import java.util.List;

public class SymPadInputHandler {

    private final PocketBoardIME pocketBoardIME;
    private final SymPadMappingManager mappingManager;
    private final SparseArray<Boolean> pressedOriginalKeyCodes;

    private AudioManager audioManager;
    private boolean isShiftPressed;
    private boolean isAltPressed;

    @SuppressLint("UseSparseArrays")
    public SymPadInputHandler(PocketBoardIME pocketBoardIME) {
        this.pocketBoardIME = pocketBoardIME;
        mappingManager = SymPadMappingManager.getInstance(pocketBoardIME);
        pressedOriginalKeyCodes = new SparseArray<>(30);
    }

    public boolean hasPressedKey(int originalKeyCode) {
        return pressedOriginalKeyCodes.get(originalKeyCode) != null;
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event, InputConnection inputConnection) {
        if (pressedOriginalKeyCodes.get(event.getKeyCode()) == null)
            pressedOriginalKeyCodes.put(event.getKeyCode(), false);

        SymPadKeyMapping keyMapping = mappingManager.getCurrentMapping().getKeyMapping(keyCode);
        if (keyMapping == null)
            return true;

        if (keyMapping.longPress() != null) {
            var longPressAction = keyMapping.longPress().action();
            if (event.getRepeatCount() == 1 && longPressAction != SymPadKeyAction.KEYS) {
                // Long press (first for app or text action)
                pressedOriginalKeyCodes.put(event.getKeyCode(), true);
                if (longPressAction == SymPadKeyAction.APP) {
                    launchApp(keyMapping.longPress());
                } else if (longPressAction == SymPadKeyAction.TEXT) {
                    insertText(keyMapping.longPress(), inputConnection);
                }
            } else if (event.getRepeatCount() >= 1 && longPressAction == SymPadKeyAction.KEYS) {
                // Long press (first and repeat for keys action)
                pressedOriginalKeyCodes.put(event.getKeyCode(), true);
                handleKeyDownInternal(keyMapping.longPress(), event, inputConnection);
            }
        } else {
            var shortPressAction = keyMapping.shortPress().action();
            if (event.getRepeatCount() == 0 && shortPressAction != SymPadKeyAction.KEYS) {
                // Short press (first for app or text action)
                if (shortPressAction == SymPadKeyAction.APP) {
                    launchApp(keyMapping.shortPress());
                } else if (shortPressAction == SymPadKeyAction.TEXT) {
                    insertText(keyMapping.shortPress(), inputConnection);
                }
            } else if (keyMapping.shortPress().action() == SymPadKeyAction.KEYS) {
                // Short press (first and repeat for keys action)
                handleKeyDownInternal(keyMapping.shortPress(), event, inputConnection);
            }
        }

        return true;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event, InputConnection inputConnection) {
        Boolean isLongPressed = pressedOriginalKeyCodes.get(event.getKeyCode());
        if (isLongPressed == null) {
            return false;
        } else {
            pressedOriginalKeyCodes.delete(event.getKeyCode());
        }

        SymPadKeyMapping keyMapping = mappingManager.getCurrentMapping().getKeyMapping(keyCode);
        if (keyMapping == null)
            return true;

        if (keyMapping.longPress() != null) {
            var longPressAction = keyMapping.longPress().action();
            if (isLongPressed && longPressAction == SymPadKeyAction.KEYS) {
                // Long press up for keys action
                handleKeyUpInternal(keyMapping.longPress(), event, inputConnection);
            } else if (!isLongPressed && keyMapping.shortPress() != null) {
                var shortPressAction = keyMapping.shortPress().action();
                if (shortPressAction != SymPadKeyAction.KEYS) {
                    // Launch app/insert text after short press of key if long press not fired
                    if (shortPressAction == SymPadKeyAction.APP) {
                        launchApp(keyMapping.shortPress());
                    } else if (shortPressAction == SymPadKeyAction.TEXT) {
                        insertText(keyMapping.shortPress(), inputConnection);
                    }
                } else {
                    // Short press down and up of key if long press not fired
                    handleKeyDownInternal(keyMapping.shortPress(), event, inputConnection);
                    handleKeyUpInternal(keyMapping.shortPress(), event, inputConnection);
                }
            }
        } else {
            // Short press up
            if (keyMapping.shortPress().action() == SymPadKeyAction.KEYS)
                handleKeyUpInternal(keyMapping.shortPress(), event, inputConnection);
        }

        return true;
    }

    private void launchApp(SymPadKeyMappingValue keyMappingValue) {
        // Request overlay permission if not granted
        if (!Settings.canDrawOverlays(pocketBoardIME)) {
            ToastMessageUtils.showMessage(pocketBoardIME, R.string.sympad_overlay_permission_required);
            Intent permissionIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + pocketBoardIME.getPackageName())
            );
            permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pocketBoardIME.startActivity(permissionIntent);
        }

        Intent intent = pocketBoardIME.getPackageManager().getLaunchIntentForPackage(keyMappingValue.appPackage());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pocketBoardIME.startActivity(intent);
        } else {
            ToastMessageUtils.showMessage(pocketBoardIME, R.string.sympad_app_not_found);
        }
    }

    private void insertText(SymPadKeyMappingValue keyMappingValue, InputConnection inputConnection) {
        if (inputConnection != null)
            inputConnection.commitText(keyMappingValue.text(), 1);
    }

    protected void handleKeyDownInternal(SymPadKeyMappingValue keyMappingValue, KeyEvent originalEvent, InputConnection inputConnection) {
        if (hasMediaKeys(keyMappingValue.keyCodes())) {
            dispatchMediaKeyEvent(keyMappingValue.keyCodes(), originalEvent, KeyEvent.ACTION_DOWN);
        } else {
            int metaState = originalEvent.getMetaState() & (KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_CTRL_ON);

            if (originalEvent.isShiftPressed() && !isShiftPressed) {
                isShiftPressed = true;
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, metaState));
            }

            if (originalEvent.isAltPressed() && !isAltPressed) {
                isAltPressed = true;
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.ACTION_DOWN, metaState));
            }

            for (var keyCode : keyMappingValue.keyCodes()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> metaState |= KeyEvent.META_SHIFT_ON;
                    case KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> metaState |= KeyEvent.META_ALT_ON;
                    case KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> metaState |= KeyEvent.META_CTRL_ON;
                }
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, keyCode, KeyEvent.ACTION_DOWN, metaState));
            }
        }
    }

    protected void handleKeyUpInternal(SymPadKeyMappingValue keyMappingValue, KeyEvent originalEvent, InputConnection inputConnection) {
        if (hasMediaKeys(keyMappingValue.keyCodes())) {
            dispatchMediaKeyEvent(keyMappingValue.keyCodes(), originalEvent, KeyEvent.ACTION_UP);
        } else {
            int metaState = originalEvent.getMetaState() & (KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_CTRL_ON);

            for (var keyCode : keyMappingValue.keyCodes()) {
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, keyCode, KeyEvent.ACTION_UP, metaState));
            }

            if (isShiftPressed) {
                isShiftPressed = false;
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, metaState));
            }

            if (isAltPressed) {
                isAltPressed = false;
                inputConnection.sendKeyEvent(InputUtils.translateKeyEvent(originalEvent, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.ACTION_UP, metaState));
            }
        }
    }

    private void dispatchMediaKeyEvent(List<Integer> keyCodes, KeyEvent originalEvent, int action) {
        AudioManager audioManager = getAudioManager();
        if (audioManager != null) {
            for (var keyCode : keyCodes) {
                audioManager.dispatchMediaKeyEvent(InputUtils.translateKeyEvent(originalEvent, keyCode, action, 0));
            }
        }
    }

    private AudioManager getAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) pocketBoardIME.getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }

    private static boolean hasMediaKeys(List<Integer> keyCodes) {
        return keyCodes.stream().anyMatch(SymPadInputHandler::isMediaKey);
    }

    private static boolean isMediaKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                 KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                 KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT,
                 KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
                 KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true;
            default -> false;
        };
    }
}
