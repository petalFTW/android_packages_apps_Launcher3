/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.anim;

import android.view.View;
import android.view.ViewGroup;

import java.util.Random;

import com.android.launcher3.CellLayout;
import com.android.launcher3.Launcher;
import com.android.launcher3.PetalLauncherPrefs;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;

/** Builds the shared workspace-item cascade for Home returns and shade tracking. */
public final class PetalHomeReturnAnimation {
    private static final long ROW_STAGGER_MS = 8;
    private static final long COLUMN_STAGGER_MS = 5;
    private static final long MAX_STAGGER_MS = 64;
    // Per-icon offset on top of the row/column wave so the icons land individually.
    private static final long MAX_JITTER_MS = 90;
    private static final float RISE_DP = 96f;

    private PetalHomeReturnAnimation() { }

    public static boolean isEnabled(Launcher launcher) {
        return android.animation.ValueAnimator.areAnimatorsEnabled()
                && PetalLauncherPrefs.enabled(launcher, PetalLauncherPrefs.HOME_ANIMATION)
                && !PetalLauncherPrefs.enabled(launcher, PetalLauncherPrefs.REDUCED_MOTION);
    }

    /** Fresh cascade; callers drive progress, settle or prepare it themselves. */
    public static PreparedHomeReveal create(Launcher launcher) {
        return build(launcher, false);
    }

    /** Prepared hidden first frame for covered-Home reveals such as app and recents returns. */
    public static PreparedHomeReveal prepare(Launcher launcher) {
        return build(launcher, true);
    }

    private static PreparedHomeReveal build(Launcher launcher, boolean prepareFirstFrame) {
        PreparedHomeReveal result = new PreparedHomeReveal();
        CellLayout page = (CellLayout) launcher.getWorkspace().getPageAt(
                launcher.getWorkspace().getCurrentPage());
        if (page == null || !isEnabled(launcher)) return result;
        ViewGroup items = page.getShortcutsAndWidgets();
        int columns = launcher.getDeviceProfile().inv.numColumns;
        boolean rtl = page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float rise = RISE_DP * launcher.getResources().getDisplayMetrics().density;
        // Sparse layouts must not wait for nonexistent upper rows before the first icon moves.
        long firstDelay = Long.MAX_VALUE;
        for (int i = 0; i < items.getChildCount(); i++) {
            View item = items.getChildAt(i);
            if (item.getVisibility() == View.VISIBLE) {
                firstDelay = Math.min(firstDelay, delayFor(item, i, columns, rtl));
            }
        }
        if (firstDelay == Long.MAX_VALUE) return result;
        // Drawn once per reveal: a scrubbed shade gesture replays the same order both ways.
        Random jitter = new Random();
        for (int i = 0; i < items.getChildCount(); i++) {
            View item = items.getChildAt(i);
            if (item.getVisibility() != View.VISIBLE) continue;
            long delay = Math.min(MAX_STAGGER_MS, delayFor(item, i, columns, rtl) - firstDelay)
                    + jitter.nextInt((int) MAX_JITTER_MS + 1);
            float startScale = item.getHeight() > item.getWidth() * 1.5f ? 1.03f : 1.08f;
            result.add(item, delay, rise, startScale);
        }
        if (prepareFirstFrame) result.prepare();
        return result;
    }

    private static long delayFor(View item, int index, int columns, boolean rtl) {
        int row = 0, column = index;
        if (item.getLayoutParams() instanceof CellLayoutLayoutParams lp) {
            row = lp.getCellY();
            column = rtl ? columns - 1 - lp.getCellX() : lp.getCellX();
        }
        return Math.max(0, row) * ROW_STAGGER_MS + Math.max(0, column) * COLUMN_STAGGER_MS;
    }
}
