package com.titanus2.nanobot;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Gate sensitive actions (MCP pair, show token, rotate) behind
 * biometric or device credential (PIN/pattern/password).
 */
public final class BiometricGate {
    public interface Callback {
        void onAuthenticated();
        void onFailed(String reason);
    }

    private static final int REQ_CONFIRM = 9044;
    private static Callback pending;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private BiometricGate() {}

    public static boolean canAuthenticate(Context c) {
        KeyguardManager kg = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if (kg != null && kg.isDeviceSecure()) return true;
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            if (bm != null) {
                int r = bm.canAuthenticate();
                return r == BiometricManager.BIOMETRIC_SUCCESS;
            }
        }
        return false;
    }

    public static void authenticate(Activity activity, String title, String subtitle, Callback cb) {
        if (activity == null || cb == null) return;
        if (!canAuthenticate(activity)) {
            // Fail closed for secrets when no lock screen — still allow with explicit toast path
            Toast.makeText(activity,
                "Set a screen lock (PIN/biometrics) to approve MCP clients and secrets.",
                Toast.LENGTH_LONG).show();
            cb.onFailed("no_device_credential");
            return;
        }

        if (Build.VERSION.SDK_INT >= 28) {
            try {
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(activity)
                    .setTitle(title == null ? "Confirm" : title)
                    .setSubtitle(subtitle == null ? "Authenticate to continue" : subtitle);
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setDeviceCredentialAllowed(true);
                    b.setNegativeButton("Cancel", activity.getMainExecutor(),
                        (d, w) -> cb.onFailed("cancelled"));
                }
                BiometricPrompt prompt = b.build();
                CancellationSignal cancel = new CancellationSignal();
                prompt.authenticate(cancel, activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            H.post(cb::onAuthenticated);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            H.post(() -> cb.onFailed(errString != null
                                ? errString.toString() : "auth_error"));
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // keep prompt open; no action
                        }
                    });
                return;
            } catch (Exception e) {
                // fall through to confirm device credential
            }
        }

        // Fallback: Keyguard confirm
        try {
            KeyguardManager kg = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
            if (kg != null && Build.VERSION.SDK_INT >= 21) {
                Intent i = kg.createConfirmDeviceCredentialIntent(
                    title == null ? "Confirm" : title,
                    subtitle == null ? "Authenticate to continue" : subtitle);
                if (i != null) {
                    pending = cb;
                    activity.startActivityForResult(i, REQ_CONFIRM);
                    return;
                }
            }
        } catch (Exception ignored) {}
        cb.onFailed("auth_unavailable");
    }

    /** Call from Activity.onActivityResult for confirm-credential fallback. */
    public static void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_CONFIRM || pending == null) return;
        Callback cb = pending;
        pending = null;
        if (resultCode == Activity.RESULT_OK) cb.onAuthenticated();
        else cb.onFailed("cancelled");
    }
}
