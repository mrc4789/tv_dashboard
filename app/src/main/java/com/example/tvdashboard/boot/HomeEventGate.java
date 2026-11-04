package com.example.tvdashboard.boot;

import java.util.Set;

/** Pure gate shared by the service and local JVM checks. */
public final class HomeEventGate {
    private long lastLaunch = -2000L;
    public boolean accept(boolean enabled, boolean interactive, boolean locked,
                          Set<String> targets, String pkg, String cls, long now) {
        if (!enabled || !interactive || locked || pkg == null || cls == null ||
                !targets.contains(pkg + "/" + cls) || now - lastLaunch < 2000L) return false;
        lastLaunch = now;
        return true;
    }
}
