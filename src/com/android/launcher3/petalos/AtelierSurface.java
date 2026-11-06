/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import android.app.WallpaperColors;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.PetalLauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.util.OnColorHintListener;
import com.android.launcher3.util.OnWallpaperColorsChangedListener;
import com.android.launcher3.util.WallpaperColorHints;
import com.android.launcher3.util.Themes;

/** matte shelf material, monet neutrals not the loud accent. no blur, no idle redraws */
public final class AtelierSurface implements View.OnAttachStateChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener, OnColorHintListener,
        OnWallpaperColorsChangedListener, ComponentCallbacks {
    private final View mView;
    private final boolean mFolder;
    private final SharedPreferences mPrefs;
    private final WallpaperColorHints mHints;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final RectF mEdgeBounds = new RectF();
    private final float mStroke;
    private LinearGradient mGradient;
    private int mTopColor;
    private int mBottomColor;
    private int mEdgeColor;
    private boolean mEnabled;
    private boolean mListening;
    private String mPreset = "atelier";
    private final RectF mShadowBounds = new RectF();

    public AtelierSurface(View view, boolean folder) {
        mView = view;
        mFolder = folder;
        Context context = view.getContext();
        // null before first unlock, defaults it is
        mPrefs = PetalLauncherPrefs.prefs(context);
        mHints = WallpaperColorHints.get(context);
        mStroke = context.getResources().getDisplayMetrics().density * .5f;
        updatePalette();
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mListening) return;
        mListening = true;
        if (mPrefs != null) mPrefs.registerOnSharedPreferenceChangeListener(this);
        mHints.registerOnColorHintsChangedListener(this);
        mHints.registerOnWallpaperColorsChangedListener(this);
        view.getContext().registerComponentCallbacks(this);
        updatePalette();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (!mListening) return;
        mListening = false;
        if (mPrefs != null) mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mHints.unregisterOnColorsChangedListener(this);
        mHints.unregisterOnWallpaperColorsChangedListener(this);
        view.getContext().unregisterComponentCallbacks(this);
    }

    public void dispose() {
        onViewDetachedFromWindow(mView);
        mView.removeOnAttachStateChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PetalLauncherPrefs.ATELIER_PRESET.equals(key)
                || PetalLauncherPrefs.SHELF_SURFACE.equals(key)
                || PetalLauncherPrefs.WALLPAPER_HARMONY.equals(key)) updatePalette();
    }

    @Override
    public void onColorHintsChanged(int hints) {
        updatePalette();
    }

    @Override
    public void onWallpaperColorsChanged() {
        updatePalette();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        updatePalette();
    }

    @Override
    public void onLowMemory() { }

    private void updatePalette() {
        Context context = mView.getContext();
        boolean harmony = PetalLauncherPrefs.enabled(context, PetalLauncherPrefs.WALLPAPER_HARMONY);
        boolean darkTheme = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // folders go off the theme text, shelf can chase the wallpaper
        boolean dark = mFolder ? ColorUtils.calculateLuminance(
                Themes.getAttrColor(context, R.attr.folderTextColor)) > .5 : !harmony ? darkTheme
                : (mHints.getHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0;
        mPreset = PetalLauncherPrefs.preset(context);
        boolean gallery = "gallery".equals(mPreset);
        boolean bloom = "bloom".equals(mPreset);
        mEnabled = mFolder || (!gallery
                && PetalLauncherPrefs.enabled(context, PetalLauncherPrefs.SHELF_SURFACE));
        int neutral = dark ? 0xFF252F2A : 0xFFEDF2EC;
        if (harmony) {
            // keep it green-grey even when monet goes full orange
            neutral = ColorUtils.blendARGB(neutral, dark ? mHints.getPalette().darkSurface
                    : mHints.getPalette().lightSurface, .45f);
        }
        // bloom gets a dash of the quiet secondary, never accent1_500
        if (bloom && harmony) {
            neutral = ColorUtils.blendARGB(neutral, dark ? mHints.getPalette().darkSurface
                    : mHints.getPalette().lightSurface, .35f);
        }
        // thick enough that text survives a white wallpaper
        int opacity = mFolder ? 250 : bloom ? 232 : 246;
        mTopColor = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(neutral,
                Color.WHITE, dark ? .035f : .12f), opacity);
        mBottomColor = ColorUtils.setAlphaComponent(neutral, opacity);
        mEdgeColor = dark ? 0x20FFFFFF : 0x180E2016;
        mGradient = null;
        if (!mFolder) mView.requestLayout();
        mView.invalidate();
    }

    public float getShelfRadius(float height) {
        return height * ("bloom".equals(mPreset) ? .48f : .28f);
    }

    public void draw(Canvas canvas, float left, float top, float width, float height,
            float radius, int alpha) {
        if (!mEnabled || width <= 0 || height <= 0 || alpha <= 0) return;
        if (mGradient == null || mBounds.left != left || mBounds.top != top
                || mBounds.width() != width || mBounds.height() != height) {
            mBounds.set(left, top, left + width, top + height);
            mGradient = new LinearGradient(left, top, left, top + height,
                    mTopColor, mBottomColor, Shader.TileMode.CLAMP);
        }
        radius = Math.min(radius, Math.min(width, height) / 2f);
        // cheapo contact shadow, 3 rings, tracks the bounds mid-transform
        if (!mFolder) {
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.FILL);
            float density = mView.getResources().getDisplayMetrics().density;
            for (int layer = 3; layer >= 1; layer--) {
                mShadowBounds.set(mBounds);
                mShadowBounds.inset(-layer * density * .5f, -layer * density * .5f);
                mShadowBounds.offset(0, layer * density * .7f);
                mPaint.setColor(Color.BLACK);
                mPaint.setAlpha(Math.round(7f * alpha / 255f));
                canvas.drawRoundRect(mShadowBounds, radius + layer * density * .5f,
                        radius + layer * density * .5f, mPaint);
            }
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(mGradient);
        mPaint.setAlpha(alpha);
        canvas.drawRoundRect(mBounds, radius, radius, mPaint);
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mStroke);
        mPaint.setColor(mEdgeColor);
        mPaint.setAlpha(Math.round(Color.alpha(mEdgeColor) * alpha / 255f));
        mEdgeBounds.set(mBounds);
        mEdgeBounds.inset(mStroke / 2f, mStroke / 2f);
        canvas.drawRoundRect(mEdgeBounds, Math.max(0, radius - mStroke / 2f),
                Math.max(0, radius - mStroke / 2f), mPaint);
    }
}
