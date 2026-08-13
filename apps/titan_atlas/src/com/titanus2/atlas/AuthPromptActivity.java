package com.titanus2.atlas;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Biometric / device-credential gate for atlas-auth (sudo / su / apt privilege).
 * Platform APIs only — no AndroidX. Not a Grok/TUI intercept.
 */
public class AuthPromptActivity extends Activity {
    public static final String EXTRA_ID = "auth_id";
    public static final String EXTRA_REASON = "auth_reason";
    /**
     * Origin of remote privilege requests (KDE Connect, BlackCube sudo, nanobot).
     * Shown on the bio sheet and in notifications — local sudo omits this.
     */
    public static final String EXTRA_SOURCE = "auth_source";
    /**
     * When true, grant/deny uses {@link #setResult} for the caller (Controls Remote ADB)
     * instead of only the file protocol. Still writes auth ok/fail when id is set.
     */
    public static final String EXTRA_FOR_RESULT = "auth_for_result";
    /** Product Remote ADB arm after bio — world-readable flag for pad-agent / Controls. */
    public static final String REMOTE_ADB_GRANT_FILE = "/data/local/tmp/titan2_remote_adb_grant";

    private String id;
    private String source;
    private boolean forResultMode;
    private boolean finished;
    /** True while a biometric/keyguard sheet is up — ignore re-launch for same id. */
    private boolean promptActive;
    private CancellationSignal cancel;
    /** When true, cancel/destroy must not write fail (supersede or client still waiting). */
    private boolean suppressDenyOnCancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Signal MainActivity: do not rebuild PTY when we finish (apt/sudo path).
        AtlasPrefs.markAuthUi(this, true);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Same request id still open → keep the live biometric sheet (do not restart).
        String newId = intent != null ? intent.getStringExtra(EXTRA_ID) : null;
        if (newId != null && newId.equals(id) && promptActive && !finished) {
            Log.i("AtlasAuth", "auth prompt already active for " + id);
            return;
        }
        // Different id: cancel previous quietly, then prompt for the new request.
        suppressDenyOnCancel = true;
        if (cancel != null) {
            try {
                cancel.cancel();
            } catch (Exception ignored) {
            }
            cancel = null;
        }
        suppressDenyOnCancel = false;
        finished = false;
        promptActive = false;
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        id = intent != null ? intent.getStringExtra(EXTRA_ID) : null;
        String reason = intent != null ? intent.getStringExtra(EXTRA_REASON) : null;
        source = intent != null ? intent.getStringExtra(EXTRA_SOURCE) : null;
        forResultMode = intent != null && intent.getBooleanExtra(EXTRA_FOR_RESULT, false);
        // forResult mode (Controls Remote ADB): id optional — synthesize one
        if ((id == null || id.isEmpty()) && forResultMode) {
            id = "ui-" + System.currentTimeMillis();
        }
        if (id == null || id.isEmpty()) {
            if (forResultMode) {
                setResult(RESULT_CANCELED);
            }
            finish();
            return;
        }
        if (reason == null || reason.isEmpty()) reason = "Atlas privilege";
        if (source != null) source = source.trim();
        if (source != null && source.isEmpty()) source = null;

        // Minimal host surface — platform biometric sheet is the real UI
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setGravity(Gravity.CENTER);
        int p = dp(24);
        root.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        String head = source != null
            ? (source + "\n" + reason)
            : reason;
        t.setText(head);
        t.setTextColor(0xFFB0BEC5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(t);
        setContentView(root);

        prompt(reason);
    }

    private void prompt(String reason) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String title = source != null ? ("Auth · " + source) : "Atlas auth";
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle(title)
                    .setSubtitle(reason)
                    .setDescription(source != null ? source : "privilege");
                // Prefer weak+strong fingerprint — STRONG-only rejects many Titan enrollments.
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setDeviceCredentialAllowed(true);
                    b.setNegativeButton("Deny", getMainExecutor(), (d, w) -> deny());
                }
                if (cancel != null) {
                    suppressDenyOnCancel = true;
                    try {
                        cancel.cancel();
                    } catch (Exception ignored) {
                    }
                    suppressDenyOnCancel = false;
                }
                cancel = new CancellationSignal();
                promptActive = true;
                b.build().authenticate(cancel, getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            grant();
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // Wrong finger — keep dialog open (platform retries). Do not deny yet.
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            // 10 = USER_CANCELED (back / dismiss by user)
                            // 13 = NEGATIVE_BUTTON
                            // 5  = CANCELED (system — activity recreate, am re-start, etc.)
                            // Never deny on system CANCELED: hybrid used to re-am-start every 5s
                            // and that wrote fail before the human could finish the finger.
                            if (suppressDenyOnCancel) {
                                Log.i("AtlasAuth", "biometric cancel suppressed " + errorCode);
                                return;
                            }
                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || errorCode == 13 /* NEGATIVE_BUTTON */) {
                                deny();
                            } else if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                || errorCode == 5) {
                                // System canceled sheet — leave request open; client keeps waiting.
                                Log.w("AtlasAuth", "biometric system cancel " + errorCode
                                    + " " + errString + " — not denying");
                                promptActive = false;
                            } else {
                                // Lockout / unable_to_process → device PIN/pattern fallback
                                Log.w("AtlasAuth", "biometric err " + errorCode + " " + errString);
                                promptActive = false;
                                fallbackKeyguard(reason);
                            }
                        }
                    });
                return;
            } catch (Exception e) {
                fallbackKeyguard(reason);
                return;
            }
        }
        fallbackKeyguard(reason);
    }

    private void fallbackKeyguard(String reason) {
        KeyguardManager kg = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (kg != null && kg.isKeyguardSecure()) {
            Intent i = kg.createConfirmDeviceCredentialIntent("Atlas auth", reason);
            if (i != null) {
                //noinspection deprecation
                startActivityForResult(i, 42);
                return;
            }
        }
        Toast.makeText(this, "no lock · granted", Toast.LENGTH_SHORT).show();
        grant();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42) {
            if (resultCode == RESULT_OK) grant();
            else deny();
        }
    }

    private void grant() {
        if (finished) return;
        finished = true;
        promptActive = false;
        if (id != null && !id.isEmpty()) {
            AtlasAuth.writeResult(this, id, true);
        }
        if (forResultMode) {
            // Biometrics only. Caller (Controls) owns ON vs Pair vs OFF.
            // NEVER auto-arm here — that re-armed TCP after user flipped OFF,
            // and replaced Pair (PIN) with classic arm (no PIN).
            writeRemoteAdbGrant(true);
            setResult(RESULT_OK);
        }
        // Quiet window so MainActivity onResume does not restartSession().
        AtlasPrefs.markAuthUi(this, false);
        finish();
    }

    private void deny() {
        if (finished) return;
        finished = true;
        promptActive = false;
        if (id != null && !id.isEmpty()) {
            AtlasAuth.writeResult(this, id, false);
        }
        if (forResultMode) {
            writeRemoteAdbGrant(false);
            setResult(RESULT_CANCELED);
        }
        AtlasPrefs.markAuthUi(this, false);
        finish();
    }

    private static void writeRemoteAdbGrant(boolean ok) {
        writeWorldFile(REMOTE_ADB_GRANT_FILE, ok ? "ok\n" : "deny\n");
    }

    /** pad-agent polls this every tick → root arm without second bio. */
    private static void writeDevAction(String action) {
        if (action == null) return;
        String body = action + " " + System.currentTimeMillis() + "\n";
        writeWorldFile("/data/local/tmp/titan2_dev_action", body);
        writeWorldFile("/data/misc/titan2/titan2_dev_action", body);
    }

    private static void writeWorldFile(String path, String body) {
        try {
            java.io.File f = new java.io.File(path);
            java.io.File parent = f.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception e) {
            Log.w("AtlasAuth", "write " + path, e);
        }
    }

    private static String readRemoteAdbPort() {
        try {
            java.io.File pf = new java.io.File("/data/local/tmp/remote_adb_port");
            if (!pf.isFile()) pf = new java.io.File("/data/misc/titan2/remote_adb_port");
            if (pf.isFile()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader(pf))) {
                    String line = br.readLine();
                    if (line != null && line.trim().matches("[0-9]+")) {
                        int p = Integer.parseInt(line.trim());
                        if (p >= 1024 && p <= 65535) return String.valueOf(p);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "5555";
    }

    /** After Remote ADB bio: try su arm immediately (pad-agent is backup). */
    private static void tryArmTcpAfterBio() {
        final String portF = readRemoteAdbPort();
        final String[] bins = {
            "/system/bin/titan2-dev-action.sh",
            "/data/local/tmp/titan2-dev-action-live.sh",
            "/data/local/tmp/titan2-dev-action.sh",
        };
        new Thread(() -> {
            for (String bin : bins) {
                if (!new java.io.File(bin).isFile()) continue;
                String[][] cmds = {
                    {"su", "0", "sh", bin, "arm_wireless_adb_trusted", portF},
                    {"/system/bin/su", "0", "sh", bin, "arm_wireless_adb_trusted", portF},
                };
                for (String[] cmd : cmds) {
                    try {
                        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                        try (java.io.InputStream in = p.getInputStream()) {
                            byte[] buf = new byte[256];
                            while (in.read(buf) >= 0) { /* drain */ }
                        }
                        if (p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                            Log.i("AtlasAuth", "remote adb armed via " + bin + " :" + portF);
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            Log.w("AtlasAuth", "direct arm failed — pad-agent must pick up titan2_dev_action");
        }, "remote-adb-arm").start();
    }

    @Override
    public void onBackPressed() {
        deny();
    }

    @Override
    protected void onDestroy() {
        // Cancel the platform sheet without writing fail — client still waits until
        // timeout or a later grant. Writing fail here made hybrid sudo "can't wait".
        suppressDenyOnCancel = true;
        if (cancel != null) {
            try {
                cancel.cancel();
            } catch (Exception ignored) {
            }
            cancel = null;
        }
        // Always clear showing flag; keep quiet-until grace from markAuthUi(false).
        if (!finished) {
            AtlasPrefs.markAuthUi(this, false);
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
