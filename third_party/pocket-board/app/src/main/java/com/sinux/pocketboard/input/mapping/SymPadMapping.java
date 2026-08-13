package com.sinux.pocketboard.input.mapping;

import java.util.HashMap;
import java.util.Map;

public record SymPadMapping(
        HashMap<Integer, SymPadKeyMapping> keyMappings
) {
    public SymPadMapping(Map<Integer, SymPadKeyMapping> keyMappings) {
        this(new HashMap<>(keyMappings));
    }

    public SymPadKeyMapping getKeyMapping(int keyCode) {
        return keyMappings.get(keyCode);
    }

    public void setKeyMapping(int keyCode, SymPadKeyMapping keyMapping) {
        keyMappings.put(keyCode, keyMapping);
    }
}
