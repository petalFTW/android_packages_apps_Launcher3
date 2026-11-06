/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.PetalLauncherPrefs;

/** Local contrast protection: uses the actual laid-out, ellipsized lines, never the wallpaper. */
public final class AtelierLabelProtection implements View.OnAttachStateChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private final TextView mView;
    private final SharedPreferences mPrefs;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final float mDensity;
    private boolean mEnabled;

    public AtelierLabelProtection(TextView view) {
        mView = view;
        mDensity = view.getResources().getDisplayMetrics().density;
        // Null before the first unlock; the default holds until the view is rebuilt.
        mPrefs = PetalLauncherPrefs.prefs(view.getContext());
        mEnabled = mPrefs == null || mPrefs.getBoolean(PetalLauncherPrefs.LABEL_PROTECTION, true);
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mPrefs == null) return;
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(mPrefs, PetalLauncherPrefs.LABEL_PROTECTION);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (mPrefs != null) mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PetalLauncherPrefs.LABEL_PROTECTION.equals(key)) {
            mEnabled = prefs.getBoolean(key, true);
            mView.invalidate();
        }
    }

    public void draw(Canvas canvas) {
        Layout layout = mView.getLayout();
        int textColor = mView.getCurrentTextColor();
        if (!mEnabled || layout == null || mView.getText().length() == 0
                || Color.alpha(textColor) == 0) return;
        float left = layout.getWidth();
        float right = 0;
        int lines = Math.min(layout.getLineCount(), mView.getMaxLines());
        if (lines == 0) return;
        for (int line = 0; line < lines; line++) {
            left = Math.min(left, layout.getLineLeft(line));
            right = Math.max(right, layout.getLineRight(line));
        }
        if (right <= left) return;
        float x = mView.getCompoundPaddingLeft() + mView.getScrollX();
        float y = mView.getExtendedPaddingTop() + mView.getScrollY();
        mBounds.set(Math.max(0, x + left - 4 * mDensity), y,
                Math.min(mView.getWidth(), x + right + 4 * mDensity),
                Math.min(mView.getHeight(), y + layout.getLineBottom(lines - 1)));
        // A deterministic fallback for detailed, unknown and live wallpapers. The existing text
        // theme chooses the foreground; an opposing local backing protects it even at a seam.
        boolean lightText = ColorUtils.calculateLuminance(textColor) > .5;
        mPaint.setColor(lightText ? Color.BLACK : Color.WHITE);
        mPaint.setAlpha(Math.round((lightText ? 176 : 224) * Color.alpha(textColor) / 255f));
        canvas.drawRoundRect(mBounds, 5 * mDensity, 5 * mDensity, mPaint);
    }
}
