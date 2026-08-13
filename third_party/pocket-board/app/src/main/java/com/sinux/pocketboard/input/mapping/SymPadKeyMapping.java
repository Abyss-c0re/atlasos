package com.sinux.pocketboard.input.mapping;

public record SymPadKeyMapping(
        SymPadKeyMappingValue shortPress,
        SymPadKeyMappingValue longPress
) {
    public SymPadKeyMapping withShortPress(SymPadKeyMappingValue shortPress) {
        return new SymPadKeyMapping(shortPress, longPress);
    }

    public SymPadKeyMapping withLongPress(SymPadKeyMappingValue longPress) {
        return new SymPadKeyMapping(shortPress, longPress);
    }

    public static SymPadKeyMapping ofShortKey(int keyCode) {
        return new SymPadKeyMapping(
                SymPadKeyMappingValue.ofKey(keyCode),
                null
        );
    }

    public static SymPadKeyMapping ofKeys(int shortKeyCode, int longKeyCode) {
        return new SymPadKeyMapping(
                SymPadKeyMappingValue.ofKey(shortKeyCode),
                SymPadKeyMappingValue.ofKey(longKeyCode)
        );
    }
}
