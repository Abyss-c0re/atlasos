package com.titanus2.nanobot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keystore-backed encrypted prefs for secrets (MCP client registry, token cache).
 * Provider OAuth stays in nanobot's sealed session under NANOBOT_HOME (peer_token KDF).
 */
public final class SecureStore {
    private static final String TAG = "SecureStore";
    private static final String KS_ALIAS = "titan_nanobot_aes";
    private static final String PREFS = "titan_nanobot_secure";
    private static final String ANDROID_KS = "AndroidKeyStore";

    private SecureStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KS);
        ks.load(null);
        if (!ks.containsAlias(KS_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS);
            kg.init(new KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
    }

    public static void putSecret(Context c, String name, String plaintext) {
        if (plaintext == null) {
            sp(c).edit().remove("s_" + name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
            sp(c).edit().putString("s_" + name, packed).apply();
        } catch (Exception e) {
            Log.e(TAG, "putSecret " + name + ": " + e.getMessage());
            // fail closed — do not store plaintext
        }
    }

    public static String getSecret(Context c, String name) {
        String packed = sp(c).getString("s_" + name, null);
        if (packed == null || packed.isEmpty()) return null;
        try {
            int colon = packed.indexOf(':');
            if (colon < 1) return null;
            byte[] iv = Base64.decode(packed.substring(0, colon), Base64.NO_WRAP);
            byte[] ct = Base64.decode(packed.substring(colon + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "getSecret " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** MCP client registry as JSON array string (encrypted). */
    public static String getClientsJson(Context c) {
        String j = getSecret(c, "mcp_clients");
        return j == null ? "[]" : j;
    }

    public static void setClientsJson(Context c, String json) {
        putSecret(c, "mcp_clients", json == null ? "[]" : json);
    }

    public static void cachePeerToken(Context c, String token) {
        putSecret(c, "peer_token_cache", token);
    }

    public static String cachedPeerToken(Context c) {
        return getSecret(c, "peer_token_cache");
    }
}
