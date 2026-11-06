/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

import android.app.WallpaperColors;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.ux.material.libmonet.hct.Hct;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 36)
public class WallpaperTonalPaletteTest {
    @Test
    public void bluePhotographStaysBlueInBothAppearances() {
        int seed = 0xFF365D7D;
        WallpaperTonalPalette palette = palette(seed);
        assertSameHue(seed, palette.darkSurface);
        assertSameHue(seed, palette.lightSurface);
    }

    @Test
    public void replacingWarmWallpaperWithBlueChangesThePalette() {
        WallpaperTonalPalette warm = palette(0xFFAC6634);
        WallpaperTonalPalette blue = palette(0xFF365D7D);
        assertNotEquals(warm.seed, blue.seed);
        assertNotEquals(warm.darkSurface, blue.darkSurface);
        assertTrue(hueDistance(Hct.fromInt(warm.darkSurface).getHue(),
                Hct.fromInt(blue.darkSurface).getHue()) > 90);
    }

    @Test
    public void neutralWallpaperDoesNotAcquireStrongTint() {
        WallpaperTonalPalette palette = palette(Color.GRAY);
        assertTrue(Hct.fromInt(palette.darkSurface).getChroma() < 6);
        assertTrue(Hct.fromInt(palette.lightSurface).getChroma() < 6);
    }

    @Test
    public void highlySaturatedWallpaperHasBoundedUiChroma() {
        WallpaperTonalPalette palette = palette(Color.RED);
        assertTrue(Hct.fromInt(palette.darkSurface).getChroma() < 35);
        assertTrue(Hct.fromInt(palette.lightSurface).getChroma() < 35);
    }

    private static WallpaperTonalPalette palette(int color) {
        return WallpaperTonalPalette.from(new WallpaperColors(Color.valueOf(color), null, null));
    }

    private static void assertSameHue(int seed, int surface) {
        assertTrue("Wallpaper hue was replaced", hueDistance(Hct.fromInt(seed).getHue(),
                Hct.fromInt(surface).getHue()) < 8);
    }

    private static double hueDistance(double first, double second) {
        double distance = Math.abs(first - second);
        return Math.min(distance, 360 - distance);
    }
}
