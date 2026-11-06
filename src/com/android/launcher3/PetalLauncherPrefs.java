package com.android.launcher3;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

import androidx.annotation.Nullable;

/** Preferences for optional petalOS launcher motion and the dock. */
public final class PetalLauncherPrefs {
    public static final String UNLOCK_ANIMATION = "petal_unlock_animation";
    public static final String HOME_ANIMATION = "petal_home_animation";
    public static final String DRAWER_ANIMATION = "petal_drawer_animation";
    public static final String DOCK_ENABLED = "petal_dock_enabled";
    public static final String ATELIER_PRESET = "petal_atelier_preset";
    public static final String SHELF_SURFACE = "petal_shelf_surface";
    public static final String LABEL_PROTECTION = "petal_label_protection";
    public static final String WALLPAPER_HARMONY = "petal_wallpaper_harmony";
    public static final String REDUCED_MOTION = "petal_reduced_motion";

    private PetalLauncherPrefs() { }

    public static boolean enabled(Context context, String key) {
        // drawer entrance and the dock are on out of the box, the fancy unlock stuff isn't
        boolean defaultValue = DRAWER_ANIMATION.equals(key) || DOCK_ENABLED.equals(key)
                || SHELF_SURFACE.equals(key) || LABEL_PROTECTION.equals(key)
                || WALLPAPER_HARMONY.equals(key);
        SharedPreferences prefs = prefs(context);
        return prefs == null ? defaultValue : prefs.getBoolean(key, defaultValue);
    }

    /**
     * Launcher prefs live in credential-encrypted storage. The taskbar inflates icons before the
     * first unlock, so callers must fall back to defaults while this returns null.
     */
    /** Home preset: glass (default), atelier, gallery or bloom. */
    public static String preset(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs == null ? PRESET_GLASS : prefs.getString(ATELIER_PRESET, PRESET_GLASS);
    }

    public static final String PRESET_GLASS = "glass";
    public static final String PRESET_LINE = "line";

    @Nullable
    public static SharedPreferences prefs(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) return null;
        return context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE);
    }
}
