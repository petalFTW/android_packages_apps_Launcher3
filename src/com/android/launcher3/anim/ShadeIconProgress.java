/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.anim;

/** Reversible mapping: the entrance starts as the panel drops below roughly 80% expansion. */
public final class ShadeIconProgress {
    private static final float ENTRANCE_START_EXPANSION = .8f;

    private ShadeIconProgress() { }

    public static float fromExpansion(float expansion) {
        if (!Float.isFinite(expansion)) throw new IllegalArgumentException("Non-finite expansion");
        return Math.max(0f, Math.min(1f,
                (ENTRANCE_START_EXPANSION - expansion) / ENTRANCE_START_EXPANSION));
    }
}
