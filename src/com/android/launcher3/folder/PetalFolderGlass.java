/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import android.graphics.Canvas;
import android.view.View;

import com.android.launcher3.petalos.AtelierSurface;

/** Compatibility adapter for the existing folder reveal and preview rendering paths. */
final class PetalFolderGlass {
    private final AtelierSurface mSurface;

    PetalFolderGlass(View view) {
        mSurface = new AtelierSurface(view, true);
    }

    void dispose() {
        mSurface.dispose();
    }

    void draw(Canvas canvas, float left, float top, float width, float height,
            float radius, int alpha) {
        mSurface.draw(canvas, left, top, width, height, radius, alpha);
    }
}
