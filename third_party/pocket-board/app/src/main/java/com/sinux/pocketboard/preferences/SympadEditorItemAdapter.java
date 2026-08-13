package com.sinux.pocketboard.preferences;

import static android.view.KeyEvent.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.mapping.SymPadKeyAction;
import com.sinux.pocketboard.input.mapping.SymPadKeyMapping;
import com.sinux.pocketboard.input.mapping.SymPadKeyMappingValue;
import com.sinux.pocketboard.input.mapping.SymPadMapping;
import com.sinux.pocketboard.input.mapping.SymPadMappingManager;
import com.sinux.pocketboard.utils.ToastMessageUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SympadEditorItemAdapter extends RecyclerView.Adapter<SympadEditorItemAdapter.KeyViewHolder> {

    private static final int[] KEY_CODES = {
            KEYCODE_Q, KEYCODE_W, KEYCODE_E, KEYCODE_R, KEYCODE_T, KEYCODE_Y, KEYCODE_U, KEYCODE_I, KEYCODE_O, KEYCODE_P,
            KEYCODE_A, KEYCODE_S, KEYCODE_D, KEYCODE_F, KEYCODE_G, KEYCODE_H, KEYCODE_J, KEYCODE_K, KEYCODE_L, KEYCODE_DEL,
            KEYCODE_Z, KEYCODE_X, KEYCODE_C, KEYCODE_V, KEYCODE_SPACE, KEYCODE_B, KEYCODE_N, KEYCODE_M, KEYCODE_ENTER
    };

    private static final String[] KEY_LABELS = {
            "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
            "A", "S", "D", "F", "G", "H", "J", "K", "L", "⌫",
            "Z", "X", "C", "V", "⎵", "B", "N", "M", "⏎"
    };

    private static final Map<Integer, String> KEYS_NAMES = new LinkedHashMap<>();
    static {
        // D-Pad
        KEYS_NAMES.put(KEYCODE_DPAD_UP_LEFT, "↖\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_UP, "⬆\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_UP_RIGHT, "↗\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_LEFT, "⬅\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_CENTER, "\uD83C\uDD97");
        KEYS_NAMES.put(KEYCODE_DPAD_RIGHT, "➡\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_DOWN_LEFT, "↙\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_DOWN, "⬇\uFE0F");
        KEYS_NAMES.put(KEYCODE_DPAD_DOWN_RIGHT, "↘\uFE0F");
        // Typing
        KEYS_NAMES.put(KEYCODE_TAB, "↔\uFE0F");
        KEYS_NAMES.put(KEYCODE_SPACE, "⎵");
        KEYS_NAMES.put(KEYCODE_ESCAPE, "ESC");
        KEYS_NAMES.put(KEYCODE_ENTER, "↩\uFE0F");
        KEYS_NAMES.put(KEYCODE_DEL, "⌫");
        KEYS_NAMES.put(KEYCODE_FORWARD_DEL, "⌦");
        KEYS_NAMES.put(KEYCODE_INSERT, "INS");
        KEYS_NAMES.put(KEYCODE_BREAK, "BREAK");
        KEYS_NAMES.put(KEYCODE_MOVE_HOME, "⏫");
        KEYS_NAMES.put(KEYCODE_MOVE_END, "⏬");
        KEYS_NAMES.put(KEYCODE_PAGE_UP, "\uD83D\uDD3C");
        KEYS_NAMES.put(KEYCODE_PAGE_DOWN, "\uD83D\uDD3D");
        // Modifiers
        KEYS_NAMES.put(KEYCODE_SHIFT_LEFT, "L-SHIFT");
        KEYS_NAMES.put(KEYCODE_SHIFT_RIGHT, "R-SHIFT");
        KEYS_NAMES.put(KEYCODE_ALT_LEFT, "L-ALT");
        KEYS_NAMES.put(KEYCODE_ALT_RIGHT, "R-ALT");
        KEYS_NAMES.put(KEYCODE_CTRL_LEFT, "L-CTRL");
        KEYS_NAMES.put(KEYCODE_CTRL_RIGHT, "R-CTRL");
        // Media
        KEYS_NAMES.put(KEYCODE_MEDIA_PLAY, "▶\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_PAUSE, "⏸\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_PLAY_PAUSE, "⏯\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_STOP, "⏹\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_PREVIOUS, "⏮\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_NEXT, "⏭\uFE0F");
        KEYS_NAMES.put(KEYCODE_MEDIA_REWIND, "⏪");
        KEYS_NAMES.put(KEYCODE_MEDIA_FAST_FORWARD, "⏩");
        KEYS_NAMES.put(KEYCODE_MEDIA_RECORD, "⏺\uFE0F");
        KEYS_NAMES.put(KEYCODE_VOLUME_MUTE, "\uD83D\uDD07");
        KEYS_NAMES.put(KEYCODE_VOLUME_UP, "\uD83D\uDD0A");
        KEYS_NAMES.put(KEYCODE_VOLUME_DOWN, "\uD83D\uDD09");
        // Digits
        KEYS_NAMES.put(KEYCODE_SOFT_LEFT, "L-SOFT");
        KEYS_NAMES.put(KEYCODE_SOFT_RIGHT, "R-SOFT");
        KEYS_NAMES.put(KEYCODE_1, "1\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_2, "2\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_3, "3\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_4, "4\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_5, "5\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_6, "6\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_7, "7\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_8, "8\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_9, "9\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_STAR, "*\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_0, "0\uFE0F⃣");
        KEYS_NAMES.put(KEYCODE_POUND, "#\uFE0F⃣");
        // Letters
        KEYS_NAMES.put(KEYCODE_Q, "Q");
        KEYS_NAMES.put(KEYCODE_W, "W");
        KEYS_NAMES.put(KEYCODE_E, "E");
        KEYS_NAMES.put(KEYCODE_R, "R");
        KEYS_NAMES.put(KEYCODE_T, "T");
        KEYS_NAMES.put(KEYCODE_Y, "Y");
        KEYS_NAMES.put(KEYCODE_U, "U");
        KEYS_NAMES.put(KEYCODE_I, "I");
        KEYS_NAMES.put(KEYCODE_O, "O");
        KEYS_NAMES.put(KEYCODE_P, "P");
        KEYS_NAMES.put(KEYCODE_A, "A");
        KEYS_NAMES.put(KEYCODE_S, "S");
        KEYS_NAMES.put(KEYCODE_D, "D");
        KEYS_NAMES.put(KEYCODE_F, "F");
        KEYS_NAMES.put(KEYCODE_G, "G");
        KEYS_NAMES.put(KEYCODE_H, "H");
        KEYS_NAMES.put(KEYCODE_J, "J");
        KEYS_NAMES.put(KEYCODE_K, "K");
        KEYS_NAMES.put(KEYCODE_L, "L");
        KEYS_NAMES.put(KEYCODE_Z, "Z");
        KEYS_NAMES.put(KEYCODE_X, "X");
        KEYS_NAMES.put(KEYCODE_C, "C");
        KEYS_NAMES.put(KEYCODE_V, "V");
        KEYS_NAMES.put(KEYCODE_B, "B");
        KEYS_NAMES.put(KEYCODE_N, "N");
        KEYS_NAMES.put(KEYCODE_M, "M");
        // Etc
        KEYS_NAMES.put(KEYCODE_COMMA, ",");
        KEYS_NAMES.put(KEYCODE_PERIOD, ".");
        KEYS_NAMES.put(KEYCODE_GRAVE, "`");
        KEYS_NAMES.put(KEYCODE_MINUS, "-");
        KEYS_NAMES.put(KEYCODE_PLUS, "+");
        KEYS_NAMES.put(KEYCODE_EQUALS, "=");
        KEYS_NAMES.put(KEYCODE_LEFT_BRACKET, "[");
        KEYS_NAMES.put(KEYCODE_RIGHT_BRACKET, "]");
        KEYS_NAMES.put(KEYCODE_BACKSLASH, "\\");
        KEYS_NAMES.put(KEYCODE_SLASH, "/");
        KEYS_NAMES.put(KEYCODE_SEMICOLON, ";");
        KEYS_NAMES.put(KEYCODE_APOSTROPHE, "'");
        KEYS_NAMES.put(KEYCODE_AT, "@");
        // F-Keys
        KEYS_NAMES.put(KEYCODE_F1, "F1");
        KEYS_NAMES.put(KEYCODE_F2, "F2");
        KEYS_NAMES.put(KEYCODE_F3, "F3");
        KEYS_NAMES.put(KEYCODE_F4, "F4");
        KEYS_NAMES.put(KEYCODE_F5, "F5");
        KEYS_NAMES.put(KEYCODE_F6, "F6");
        KEYS_NAMES.put(KEYCODE_F7, "F7");
        KEYS_NAMES.put(KEYCODE_F8, "F8");
        KEYS_NAMES.put(KEYCODE_F9, "F9");
        KEYS_NAMES.put(KEYCODE_F10, "F10");
        KEYS_NAMES.put(KEYCODE_F11, "F11");
        KEYS_NAMES.put(KEYCODE_F12, "F12");
        // Gamepad
        KEYS_NAMES.put(KEYCODE_BUTTON_A, "A[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_B, "B[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_C, "C[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_X, "X[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_Y, "Y[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_Z, "Z[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_L1, "L1[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_R1, "R1[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_L2, "L2[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_R2, "R2[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_THUMBL, "THUMBL[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_THUMBR, "THUMBR[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_START, "START[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_SELECT, "SELECT[\uD83C\uDFAE]");
        KEYS_NAMES.put(KEYCODE_BUTTON_MODE, "MODE[\uD83C\uDFAE]");
    }

    private final Context context;
    private final SymPadMappingManager mappingManager;
    private final boolean isLongPress;

    public SympadEditorItemAdapter(Context context, boolean isLongPress) {
        this.context = context;
        this.mappingManager = SymPadMappingManager.getInstance(context);
        this.isLongPress = isLongPress;
    }

    @NonNull
    @Override
    public KeyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.sympad_editor_key_item_view, parent, false);
        return new KeyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull KeyViewHolder holder, int position) {
        int keyCode = KEY_CODES[position];
        String keyLabel = KEY_LABELS[position];
        SymPadKeyMapping keyMapping = mappingManager.getCurrentMapping().getKeyMapping(keyCode);
        SymPadKeyMappingValue keyMappingValue = keyMapping == null ? null :
                isLongPress ? keyMapping.longPress() : keyMapping.shortPress();

        holder.label.setText(keyLabel);
        setHolderValue(holder, keyMappingValue);
        holder.itemView.setOnClickListener(v -> showDialog(position, keyCode, keyLabel, keyMappingValue));
    }

    @Override
    public int getItemCount() {
        return KEY_LABELS.length;
    }

    private void setHolderValue(KeyViewHolder holder, SymPadKeyMappingValue keyMappingValue) {
        holder.actionWrapper.setVisibility(View.GONE);
        holder.action.setBackground(null);
        holder.action.setText("");
        holder.value.setText("");

        if (keyMappingValue == null) {
            return;
        }

        // Keys
        if (keyMappingValue.action() == SymPadKeyAction.KEYS && keyMappingValue.keyCodes() != null) {
            holder.action.setText("⌨");
            holder.actionWrapper.setVisibility(View.VISIBLE);

            String joinedKeyNames = keyMappingValue.keyCodes().stream()
                    .map(keyCode -> KEYS_NAMES.getOrDefault(keyCode, keyCodeToString(keyCode)))
                    .collect(Collectors.joining(" + "));
            holder.value.setText(joinedKeyNames);
            return;
        }

        // Text
        if (keyMappingValue.action() == SymPadKeyAction.TEXT && keyMappingValue.text() != null) {
            holder.action.setText("\uD835\uDC13");
            holder.actionWrapper.setVisibility(View.VISIBLE);
            holder.value.setText(keyMappingValue.text());
            return;
        }

        // App
        if (keyMappingValue.action() == SymPadKeyAction.APP && keyMappingValue.appPackage() != null) {
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appInfo = pm.getApplicationInfo(
                            keyMappingValue.appPackage(),
                            PackageManager.ApplicationInfoFlags.of(0)
                    );
                } else {
                    appInfo = pm.getApplicationInfo(keyMappingValue.appPackage(), 0);
                }

                String appName = pm.getApplicationLabel(appInfo).toString();
                Drawable appIcon = pm.getApplicationIcon(appInfo);

                holder.action.setBackground(appIcon);
                holder.actionWrapper.setVisibility(View.VISIBLE);
                holder.value.setText(appName);

            } catch (PackageManager.NameNotFoundException ignored) {
                holder.action.setText("★");
                holder.actionWrapper.setVisibility(View.VISIBLE);
                holder.value.setText(context.getString(R.string.ime_sympad_app_not_found));
            }
        }
    }

    private void showDialog(
            int position,
            int keyCode,
            String keyLabel,
            SymPadKeyMappingValue keyMappingValue
    ) {
        SympadKeyMappingDialogDTO keyMappingDialogDTO = new SympadKeyMappingDialogDTO(keyCode, keyMappingValue);

        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.sympad_editor_dialog_layout, null);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setTitle(context.getString(isLongPress ? R.string.ime_sympad_long_press_of : R.string.ime_sympad_short_press_of) + " " + keyLabel)
                .setPositiveButton(context.getString(android.R.string.ok), (d, w) -> save(position, keyMappingDialogDTO))
                .setNegativeButton(context.getString(android.R.string.cancel), (d, id) -> d.dismiss())
                .create();

        initActionGroup(dialog, dialogView, keyMappingDialogDTO);
        dialog.show();
    }

    private void initActionGroup(
            AlertDialog dialog,
            View dialogView,
            SympadKeyMappingDialogDTO keyMappingDialogDTO
    ) {
        RadioGroup actionGroup = dialogView.findViewById(R.id.sympadEditorKeyActionGroup);
        TextView actionNone = dialogView.findViewById(R.id.sympadEditorKeyActionNoneTextView);
        ViewGroup actionKeysWrapper = dialogView.findViewById(R.id.sympadEditorKeyActionWrapper);
        EditText actionText = dialogView.findViewById(R.id.sympadEditorKeyActionTextEdit);
        ViewGroup actionAppWrapper = dialogView.findViewById(R.id.sympadEditorAppActionWrapper);

        actionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            keyMappingDialogDTO.selectedActionId = checkedId;

            actionNone.setVisibility(View.GONE);
            actionKeysWrapper.setVisibility(View.GONE);
            actionText.setVisibility(View.GONE);
            actionAppWrapper.setVisibility(View.GONE);

            if (checkedId == R.id.sympadEditorKeyActionNone)
                actionNone.setVisibility(View.VISIBLE);
            else if (checkedId == R.id.sympadEditorKeyActionKeys)
                actionKeysWrapper.setVisibility(View.VISIBLE);
            else if (checkedId == R.id.sympadEditorKeyActionText)
                actionText.setVisibility(View.VISIBLE);
            else if (checkedId == R.id.sympadEditorKeyActionApp)
                actionAppWrapper.setVisibility(View.VISIBLE);

            updateSaveButtonState(dialog, checkedId, keyMappingDialogDTO);
        });

        // Set active tab
        actionGroup.check(keyMappingDialogDTO.selectedActionId);

        // Fill controls
        initKeysTab(dialog, actionKeysWrapper, keyMappingDialogDTO);
        initTextTab(dialog, actionText, keyMappingDialogDTO);
        initAppTab(dialog, actionAppWrapper, keyMappingDialogDTO);
    }

    private void initKeysTab(AlertDialog dialog, ViewGroup actionKeysWrapper, SympadKeyMappingDialogDTO keyMappingDialogDTO) {
        Spinner actionKeysSpinner = actionKeysWrapper.findViewById(R.id.sympadEditorKeyActionKeysSpinner);
        ListView listView = actionKeysWrapper.findViewById(R.id.sympadEditorKeysList);

        // Keys spinner
        var allKeys = new ArrayList<KeySpinnerItem>(KEYS_NAMES.size() + 1);
        allKeys.add(new KeySpinnerItem(-1, context.getString(R.string.ime_sympad_key_hint)));
        KEYS_NAMES.forEach((keyCode, keyName) -> allKeys.add(new KeySpinnerItem(keyCode, keyName)));
        var keysSpinnerAdapter = new ArrayAdapter<>(context, R.layout.sympad_editor_dropdown_item_view, allKeys);
        keysSpinnerAdapter.setDropDownViewResource(R.layout.sympad_editor_dropdown_item_view);
        actionKeysSpinner.setAdapter(keysSpinnerAdapter);

        // List
        SympadEditorKeysListSpinnerAdapter keyAdapter = new SympadEditorKeysListSpinnerAdapter(
                context, keyMappingDialogDTO.selectedKeys, () ->
                updateSaveButtonState(dialog, R.id.sympadEditorKeyActionKeys, keyMappingDialogDTO));
        listView.setAdapter(keyAdapter);

        actionKeysSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0)
                    return;

                KeySpinnerItem selected = (KeySpinnerItem) actionKeysSpinner.getSelectedItem();
                keyMappingDialogDTO.selectedKeys.add(selected);
                keyAdapter.notifyDataSetChanged();

                updateSaveButtonState(dialog, R.id.sympadEditorKeyActionKeys, keyMappingDialogDTO);

                actionKeysSpinner.setSelection(0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void initTextTab(AlertDialog dialog, EditText actionText, SympadKeyMappingDialogDTO keyMappingDialogDTO) {
        actionText.setText(keyMappingDialogDTO.text);
        actionText.addTextChangedListener(new TextWatcher() {
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                keyMappingDialogDTO.text = String.valueOf(s);
                updateSaveButtonState(dialog, R.id.sympadEditorKeyActionText, keyMappingDialogDTO);
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void initAppTab(AlertDialog dialog, ViewGroup actionAppWrapper, SympadKeyMappingDialogDTO keyMappingDialogDTO) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.add(0, null);
        SympadEditorAppSpinnerAdapter appAdapter = new SympadEditorAppSpinnerAdapter(context, apps, pm);
        Spinner actionApp = actionAppWrapper.findViewById(R.id.sympadEditorKeyActionAppSpinner);
        actionApp.setAdapter(appAdapter);

        if (keyMappingDialogDTO.appPackage != null) {
            for (int i = 1; i < apps.size(); i++) {
                ResolveInfo info = apps.get(i);
                if (info.activityInfo.packageName.equals(keyMappingDialogDTO.appPackage)) {
                    actionApp.setSelection(i);
                    break;
                }
            }
        }

        actionApp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != 0) {
                    keyMappingDialogDTO.appPackage = apps.get(position).activityInfo.packageName;
                } else {
                    keyMappingDialogDTO.appPackage = null;
                }
                updateSaveButtonState(dialog, R.id.sympadEditorKeyActionApp, keyMappingDialogDTO);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                keyMappingDialogDTO.appPackage = null;
                updateSaveButtonState(dialog, R.id.sympadEditorKeyActionApp, keyMappingDialogDTO);
            }
        });

        TextView permissionMsg = actionAppWrapper.findViewById(R.id.sympadEditorKeyActionAppPermissionMsg);
        if (!Settings.canDrawOverlays(context)) {
            permissionMsg.setVisibility(View.VISIBLE);
            permissionMsg.setOnClickListener(v -> {
                if (Settings.canDrawOverlays(context)) {
                    permissionMsg.setVisibility(View.GONE);
                    return;
                }
                Intent permissionIntent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName())
                );
                permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(permissionIntent);
            });
        }
    }

    private void updateSaveButtonState(AlertDialog dialog, int checkedId, SympadKeyMappingDialogDTO keyMappingDialogDTO) {
        Button saveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (saveBtn != null) {
            if (checkedId == R.id.sympadEditorKeyActionNone)
                saveBtn.setEnabled(true);
            else if (checkedId == R.id.sympadEditorKeyActionKeys)
                saveBtn.setEnabled(!keyMappingDialogDTO.selectedKeys.isEmpty());
            else if (checkedId == R.id.sympadEditorKeyActionText)
                saveBtn.setEnabled(!TextUtils.isEmpty(keyMappingDialogDTO.text));
            else if (checkedId == R.id.sympadEditorKeyActionApp)
                saveBtn.setEnabled(!TextUtils.isEmpty(keyMappingDialogDTO.appPackage));
        }
    }

    private void save(int position, SympadKeyMappingDialogDTO keyMappingDialogDTO) {
        SymPadKeyMappingValue keyMappingValue = new SymPadKeyMappingValue(
                keyMappingDialogDTO.getAction(),
                keyMappingDialogDTO.getKeys(),
                keyMappingDialogDTO.text,
                keyMappingDialogDTO.appPackage
        );

        SymPadMapping currentMapping = mappingManager.getCurrentMapping();
        SymPadKeyMapping keyMapping = currentMapping.getKeyMapping(keyMappingDialogDTO.keyCode);

        if (keyMapping == null) {
            keyMapping = new SymPadKeyMapping(
                    isLongPress ? null : keyMappingValue,
                    isLongPress ? keyMappingValue : null
            );
        } else {
            if (isLongPress) {
                keyMapping = keyMapping.withLongPress(keyMappingValue);
            } else {
                keyMapping = keyMapping.withShortPress(keyMappingValue);
            }
        }

        currentMapping.setKeyMapping(keyMappingDialogDTO.keyCode, keyMapping);
        notifyItemChanged(position);

        try {
            SymPadMappingManager.save(context, currentMapping);
        } catch (Exception e) {
            Log.e("PocketBoard", "Failed to save SymPad mapping", e);
            ToastMessageUtils.showMessage(context, R.string.sympad_mapping_save_failed);
        }
    }

    public static class KeyViewHolder extends RecyclerView.ViewHolder {

        private final TextView label;
        private final View actionWrapper;
        private final TextView action;
        private final TextView value;

        KeyViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.sympadKeyLabel);
            actionWrapper = v.findViewById(R.id.sympadKeyActionWrapper);
            action = v.findViewById(R.id.sympadKeyAction);
            value = v.findViewById(R.id.sympadKeyValue);
        }
    }

    public record KeySpinnerItem(int keyCode, String keyName) {
        @NonNull
        @Override
        public String toString() {
            if (keyCode == -1)
                return keyName;

            return keyName + " [" + keyCodeToString(keyCode) + "]";
        }
    }

    private static final class SympadKeyMappingDialogDTO {

        private final int keyCode;
        private int selectedActionId = R.id.sympadEditorKeyActionNone;
        private final List<KeySpinnerItem> selectedKeys = new ArrayList<>();
        private String text;
        private String appPackage;

        public SympadKeyMappingDialogDTO(int keyCode, SymPadKeyMappingValue keyMappingValue) {
            this.keyCode = keyCode;

            if (keyMappingValue != null) {
                if (keyMappingValue.action() != null) {
                    selectedActionId = switch (keyMappingValue.action()) {
                        case KEYS -> R.id.sympadEditorKeyActionKeys;
                        case TEXT -> R.id.sympadEditorKeyActionText;
                        case APP -> R.id.sympadEditorKeyActionApp;
                    };
                }

                if (keyMappingValue.keyCodes() != null) {
                    keyMappingValue.keyCodes().forEach(code -> selectedKeys.add(
                                    new KeySpinnerItem(code, KEYS_NAMES.getOrDefault(code, keyCodeToString(keyCode)))
                    ));
                }

                text = keyMappingValue.text();
                appPackage = keyMappingValue.appPackage();
            }
        }

        public SymPadKeyAction getAction() {
            if (selectedActionId == R.id.sympadEditorKeyActionKeys)
                return SymPadKeyAction.KEYS;
            else if (selectedActionId == R.id.sympadEditorKeyActionText)
                return SymPadKeyAction.TEXT;
            else if (selectedActionId == R.id.sympadEditorKeyActionApp)
                return SymPadKeyAction.APP;
            else
                return null;
        }

        public List<Integer> getKeys() {
            return selectedKeys.stream()
                    .map(keySpinnerItem -> keySpinnerItem.keyCode)
                    .collect(Collectors.toList());
        }
    }
}