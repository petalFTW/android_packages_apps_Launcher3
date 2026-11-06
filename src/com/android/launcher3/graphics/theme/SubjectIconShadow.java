/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.graphics.theme;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Shared alpha-only cutout shadow. Created once per cached subject, never during frame drawing. */
final class SubjectIconShadow {
    private final Bitmap mMask;
    private final int mOffsetX;
    private final int mOffsetY;
    private final int mSourceWidth;
    private final int mSourceHeight;

    private SubjectIconShadow(Bitmap mask, int offsetX, int offsetY, int width, int height) {
        mMask = mask;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mSourceWidth = width;
        mSourceHeight = height;
    }

    static SubjectIconShadow create(Bitmap subject) {
        Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
        blur.setMaskFilter(new BlurMaskFilter(Math.max(1f, subject.getWidth() * .012f),
                BlurMaskFilter.Blur.NORMAL));
        int[] offsets = new int[2];
        Bitmap mask = subject.extractAlpha(blur, offsets);
        return new SubjectIconShadow(mask, offsets[0], offsets[1], subject.getWidth(),
                subject.getHeight());
    }

    void draw(Canvas canvas, int size, RectF destination, Paint paint) {
        float scaleX = (float) size / mSourceWidth;
        float scaleY = (float) size / mSourceHeight;
        float left = mOffsetX * scaleX;
        float top = mOffsetY * scaleY + size * .012f;
        destination.set(left, top, left + mMask.getWidth() * scaleX,
                top + mMask.getHeight() * scaleY);
        paint.setColor(Color.BLACK);
        paint.setAlpha(38); // 15% opacity, following the subject silhouette rather than its tile.
        canvas.drawBitmap(mMask, null, destination, paint);
    }
}
