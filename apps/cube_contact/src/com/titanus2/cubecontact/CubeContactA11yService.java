package com.titanus2.cubecontact;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Unprivileged automation surface — shared nanobot plane without root. */
public class CubeContactA11yService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* observe only for now */ }
    @Override public void onInterrupt() {}
}
