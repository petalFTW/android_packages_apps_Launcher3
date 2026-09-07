/*
 * Copyright (C) 2026 petalOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.petalos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.view.Surface;

import com.android.launcher3.LauncherFiles;

/**
 * petalOS recents layout selection.
 *
 * <p>Stock keeps the one-card-per-page carousel. Grid reuses the built-in 2-row grid overview
 * (previously tablet-only) by flipping {@code OverviewReleaseFlags.enableGridOnlyOverview()} and
 * {@code OverviewState#displayOverviewTasksAsGrid}, which drive the grid translations, row
 * assignment and sizing throughout RecentsView.
 */
public final class PetalRecentsPrefs {

    public static final String PREF_RECENTS_LAYOUT = "pref_recents_layout";
    public static final String LAYOUT_STOCK = "0";
    public static final String LAYOUT_GRID = "1";
    public static final String LAYOUT_LIST = "2";
    public static final String LAYOUT_ROUDABOUT = "3";

    private static volatile String sLayout = LAYOUT_STOCK;
    /** App context captured at init time so lazy reads can happen without a caller. */
    private static volatile Context sAppContext;
    /** True once the persisted value has actually been read into sLayout. */
    private static volatile boolean sReadPersisted;
    /** Quickstep's touch rotation; Launcher itself commonly remains portrait-locked in overview. */
    private static volatile int sRecentsRotation = Surface.ROTATION_0;

    private PetalRecentsPrefs() {}

    /**
     * Mirrors the persisted choice into the process-wide static. Call at app start.
     *
     * <p>Because some component in this app is direct-boot aware, {@code Application.onCreate()}
     * can run before the user unlocks the device, while credential-encrypted (CE) storage is
     * still locked. If the process starts in that window — after the boot-time
     * {@code ACTION_USER_UNLOCKED} broadcast — the one-shot retry receiver never fires and the
     * static would stay at the default for the whole process lifetime. The app context is
     * therefore always captured here, and every getter lazily retries the read once CE storage
     * becomes available.
     */
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        if (sReadPersisted) return;
        if (!isUserUnlocked()) return;
        sLayout = prefs(sAppContext).getString(PREF_RECENTS_LAYOUT, LAYOUT_STOCK);
        sReadPersisted = true;
    }

    private static boolean isUserUnlocked() {
        UserManager um = sAppContext == null
                ? null : sAppContext.getSystemService(UserManager.class);
        return um == null || um.isUserUnlocked();
    }

    /** Retries the persisted read if the first init ran before CE storage unlocked. */
    private static void ensureRead() {
        if (sReadPersisted || sAppContext == null || !isUserUnlocked()) return;
        sLayout = prefs(sAppContext).getString(PREF_RECENTS_LAYOUT, LAYOUT_STOCK);
        sReadPersisted = true;
    }

    /** Updates the persisted choice and the static mirror immediately. */
    public static void setLayout(Context context, String layout) {
        sLayout = layout;
        sReadPersisted = true;
        prefs(context).edit().putString(PREF_RECENTS_LAYOUT, layout).apply();
    }

    public static String getLayout() {
        ensureRead();
        return sLayout;
    }

    public static boolean isGrid() {
        ensureRead();
        // The overview Activity stays portrait-locked while Quickstep rotates its coordinate space
        // for a gesture from a landscape app. Resources.configuration therefore still says
        // portrait at exactly the point where the phone grid must be disabled. Use Quickstep's
        // touch rotation instead, and fall back to the stock carousel for both landscape turns.
        return LAYOUT_GRID.equals(sLayout)
                && sRecentsRotation != Surface.ROTATION_90
                && sRecentsRotation != Surface.ROTATION_270;
    }

    /** Keeps the process-wide grid decision aligned with Quickstep's active coordinate space. */
    public static void setRecentsRotation(int rotation) {
        sRecentsRotation = rotation;
    }

    public static boolean isList() {
        ensureRead();
        return LAYOUT_LIST.equals(sLayout);
    }

    /** Infinity-X's "Scale effect": the focused card is full-size and its neighbors recede. */
    public static boolean isRoudabout() {
        ensureRead();
        return LAYOUT_ROUDABOUT.equals(sLayout);
    }

    /**
     * petalOS: extra vertical gap (px) between the two grid rows, added on top of the stock
     * row spacing. Used consistently by RecentsView (row pitch) and
     * BaseContainerInterface (card sizing / vertical centering).
     */
    public static int getGridRowExtraGapPx(Context context) {
        return Math.round(24 * context.getResources().getDisplayMetrics().density);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(
                LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
    }
}
