/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Pixel-based checks for logo preservation and optical sizing, using native Skia. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtelierIconNormalizerTest {
    private final Resources mResources = Resources.getSystem();

    @Test
    public void adaptiveIconPreservesLayersAndIdentity() {
        AdaptiveIconDrawable adaptive = new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),
                new ColorDrawable(Color.RED), new ColorDrawable(Color.WHITE));
        assertSame(adaptive, AtelierIconNormalizer.normalize(mResources, adaptive, 320));
    }

    @Test
    public void emptyArtworkFallsBackWithoutInventingLogo() {
        Drawable empty = new ColorDrawable(Color.TRANSPARENT);
        assertSame(empty, AtelierIconNormalizer.normalize(mResources, empty, 320));
    }

    @Test
    public void opaqueLegacySurfaceDoesNotAcquireWhiteFrame() {
        Drawable opaque = new ColorDrawable(Color.BLACK);
        assertSame(opaque, AtelierIconNormalizer.normalize(mResources, opaque, 320));
    }

    @Test
    public void sourceBoundsAreRestored() {
        Drawable source = artwork(false, 12);
        source.setBounds(3, 5, 17, 19);
        AtelierIconNormalizer.normalize(mResources, source, 320);
        assertEquals(new Rect(3, 5, 17, 19), source.getBounds());
    }

    @Test
    public void circleAndSquareHaveSimilarMassWithRoundOvershoot() {
        Bitmap square = foreground(artwork(false, 2));
        Bitmap circle = foreground(artwork(true, 0));
        assertEquals(alphaMass(square), alphaMass(circle), alphaMass(square) * .06);
        assertTrue(visibleWidth(circle) > visibleWidth(square));
    }

    @Test
    public void transparentPaddingDoesNotMakeLogoTiny() {
        Bitmap plain = foreground(artwork(false, 2));
        Bitmap padded = foreground(artwork(false, 12));
        assertEquals(visibleWidth(plain), visibleWidth(padded), 2);
    }

    @Test
    public void diagonalArtworkRemainsInsideMaskSafeCircle() {
        Bitmap foreground = foreground(artwork(false, 2));
        float center = foreground.getWidth() / 2f;
        float radius = foreground.getWidth() * .45f
                / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction());
        for (int y = 0; y < foreground.getHeight(); y++) {
            for (int x = 0; x < foreground.getWidth(); x++) {
                if (Color.alpha(foreground.getPixel(x, y)) < 32) continue;
                double distance = Math.hypot(x + .5 - center, y + .5 - center);
                assertTrue("Artwork escaped safe circle", distance <= radius + 1);
                int color = foreground.getPixel(x, y);
                // Low-alpha, bilinear-filtered edge pixels have premultiplication rounding.
                // Verify retained hue without demanding mathematically exact unpremultiplication.
                assertEquals(0, Color.green(color));
                assertEquals(0, Color.blue(color));
                assertTrue("Original logo colour changed", Color.red(color) >= 240);
            }
        }
    }

    private Drawable artwork(boolean circle, int padding) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.RED);
        if (circle) canvas.drawCircle(48, 48, 48 - padding, paint);
        else canvas.drawRect(padding, padding, 96 - padding, 96 - padding, paint);
        return new BitmapDrawable(mResources, bitmap);
    }

    private Bitmap foreground(Drawable source) {
        AdaptiveIconDrawable result = (AdaptiveIconDrawable) AtelierIconNormalizer.normalize(
                mResources, source, 640);
        return ((BitmapDrawable) result.getForeground()).getBitmap();
    }

    private static double alphaMass(Bitmap bitmap) {
        double mass = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                mass += Color.alpha(bitmap.getPixel(x, y)) / 255.0;
            }
        }
        return mass;
    }

    private static int visibleWidth(Bitmap bitmap) {
        int left = bitmap.getWidth(), right = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 32) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        return right - left + 1;
    }
}
