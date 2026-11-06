/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import android.app.WallpaperColors;

import com.google.ux.material.libmonet.hct.Hct;

/** Cached HCT tones of the current HOME wallpaper, independent of the selected system color theme. */
public final class WallpaperTonalPalette {
    public final int seed;
    public final int lightSurface;
    public final int darkSurface;

    private WallpaperTonalPalette(int seed, int lightSurface, int darkSurface) {
        this.seed = seed;
        this.lightSurface = lightSurface;
        this.darkSurface = darkSurface;
    }

    public static WallpaperTonalPalette from(WallpaperColors colors) {
        int seed = colors == null ? 0xFF60747D : colors.getPrimaryColor().toArgb();
        Hct source = Hct.fromInt(seed);
        // Keep genuine neutral wallpapers neutral. Compress colourful wallpapers without losing
        // their actual hue; a pinned Monet accent must not turn a blue photograph into brown tiles.
        double chroma = source.getChroma() < 5 ? 2 : Math.min(32, Math.max(16,
                source.getChroma() * .8));
        return new WallpaperTonalPalette(seed,
                Hct.from(source.getHue(), chroma, 88).toInt(),
                Hct.from(source.getHue(), chroma, 25).toInt());
    }
}
