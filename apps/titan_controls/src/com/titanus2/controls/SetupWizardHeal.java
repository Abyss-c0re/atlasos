package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;

/**
 * Lineage SetupWizard holds {@code StatusBarManager.disable} until finished
 * ({@code mDisabled1} non-zero → no clock / no QS). After wipe or when the
 * human skips setup, finish provision and re-enable the status bar.
 * <p>
 * Host path: {@code scripts/host/install_latest_input_stack.sh}. App path:
 * boot / package-replace / hub open so QS returns without re-running adb.
 */
public final class SetupWizardHeal {
    private SetupWizardHeal() {}

    /** Mark setup complete + clear status-bar setup disables. Best-effort. */
    public static void heal(Context ctx) {
        if (ctx == null) return;
        try {
            Settings.Global.putInt(ctx.getContentResolver(), "device_provisioned", 1);
        } catch (Exception ignored) {}
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), "user_setup_complete", 1);
        } catch (Exception ignored) {}
        try {
            Settings.Global.putInt(ctx.getContentResolver(), "user_setup_complete", 1);
        } catch (Exception ignored) {}
        try {
            Settings.Global.putInt(ctx.getContentResolver(), "setup_wizard_has_run", 1);
        } catch (Exception ignored) {}
        // Shell cmds work when app has shell/privileged path; ignore failures.
        execQuiet("cmd", "statusbar", "disable-for-setup", "false");
        execQuiet("cmd", "statusbar", "send-disable-flag", "none");
    }

    private static void execQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Don't block forever if statusbar is wedged
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }
}
