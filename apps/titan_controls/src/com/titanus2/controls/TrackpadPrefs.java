package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent policy for keyboard-as-trackpad.
 *
 * Modes mirror stock “Intelligent assistance” intent in a maintainable way:
 *   OFF       — always inhibit touchPad (usable typing)
 *   GLOBAL    — always enable (PC remote / desktop apps)
 *   WHITELIST — enable only while a listed package is in the foreground
 */
public final class TrackpadPrefs {
    public static final String MODE_OFF = "off";
    public static final String MODE_GLOBAL = "global";
    public static final String MODE_WHITELIST = "whitelist";

    private static final String PREF = "titan_trackpad";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PACKAGES = "whitelist";
    private static final String KEY_FOLLOW_ORIENT = "follow_orient";

    private final SharedPreferences sp;

    public TrackpadPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getMode() {
        return sp.getString(KEY_MODE, MODE_OFF);
    }

    public void setMode(String mode) {
        sp.edit().putString(KEY_MODE, mode).apply();
    }

    /** Default true — mouse axes should track screen rotation out of the box. */
    public boolean getFollowOrient() {
        return sp.getBoolean(KEY_FOLLOW_ORIENT, true);
    }

    public void setFollowOrient(boolean on) {
        sp.edit().putBoolean(KEY_FOLLOW_ORIENT, on).apply();
    }

    public Set<String> getWhitelist() {
        return new HashSet<>(sp.getStringSet(KEY_PACKAGES, new HashSet<String>()));
    }

    public void setWhitelist(Set<String> pkgs) {
        sp.edit().putStringSet(KEY_PACKAGES, new HashSet<>(pkgs)).apply();
    }

    public void addPackage(String pkg) {
        Set<String> s = getWhitelist();
        s.add(pkg);
        setWhitelist(s);
    }

    public void removePackage(String pkg) {
        Set<String> s = getWhitelist();
        s.remove(pkg);
        setWhitelist(s);
    }

    public boolean isWhitelisted(String pkg) {
        return pkg != null && getWhitelist().contains(pkg);
    }
}
