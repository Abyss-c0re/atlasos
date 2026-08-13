package com.sinux.pocketboard.input.mapping;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;
import android.view.KeyEvent;

import com.sinux.pocketboard.R;
import com.sinux.pocketboard.utils.ToastMessageUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;

public class SymPadMappingManager {

    public static final String FILE_NAME = "sympad_mapping.json";

    private static volatile SymPadMappingManager instance;

    private SymPadMapping currentMapping;

    private SymPadMappingManager(Context context) {
        currentMapping = load(context);
    }

    public static SymPadMappingManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SymPadMappingManager.class) {
                if (instance == null) {
                    instance = new SymPadMappingManager(context);
                }
            }
        }
        return instance;
    }

    public SymPadMapping getCurrentMapping() {
        return currentMapping;
    }

    private static SymPadMapping load(Context context) {
        var file = new File(context.getFilesDir(), FILE_NAME);

        if (!file.exists())
            return getDefaultMapping();

        var atomicFile = new AtomicFile(file);

        try (
                InputStream is = atomicFile.openRead();
                Scanner s = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A")
        ) {
            String json = s.hasNext() ? s.next() : "";
            return SymPadMappingParser.parseJson(json);
        } catch (Exception e) {
            Log.e("PocketBoard", "Failed to load SymPad mapping", e);
            ToastMessageUtils.showMessage(context, R.string.sympad_mapping_load_failed);
            return getDefaultMapping();
        }
    }

    public static void save(Context context, SymPadMapping mapping) throws Exception {
        var file = new File(context.getFilesDir(), FILE_NAME);
        var atomicFile = new AtomicFile(file);
        FileOutputStream fos = null;

        try {
            byte[] data = SymPadMappingParser.writeJson(mapping).getBytes(StandardCharsets.UTF_8);
            fos = atomicFile.startWrite();
            fos.write(data);
            atomicFile.finishWrite(fos);
        } catch (Exception e) {
            if (fos != null) {
                atomicFile.failWrite(fos);
            }
            Log.e("PocketBoard", "Failed to save SymPad mapping", e);
            ToastMessageUtils.showMessage(context, R.string.sympad_mapping_save_failed);
            throw e;
        }
    }

    public static void importFromFile(Context context, Uri uri) {
        try (
                InputStream is = context.getContentResolver().openInputStream(uri);
                Scanner s = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A")
        ) {
            String json = s.hasNext() ? s.next() : "";
            SymPadMapping mapping = SymPadMappingParser.parseJson(json);
            save(context, mapping);
            // Apply new mapping
            getInstance(context).currentMapping = mapping;
        } catch (Exception e) {
            Log.e("PocketBoard", "Failed to import SymPad mapping", e);
            ToastMessageUtils.showMessage(context, R.string.sympad_mapping_load_failed);
        }
    }

    public static void exportToFile(Context context, Uri uri) {
        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            SymPadMapping mapping = getInstance(context).currentMapping;
            String json = SymPadMappingParser.writeJson(mapping);
            os.write(json.getBytes());
        } catch (Exception e) {
            Log.e("PocketBoard", "Failed to export SymPad mapping", e);
            ToastMessageUtils.showMessage(context, R.string.sympad_mapping_save_failed);
        }
    }

    public static boolean hasCustomMapping(Context context) {
        return new File(context.getFilesDir(), FILE_NAME).exists();
    }

    public static void resetToDefaults(Context context) {
        // Remove files
        var file = new File(context.getFilesDir(), FILE_NAME);
        var atomicFile = new AtomicFile(file);
        atomicFile.delete();

        // Set mapping to default
        getInstance(context).currentMapping = getDefaultMapping();
    }

    private static SymPadMapping getDefaultMapping() {
        return new SymPadMapping(
                Map.ofEntries(
                        // R, U - Home, F, J - End (text navigation)
                        Map.entry(KeyEvent.KEYCODE_R, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_MOVE_HOME)),
                        Map.entry(KeyEvent.KEYCODE_U, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_MOVE_HOME)),
                        Map.entry(KeyEvent.KEYCODE_F, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_MOVE_END)),
                        Map.entry(KeyEvent.KEYCODE_J, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_MOVE_END)),
                        // Q, W, E, A, S, D, Z, X, C or I, O, P, K, L, DEL, N, M, ENTER - 9-positional D-pad
                        Map.entry(KeyEvent.KEYCODE_Q, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_I, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_W, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP)),
                        Map.entry(KeyEvent.KEYCODE_O, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP)),
                        Map.entry(KeyEvent.KEYCODE_E, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP_RIGHT)),
                        Map.entry(KeyEvent.KEYCODE_P, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_UP_RIGHT)),
                        Map.entry(KeyEvent.KEYCODE_A, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_K, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_S, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_CENTER)),
                        Map.entry(KeyEvent.KEYCODE_L, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_CENTER)),
                        Map.entry(KeyEvent.KEYCODE_D, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_RIGHT)),
                        Map.entry(KeyEvent.KEYCODE_DEL, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_RIGHT)),
                        Map.entry(KeyEvent.KEYCODE_Z, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_N, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN_LEFT)),
                        Map.entry(KeyEvent.KEYCODE_X, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN)),
                        Map.entry(KeyEvent.KEYCODE_M, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN)),
                        Map.entry(KeyEvent.KEYCODE_C, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN_RIGHT)),
                        Map.entry(KeyEvent.KEYCODE_ENTER, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DPAD_DOWN_RIGHT)),
                        // T - Esc, Y - Enter
                        Map.entry(KeyEvent.KEYCODE_T, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_ESCAPE)),
                        Map.entry(KeyEvent.KEYCODE_Y, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_ENTER)),
                        // G - Backspace, H - Forward delete
                        Map.entry(KeyEvent.KEYCODE_G, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_DEL)),
                        Map.entry(KeyEvent.KEYCODE_H, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_FORWARD_DEL)),
                        // V - Prev/Rewind, Space - Play/Pause, B - Next/Fast forward (media navigation)
                        Map.entry(KeyEvent.KEYCODE_V, SymPadKeyMapping.ofKeys(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND)),
                        Map.entry(KeyEvent.KEYCODE_SPACE, SymPadKeyMapping.ofShortKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
                        Map.entry(KeyEvent.KEYCODE_B, SymPadKeyMapping.ofKeys(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
                )
        );
    }
}
