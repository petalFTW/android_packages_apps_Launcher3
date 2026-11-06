/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

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

/**
 * Fits legacy application artwork by visible alpha mass, before Launcher3 caches it. Native
 * adaptive layers are never rescaled or cropped. Icon packs and custom shortcuts bypass this path.
 */
public final class AtelierIconNormalizer {
    public static final String CACHE_VERSION = "atelier-optical-2";

    private AtelierIconNormalizer() { }

    public static Drawable normalize(Resources resources, Drawable icon, int densityDpi) {
        if (icon == null || icon instanceof AdaptiveIconDrawable) return icon;
        int size = Math.max(96, Math.min(384, Math.round(48f * densityDpi / 160f)));
        Bitmap sample = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample);
        Rect oldBounds = new Rect(icon.getBounds());
        int width = icon.getIntrinsicWidth();
        int height = icon.getIntrinsicHeight();
        float aspect = width > 0 && height > 0 ? (float) width / height : 1;
        int drawWidth = aspect > 1 ? size : Math.max(1, Math.round(size * aspect));
        int drawHeight = aspect < 1 ? size : Math.max(1, Math.round(size / aspect));
        icon.setBounds((size - drawWidth) / 2, (size - drawHeight) / 2,
                (size + drawWidth) / 2, (size + drawHeight) / 2);
        try {
            icon.draw(canvas);
        } finally {
            icon.setBounds(oldBounds);
        }

        int[] pixels = new int[size * size];
        sample.getPixels(pixels, 0, size, 0, 0, size, size);
        int left = size, top = size, right = -1, bottom = -1;
        double mass = 0, centerX = 0, centerY = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int alpha = Color.alpha(pixels[y * size + x]);
                if (alpha == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
                double weight = alpha / 255.0;
                mass += weight;
                centerX += (x + .5) * weight;
                centerY += (y + .5) * weight;
            }
        }
        // Full-bleed legacy artwork already has its own surface. Rewrapping it would create a
        // conspicuous white frame, especially around dark icon backgrounds. Keep its native path.
        if (mass < 1 || mass > size * size * .94) {
            sample.recycle();
            return icon;
        }
        // Equalize filled area; round silhouettes receive a modest optical overshoot. Keep every
        // nontransparent pixel inside the adaptive safe zone, including thin logo extremities.
        float extent = Math.max(right - left + 1, bottom - top + 1);
        float scale = Math.min(1.6f, Math.min(.72f * size / extent,
                (float) Math.sqrt(.36 * size * size / mass)));
        float cx = (left + right + 1) / 2f;
        float cy = (top + bottom + 1) / 2f;
        cx += Math.max(-size * .025f, Math.min(size * .025f, (float) (centerX / mass) - cx));
        cy += Math.max(-size * .025f, Math.min(size * .025f, (float) (centerY / mass) - cy));
        double maxRadiusSquared = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (Color.alpha(pixels[y * size + x]) == 0) continue;
                double dx = Math.abs(x + .5 - cx) + .5;
                double dy = Math.abs(y + .5 - cy) + .5;
                maxRadiusSquared = Math.max(maxRadiusSquared, dx * dx + dy * dy);
            }
        }
        // Protect diagonal extremities too: width/height alone cannot guarantee circular safety.
        scale = Math.min(scale, (float) (.44 * size / Math.sqrt(maxRadiusSquared)));
        // Adaptive foreground layers extend beyond their mask by getExtraInsetFraction().
        float layerScale = scale / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction());
        Bitmap fitted = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas output = new Canvas(fitted);
        output.translate(size / 2f, size / 2f);
        output.scale(layerScale, layerScale);
        output.translate(-cx, -cy);
        output.drawBitmap(sample, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        sample.recycle();
        // A neutral opaque wrapper preserves original colours and the system's normal adaptive
        // masking/shadow/badging paths. Never infer a monochrome logo from this coloured raster.
        return new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE),
                new BitmapDrawable(resources, fitted));
    }
}
