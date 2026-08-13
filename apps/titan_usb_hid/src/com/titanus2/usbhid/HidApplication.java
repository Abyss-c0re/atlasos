package com.titanus2.usbhid;

import android.app.Application;

/**
 * Process entry: keep control plane honest when HID process is warm without FGS
 * (HostMouseReceiver / broadcasts can start the package without MainActivity).
 * <p>
 * Host-safety (2026-07-17): if FGS is not running, <b>always</b> force host-safe
 * idle — ghost session=1 after kill left USB as a second PC keyboard.
 */
public class HidApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        HidAppContext.init(this);
        // B2 rootless: specials queues must exist before first exclusive Start
        // (Controls hostRemoteOnly / pad-agent may not have created them yet).
        try { HidControl.ensureSpecialsQueues(this); } catch (Exception ignored) {}
        try {
            if (!HidSessionService.isRunning()) {
                // Always clear ghost exclusive + restore USB (not only when sess=0).
                HidControl.forceHostSafeIdle(this);
            }
        } catch (Exception ignored) {}
    }
}
