package com.sinux.pocketboard.input.mapping;

import android.text.TextUtils;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public final class SymPadMappingParser {

    private static final String SHORT_PRESS_PROPERTY = "shortPress";
    private static final String LONG_PRESS_PROPERTY = "longPress";
    private static final String ACTION_PROPERTY = "action";
    private static final String KEY_CODES_PROPERTY = "keyCodes";
    private static final String TEXT_PROPERTY = "text";
    private static final String APP_PACKAGE_PROPERTY = "appPackage";

    public static SymPadMapping parseJson(String jsonString) throws Exception {
        var root = new JSONObject(jsonString);
        var keyMap = new HashMap<Integer, SymPadKeyMapping>();

        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String keyName = keys.next();
            int keyCode = KeyEvent.keyCodeFromString(keyName);
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                JSONObject keyJson = root.getJSONObject(keyName);

                var keyMapping = new SymPadKeyMapping(
                        parseValue(keyJson.optJSONObject(SHORT_PRESS_PROPERTY)),
                        parseValue(keyJson.optJSONObject(LONG_PRESS_PROPERTY))
                );

                if (!isEmpty(keyMapping))
                    keyMap.put(keyCode, keyMapping);
            } else {
                throw new IllegalArgumentException("Unknown key name " + keyName);
            }
        }

        return new SymPadMapping(keyMap);
    }

    private static SymPadKeyMappingValue parseValue(JSONObject json) throws Exception {
        if (json == null)
            return null;

        var action = SymPadKeyAction.valueOf(json.getString(ACTION_PROPERTY));
        var text = json.optString(TEXT_PROPERTY, null);
        var appPackage = json.optString(APP_PACKAGE_PROPERTY, null);

        var keyCodes = new ArrayList<Integer>();
        JSONArray keyCodesArray = json.optJSONArray(KEY_CODES_PROPERTY);
        if (keyCodesArray != null) {
            for (int i = 0; i < keyCodesArray.length(); i++) {
                String keyName = keyCodesArray.getString(i);
                int keyCode = KeyEvent.keyCodeFromString(keyName);
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    keyCodes.add(keyCode);
                } else {
                    throw new IllegalArgumentException("Unknown key name " + keyName);
                }
            }
        }

        return new SymPadKeyMappingValue(action, keyCodes, text, appPackage);
    }

    public static String writeJson(SymPadMapping mapping) throws Exception {
        var root = new JSONObject();

        for (var entry : mapping.keyMappings().entrySet()) {
            String keyName = KeyEvent.keyCodeToString(entry.getKey());

            if (isEmpty(entry.getValue()))
                continue;

            var keyNode = new JSONObject();
            keyNode.put(SHORT_PRESS_PROPERTY, writeValue(entry.getValue().shortPress()));
            keyNode.put(LONG_PRESS_PROPERTY, writeValue(entry.getValue().longPress()));
            root.put(keyName, keyNode);
        }

        return root.toString(2);
    }

    private static JSONObject writeValue(SymPadKeyMappingValue value) throws Exception {
        if (isEmpty(value))
            return null;

        var valueNode = new JSONObject();
        valueNode.put(ACTION_PROPERTY, value.action().name());

        if (!TextUtils.isEmpty(value.text())) {
            valueNode.put(TEXT_PROPERTY, value.text());
        }

        if (!TextUtils.isEmpty(value.appPackage())) {
            valueNode.put(APP_PACKAGE_PROPERTY, value.appPackage());
        }

        if (value.keyCodes() != null && !value.keyCodes().isEmpty()) {
            var keyCodesArray = new JSONArray();
            for (var code : value.keyCodes()) {
                keyCodesArray.put(KeyEvent.keyCodeToString(code));
            }
            valueNode.put(KEY_CODES_PROPERTY, keyCodesArray);
        }

        return valueNode;
    }

    private static boolean isEmpty(SymPadKeyMapping keyMapping) {
        return keyMapping == null || (isEmpty(keyMapping.shortPress()) && isEmpty(keyMapping.longPress()));
    }

    private static boolean isEmpty(SymPadKeyMappingValue keyMappingValue) {
        if (keyMappingValue == null || keyMappingValue.action() == null)
            return true;

        return switch (keyMappingValue.action()) {
            case KEYS -> keyMappingValue.keyCodes() == null || keyMappingValue.keyCodes().isEmpty();
            case TEXT -> TextUtils.isEmpty(keyMappingValue.text());
            case APP -> TextUtils.isEmpty(keyMappingValue.appPackage());
        };
    }
}
