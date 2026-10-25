package com.android.launcher3;

import android.content.Context;

/** Preferences for optional petalOS launcher motion and the dock. */
public final class PetalLauncherPrefs {
    public static final String UNLOCK_ANIMATION = "petal_unlock_animation";
    public static final String HOME_ANIMATION = "petal_home_animation";
    public static final String DRAWER_ANIMATION = "petal_drawer_animation";
    public static final String DOCK_ENABLED = "petal_dock_enabled";

    private PetalLauncherPrefs() { }

    public static boolean enabled(Context context, String key) {
        // drawer entrance and the dock are on out of the box, the fancy unlock stuff isn't
        boolean defaultValue = DRAWER_ANIMATION.equals(key) || DOCK_ENABLED.equals(key);
        return context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE).getBoolean(key, defaultValue);
    }
}
