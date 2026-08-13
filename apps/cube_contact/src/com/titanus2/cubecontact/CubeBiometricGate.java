package com.titanus2.cubecontact;

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
 * Biometric / device-credential gate for Commander high-risk overrides.
 * Fail-closed when no screen lock (no silent danger).
 */
public final class CubeBiometricGate {
    public interface Callback {
        void onAuthenticated();
        void onFailed(String reason);
    }

    private static final int REQ_CONFIRM = 9144;
    private static Callback pending;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private CubeBiometricGate() {}

    public static boolean canAuthenticate(Context c) {
        KeyguardManager kg = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if (kg != null && kg.isDeviceSecure()) return true;
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            if (bm != null) {
                return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
        }
        return false;
    }

    public static void authenticate(Activity activity, String title, String subtitle,
                                    Callback cb) {
        if (activity == null || cb == null) return;
        if (!canAuthenticate(activity)) {
            Toast.makeText(activity,
                "Set a screen lock to confirm dangerous Commander overrides.",
                Toast.LENGTH_LONG).show();
            cb.onFailed("no_device_credential");
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(activity)
                    .setTitle(title != null ? title : "Commander override")
                    .setSubtitle(subtitle != null ? subtitle
                        : "Confirm high-risk action via the CUBE");
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
                        public void onAuthenticationError(int errorCode,
                                                          CharSequence errString) {
                            H.post(() -> cb.onFailed(errString != null
                                ? errString.toString() : "auth_error"));
                        }

                        @Override
                        public void onAuthenticationFailed() { /* keep open */ }
                    });
                return;
            } catch (Exception ignored) {}
        }
        try {
            KeyguardManager kg = (KeyguardManager)
                activity.getSystemService(Context.KEYGUARD_SERVICE);
            if (kg != null && Build.VERSION.SDK_INT >= 21) {
                Intent i = kg.createConfirmDeviceCredentialIntent(
                    title != null ? title : "Commander override",
                    subtitle != null ? subtitle : "Confirm high-risk action");
                if (i != null) {
                    pending = cb;
                    activity.startActivityForResult(i, REQ_CONFIRM);
                    return;
                }
            }
        } catch (Exception ignored) {}
        cb.onFailed("auth_unavailable");
    }

    public static void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_CONFIRM || pending == null) return;
        Callback cb = pending;
        pending = null;
        if (resultCode == Activity.RESULT_OK) cb.onAuthenticated();
        else cb.onFailed("cancelled");
    }
}
