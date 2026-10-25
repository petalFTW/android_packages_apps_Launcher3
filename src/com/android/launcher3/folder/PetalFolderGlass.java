/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.WindowManager;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import java.util.function.Consumer;

// soft tint, bright shoulder, none of that flat grey crap
final class PetalFolderGlass implements View.OnAttachStateChangeListener {
    private final View mView;
    private final WindowManager mWindowManager;
    private final Consumer<Boolean> mBlurListener = enabled -> invalidateView();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private BackgroundBlurDrawable mBackdrop;
    private LinearGradient mSheen;

    PetalFolderGlass(View view) {
        mView = view;
        mWindowManager = view.getContext().getSystemService(WindowManager.class);
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
    }

    @Override public void onViewAttachedToWindow(View view) {
        mWindowManager.addCrossWindowBlurEnabledListener(view.getContext().getMainExecutor(),
                mBlurListener);
        if (view.getViewRootImpl() != null) {
            mBackdrop = view.getViewRootImpl().createBackgroundBlurDrawable();
        }
    }

    @Override public void onViewDetachedFromWindow(View view) {
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        if (mBackdrop != null) {
            mBackdrop.setVisible(false, false);
            mBackdrop.setBlurRadius(0);
            mBackdrop = null;
        }
    }

    private void invalidateView() { mView.invalidate(); }

    void dispose() {
        onViewDetachedFromWindow(mView);
        mView.removeOnAttachStateChangeListener(this);
    }

    void draw(Canvas canvas, float left, float top, float width, float height,
            float radius, int alpha) {
        if (width <= 0f || height <= 0f || alpha <= 0) {
            if (mBackdrop != null) mBackdrop.setVisible(false, false);
            return;
        }
        float density = mView.getResources().getDisplayMetrics().density;
        boolean blur = mBackdrop != null && canvas.isHardwareAccelerated()
                && mWindowManager.isCrossWindowBlurEnabled();
        if (mSheen == null || mBounds.left != left || mBounds.top != top
                || mBounds.width() != width || mBounds.height() != height) {
            mBounds.set(left, top, left + width, top + height);
            mSheen = new LinearGradient(left, top, left + width * .75f, top + height,
                    new int[]{0x38FFFFFF, 0x06FFFFFF, 0x00FFFFFF, 0x12FFFFFF},
                    new float[]{0f, .35f, .65f, 1f}, Shader.TileMode.CLAMP);
        }
        if (mBackdrop != null) {
            mBackdrop.setVisible(blur, false);
            if (blur) {
                mBackdrop.setBounds(Math.round(left), Math.round(top),
                        Math.round(left + width), Math.round(top + height));
                mBackdrop.setCornerRadius(radius);
                mBackdrop.setBlurRadius(Math.round(24f * density));
                mBackdrop.setAlpha(alpha);
                mBackdrop.draw(canvas);
            }
        }
        int accent = mView.getContext().getColor(android.R.color.system_accent1_500);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(blur ? 0x54171920 : 0xED202229);
        mPaint.setAlpha(Math.round((blur ? 84 : 237) * alpha / 255f));
        canvas.drawRoundRect(mBounds, radius, radius, mPaint);
        mPaint.setColor((accent & 0xFFFFFF) | 0x18000000);
        mPaint.setAlpha(Math.round(24 * alpha / 255f));
        canvas.drawRoundRect(mBounds, radius, radius, mPaint);
        mPaint.setShader(mSheen);
        mPaint.setAlpha(alpha);
        canvas.drawRoundRect(mBounds, radius, radius, mPaint);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(density);
        mPaint.setColor(0x44FFFFFF);
        mPaint.setAlpha(Math.round(68 * alpha / 255f));
        mBounds.inset(density / 2f, density / 2f);
        canvas.drawRoundRect(mBounds, radius, radius, mPaint);
        mBounds.inset(-density / 2f, -density / 2f);
    }
}
