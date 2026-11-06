/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.function.Consumer;

/** blurred wallpaper dock w/ a hairline top lip, thicker tint if blur's off */
public final class GlassDock implements View.OnAttachStateChangeListener {
    private static final int TINT_OVER_BLUR_LIGHT = 0x4DFFFFFF;
    private static final int TINT_OVER_BLUR_DARK = 0x33FFFFFF;
    private static final int TINT_NO_BLUR_LIGHT = 0xB3EEF0F3;
    private static final int TINT_NO_BLUR_DARK = 0xB33A3B40;

    private final View mView;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final float mBlurRadius;
    private final float mStroke;
    private final Consumer<Boolean> mBlurListener;
    private WindowManager mWindowManager;
    private BackgroundBlurDrawable mBlur;
    private boolean mBlurEnabled;
    private float mEdgeTop = Float.NaN;
    private float mEdgeHeight;
    private float mLastAlpha = -1f;
    private final android.view.ViewTreeObserver.OnPreDrawListener mAlphaWatch;

    public GlassDock(View view) {
        mView = view;
        float density = view.getResources().getDisplayMetrics().density;
        mBlurRadius = 30f * density;
        mStroke = .75f * density;
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeWidth(mStroke);
        mAlphaWatch = () -> {
            // parent fades dont re-record us, poke it so the blur fades too
            if (mBlur != null && effectiveAlpha() != mLastAlpha) mView.invalidate();
            return true;
        };
        mBlurListener = enabled -> {
            mBlurEnabled = enabled;
            mView.invalidate();
        };
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
    }

    // caller does layout, we just paint the thing
    public void draw(Canvas canvas, float left, float top, float right, float bottom,
            float radius) {
        if (right <= left || bottom <= top) return;
        mBounds.set(left, top, right, bottom);
        radius = Math.min(radius, mBounds.height() / 2f);
        boolean blurred = drawBlur(canvas, radius);
        boolean dark = (mView.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mFill.setColor(blurred ? (dark ? TINT_OVER_BLUR_DARK : TINT_OVER_BLUR_LIGHT)
                : (dark ? TINT_NO_BLUR_DARK : TINT_NO_BLUR_LIGHT));
        canvas.drawRoundRect(mBounds, radius, radius, mFill);
        // lit from up top, dead by the middle
        if (mEdgeTop != top || mEdgeHeight != mBounds.height()) {
            mEdgeTop = top;
            mEdgeHeight = mBounds.height();
            mEdge.setShader(new LinearGradient(0, top, 0, bottom,
                    new int[] {0x59FFFFFF, 0x14FFFFFF, 0x0AFFFFFF},
                    new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP));
        }
        mBounds.inset(mStroke / 2f, mStroke / 2f);
        canvas.drawRoundRect(mBounds, radius - mStroke / 2f, radius - mStroke / 2f, mEdge);
    }

    private boolean drawBlur(Canvas canvas, float radius) {
        ViewRootImpl root = mView.getViewRootImpl();
        if (!mBlurEnabled || root == null || !canvas.isHardwareAccelerated()) {
            if (mBlur != null) mBlur.setVisible(false, false);
            return false;
        }
        if (mBlur == null) {
            mBlur = root.createBackgroundBlurDrawable();
            mBlur.setColor(Color.TRANSPARENT);
            mBlur.setCallback(mView);
        }
        mLastAlpha = effectiveAlpha();
        int alpha = Math.round(Math.max(0f, Math.min(1f, mLastAlpha)) * 255);
        mBlur.setVisible(alpha > 0, false);
        mBlur.setAlpha(alpha);
        if (alpha == 0) return false;
        mBlur.setBounds(Math.round(mBounds.left), Math.round(mBounds.top),
                Math.round(mBounds.right), Math.round(mBounds.bottom));
        mBlur.setCornerRadius(radius);
        mBlur.setBlurRadius(Math.round(mBlurRadius));
        mBlur.draw(canvas);
        return true;
    }

    // SF owns the blur so view alpha doesnt reach it, frost hung around after launches
    private float effectiveAlpha() {
        float alpha = 1f;
        for (View v = mView; v != null; ) {
            alpha *= v.getAlpha();
            v = v.getParent() instanceof View parent ? parent : null;
        }
        return alpha;
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        view.getViewTreeObserver().addOnPreDrawListener(mAlphaWatch);
        mWindowManager = view.getContext().getSystemService(WindowManager.class);
        if (mWindowManager == null) return;
        mBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        mWindowManager.addCrossWindowBlurEnabledListener(view.getContext().getMainExecutor(),
                mBlurListener);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (view.getViewTreeObserver().isAlive()) {
            view.getViewTreeObserver().removeOnPreDrawListener(mAlphaWatch);
        }
        if (mWindowManager != null) {
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
            mWindowManager = null;
        }
        if (mBlur != null) {
            mBlur.setBlurRadius(0);
            mBlur.setCallback(null);
            mBlur = null;
        }
    }
}
