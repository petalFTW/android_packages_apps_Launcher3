/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.taskbar;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.IntProperty;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import com.android.internal.graphics.drawable.PetalGlassDrawable;
import com.android.launcher3.R;

// stashed bar still needs contrast over whatever app is underneath
public class StashedHandleView extends View {

    private static final long COLOR_CHANGE_DURATION = 120;

    private final @ColorInt int mStashedHandleLightColor;
    private final @ColorInt int mStashedHandleDarkColor;
    private final Rect mSampledRegion = new Rect();
    private final int[] mTmpArr = new int[2];

    private @Nullable ObjectAnimator mColorChangeAnim;
    private Boolean mIsRegionDark;
    private final PetalGlassDrawable mGlass;
    private final Outline mGlassOutline = new Outline();
    private final Rect mGlassBounds = new Rect();
    private int mHandleColor;
    private static final IntProperty<StashedHandleView> HANDLE_COLOR =
            new IntProperty<>("handleColor") {
                @Override public Integer get(StashedHandleView view) { return view.mHandleColor; }
                @Override public void setValue(StashedHandleView view, int color) {
                    view.setBackgroundColor(color);
                }
            };

    public StashedHandleView(Context context) {
        this(context, null);
    }

    public StashedHandleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StashedHandleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public StashedHandleView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mStashedHandleLightColor = ContextCompat.getColor(context,
                R.color.taskbar_stashed_handle_light_color);
        mStashedHandleDarkColor = ContextCompat.getColor(context,
                R.color.taskbar_stashed_handle_dark_color);
        // tiny bar doesn't need to drag the whole app through a blur pass
        mGlass = new PetalGlassDrawable(this, 100f, 0f, false);
        setBackground(null);
        setWillNotDraw(false);
        setBackgroundColor(mStashedHandleLightColor);
    }

    @Override public void setBackgroundColor(int color) {
        if (mGlass == null) {
            super.setBackgroundColor(color);
            return;
        }
        if (mHandleColor == color) return;
        mHandleColor = color;
        mGlass.setColor(color & 0x00FFFFFF | 0x90000000);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getOutlineProvider() == null) return;
        mGlassOutline.setEmpty();
        getOutlineProvider().getOutline(this, mGlassOutline);
        if (!mGlassOutline.getRect(mGlassBounds)) return;
        mGlass.setBounds(mGlassBounds);
        float radius = Math.max(0f, mGlassOutline.getRadius());
        mGlass.setGlassCorners(radius, radius);
        mGlass.draw(canvas);
    }

    // sampling wants screen coordinates
    public void updateSampledRegion(Rect stashedHandleBounds) {
        getLocationOnScreen(mTmpArr);
        // sample the resting spot, not the animated wobble
        mTmpArr[0] -= Math.round(getTranslationX());
        mTmpArr[1] -= Math.round(getTranslationY());
        mSampledRegion.set(stashedHandleBounds);
        mSampledRegion.offset(mTmpArr[0], mTmpArr[1]);
    }

    public Rect getSampledRegion() {
        return mSampledRegion;
    }

    // dark app gets a light handle, obviously
    public void updateHandleColor(boolean isRegionDark, boolean animate) {
        if (mIsRegionDark != null && mIsRegionDark == isRegionDark) {
            return;
        }
        int newColor = isRegionDark ? mStashedHandleLightColor : mStashedHandleDarkColor;
        mIsRegionDark = isRegionDark;
        if (mColorChangeAnim != null) {
            mColorChangeAnim.cancel();
        }
        if (animate) {
            mColorChangeAnim = ObjectAnimator.ofArgb(this,
                    HANDLE_COLOR, newColor);
            mColorChangeAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mColorChangeAnim = null;
                }
            });
            mColorChangeAnim.setDuration(COLOR_CHANGE_DURATION);
            mColorChangeAnim.start();
        } else {
            setBackgroundColor(newColor);
        }
    }

    // scale from wherever the last gesture left it
    public void animateScale(float scale, long durationMs) {
        ObjectAnimator scaleAnim = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_PROPERTY, scale));
        scaleAnim.setDuration(durationMs).setAutoCancel(true);
        scaleAnim.start();
    }
}
