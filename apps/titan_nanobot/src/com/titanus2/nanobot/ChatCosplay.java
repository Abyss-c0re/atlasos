package com.titanus2.nanobot;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.7.14: Models (esp. Grok cloud) sometimes dump tool_call XML / Grok-Build
 * markup into assistant *text* instead of API tool_calls. That never runs.
 * Strip for display; optional extract for honest "not executed" messaging.
 */
public final class ChatCosplay {
    private ChatCosplay() {}

    private static final Pattern TOOL_BLOCK = Pattern.compile(
        "(?is)(<tool_call[\\s\\S]*?</tool_call>)|"
            + "(run_terminal_command[\\s\\S]{0,800})|"
            + "(run_terminal_cmd[\\s\\S]{0,800})"
    );
    private static final Pattern PARAM_VALUE = Pattern.compile(
        "(?is)<parameter_value>\\s*([^<]+?)\\s*</parameter_value>"
    );
    private static final Pattern TOOL_NAME = Pattern.compile(
        "(?is)<tool_name>\\s*([^<]+?)\\s*</tool_name>"
    );

    public static boolean looksLikeCosplay(String s) {
        if (s == null || s.isEmpty()) return false;
        String low = s.toLowerCase(Locale.US);
        return low.contains("<tool_call")
            || low.contains("<tool_name>")
            || low.contains("run_terminal_command")
            || low.contains("run_terminal_cmd")
            || low.contains("parameter_value")
            || low.contains("parameter_name");
    }

    /** Visible text without tool cosplay markup. */
    public static String stripForDisplay(String s) {
        if (s == null) return "";
        if (!looksLikeCosplay(s)) return s;
        String out = TOOL_BLOCK.matcher(s).replaceAll("").trim();
        // collapse leftover tags
        out = out.replaceAll("(?is)</?tool_[^>]+>", "");
        out = out.replaceAll("(?is)</?parameter_[^>]+>", "");
        out = out.replaceAll("\n{3,}", "\n\n").trim();
        if (out.isEmpty()) {
            return "(Tool cosplay stripped — that markup was never a real tool call.)";
        }
        if (looksLikeCosplay(s) && !out.contains("NOT called")
                && !out.contains("never a real")) {
            out = out + "\n\n(Tool was NOT called — model wrote tool markup as text.)";
        }
        return out;
    }

    /** Best-effort shell command from cosplay, or null. */
    public static String extractCommand(String s) {
        if (s == null) return null;
        Matcher m = PARAM_VALUE.matcher(s);
        if (m.find()) {
            String c = m.group(1).trim();
            return c.isEmpty() ? null : c;
        }
        m = Pattern.compile("(?is)command\\s+is\\s+[`'\"]?([^`'\\\"\\n<]+)").matcher(s);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?is)\"command\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    public static String extractToolName(String s) {
        if (s == null) return null;
        Matcher m = TOOL_NAME.matcher(s);
        if (m.find()) return m.group(1).trim();
        if (s.toLowerCase(Locale.US).contains("run_terminal")) return "run_terminal_command";
        return null;
    }
}
