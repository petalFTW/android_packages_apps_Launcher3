/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.R;
import com.android.launcher3.util.Themes;

import org.petalos.config.PetalConfig;

import java.util.Random;

/** QS tile glass for folders. tint, grain, lit rim, parked sheen. no island lens shit. */
final class PetalFolderGlass {
    // straight outta PetalQsSkin, keep these in sync or it looks off again
    private static final int TILE_DARK = 0x33333D;
    private static final int TILE_LIGHT = 0xFBFBFE;
    private static final int FILL_FROSTED = 0x85;
    private static final int FILL_SOLID = 0xE6;
    private static final int[] RIM_DARK = {0x99FFFFFF, 0x14FFFFFF};
    private static final int[] RIM_LIGHT = {0xFFFFFFFF, 0x1F000000};
    private static final float SHEEN_POS = .16f;
    private static final int SHEEN_ALPHA = 0x55;
    private static final int NOISE = 96;
    private static Bitmap sNoise;

    private final View mView;
    private final boolean mDark;
    private final float mRimWidth;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient mRimShader;
    private final LinearGradient mSheenShader;
    private final Matrix mMatrix = new Matrix();
    private final RectF mBounds = new RectF();
    private final RectF mRimBounds = new RectF();
    private final Path mPath = new Path();
    private boolean mFrosted;

    PetalFolderGlass(View view) {
        mView = view;
        float density = view.getResources().getDisplayMetrics().density;
        mRimWidth = 1.1f * density;
        // folder text follows the wallpaper, not night mode, so pick the glass off that
        mDark = ColorUtils.calculateLuminance(
                Themes.getAttrColor(view.getContext(), R.attr.folderTextColor)) > .5;
        int[] rim = mDark ? RIM_DARK : RIM_LIGHT;
        mRimShader = new LinearGradient(0, 0, 0, 1, rim[0], rim[1], Shader.TileMode.CLAMP);
        mRim.setStyle(Paint.Style.STROKE);
        mRim.setStrokeWidth(mRimWidth);
        mRim.setShader(mRimShader);
        mSheenShader = new LinearGradient(0, 0, 1, 0,
                new int[] {0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        mSheen.setShader(mSheenShader);
        mGrain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT));
        setFrosted(false);
    }

    /** open folder only, the icon previews sit on raw wallpaper so they stay solid */
    void refreshBackdrop() {
        WindowManager wm = mView.getContext().getSystemService(WindowManager.class);
        setFrosted(wm != null && wm.isCrossWindowBlurEnabled()
                && PetalConfig.getAppsBlurScale(mView.getContext()) > .05f);
    }

    private void setFrosted(boolean frosted) {
        mFrosted = frosted;
        mView.invalidate();
    }

    void dispose() { }

    void draw(Canvas canvas, float left, float top, float width, float height,
            float radius, int alpha) {
        if (width <= 0 || height <= 0 || alpha <= 0) return;
        float a = alpha / 255f;
        radius = Math.min(radius, Math.min(width, height) / 2f);
        mBounds.set(left, top, left + width, top + height);
        mPath.reset();
        mPath.addRoundRect(mBounds, radius, radius, Path.Direction.CW);

        int fill = mFrosted ? FILL_FROSTED : FILL_SOLID;
        mFill.setColor(((mDark ? TILE_DARK : TILE_LIGHT) & 0xFFFFFF)
                | (Math.round(fill * a) << 24));
        canvas.drawPath(mPath, mFill);

        mGrain.setAlpha(Math.round((mDark ? 0x16 : 0x10) * a));
        canvas.drawPath(mPath, mGrain);

        mMatrix.setScale(1, height);
        mMatrix.postTranslate(0, top);
        mRimShader.setLocalMatrix(mMatrix);
        mRim.setAlpha(Math.round(255 * a));
        mRimBounds.set(mBounds);
        mRimBounds.inset(mRimWidth / 2f, mRimWidth / 2f);
        float rimRadius = Math.max(0, radius - mRimWidth / 2f);
        canvas.drawRoundRect(mRimBounds, rimRadius, rimRadius, mRim);

        drawSheen(canvas, a);
    }

    // same tilted band the qs tiles park at 0.16, not animated, nobody asked for a disco
    private void drawSheen(Canvas canvas, float a) {
        float w = mBounds.width();
        float h = mBounds.height();
        float band = w * .4f;
        float x = mBounds.left - band + SHEEN_POS * (w + band);
        mMatrix.setScale(band, 1);
        mMatrix.postTranslate(x, 0);
        mSheenShader.setLocalMatrix(mMatrix);
        mSheen.setAlpha(Math.round(SHEEN_ALPHA * a));
        int save = canvas.save();
        canvas.clipPath(mPath);
        canvas.rotate(-22f, mBounds.centerX(), mBounds.centerY());
        canvas.drawRect(x, mBounds.top - h, x + band, mBounds.bottom + h, mSheen);
        canvas.restoreToCount(save);
    }

    private static synchronized Bitmap noise() {
        if (sNoise != null) return sNoise;
        int[] px = new int[NOISE * NOISE];
        Random rnd = new Random(0x5EED);
        for (int i = 0; i < px.length; i++) {
            int a = rnd.nextInt(0x40);
            px[i] = rnd.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
        }
        sNoise = Bitmap.createBitmap(px, NOISE, NOISE, Bitmap.Config.ARGB_8888);
        return sNoise;
    }
}
