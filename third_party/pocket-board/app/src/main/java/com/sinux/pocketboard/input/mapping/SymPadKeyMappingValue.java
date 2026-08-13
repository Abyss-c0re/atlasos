package com.sinux.pocketboard.input.mapping;

import java.util.Collections;
import java.util.List;

public record SymPadKeyMappingValue(
        SymPadKeyAction action,
        List<Integer> keyCodes,
        String text,
        String appPackage
) {
    public static SymPadKeyMappingValue ofKey(int keyCode) {
        return new SymPadKeyMappingValue(
                SymPadKeyAction.KEYS,
                Collections.singletonList(keyCode),
                null,
                null
        );
    }
}
