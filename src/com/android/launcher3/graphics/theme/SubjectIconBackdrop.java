/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.graphics.theme;

import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.icons.FastBitmapDrawable;

import java.util.WeakHashMap;
import java.util.function.Consumer;

/** UI-thread-owned, transparent window lens. Software snapshots never change the live backdrop. */
final class SubjectIconBackdrop implements View.OnAttachStateChangeListener {
    private static final int MAX_LENSES_PER_WINDOW = 64;
    private static final WeakHashMap<ViewRootImpl, Integer> SLOTS = new WeakHashMap<>();

    private final FastBitmapDrawable mHost;
    private final Consumer<Boolean> mBlurListener;
    private final ViewTreeObserver.OnPreDrawListener mOwnerCheck;
    private View mView;
    private WindowManager mWindowManager;
    private ViewRootImpl mSlotRoot;
    private BackgroundBlurDrawable mBackdrop;
    private boolean mBlurEnabled;

    SubjectIconBackdrop(FastBitmapDrawable host) {
        mHost = host;
        mOwnerCheck = () -> {
            if (mView != null && (mHost.getCallback() != mView || !mHost.isVisible()
                    || !mView.isShown() || mView.getWindowVisibility() != View.VISIBLE)) {
                release();
            } else if (mBackdrop != null) {
                updateOpacity();
            }
            return true;
        };
        mBlurListener = enabled -> {
            mBlurEnabled = enabled;
            if (!enabled) hideBackdrop();
            mHost.invalidateSelf();
        };
    }

    void draw(Canvas canvas, int size, float cornerRadius, boolean unfiltered) {
        // DoubleShadowBubbleTextView records a second pass clipped to labels only. Do not record
        // this same position-tracked RenderNode in that pass: its clipped occurrence can report
        // positionLost and erase the valid first-pass region.
        if (!canvas.isHardwareAccelerated() || canvas.quickReject(0, 0, size, size)) return;
        if (!(mHost.getCallback() instanceof View owner) || !owner.isAttachedToWindow()
                || !unfiltered || !mHost.isVisible()) {
            release();
            return;
        }
        if (mView != owner) {
            release();
            mView = owner;
            owner.addOnAttachStateChangeListener(this);
            owner.getViewTreeObserver().addOnPreDrawListener(mOwnerCheck);
            mWindowManager = owner.getContext().getSystemService(WindowManager.class);
            if (mWindowManager != null) {
                mBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
                mWindowManager.addCrossWindowBlurEnabledListener(
                        owner.getContext().getMainExecutor(), mBlurListener);
            }
        }
        if (!mBlurEnabled || owner.getViewRootImpl() == null) {
            hideBackdrop();
            return;
        }
        if (mBackdrop == null) {
            mBackdrop = owner.getViewRootImpl().createBackgroundBlurDrawable();
            mBackdrop.setVisible(false, false);
            mBackdrop.setColor(Color.TRANSPARENT);
            // Creation happens during recording, after pre-draw; request the next publication.
            mBackdrop.setCallback(mHost);
        }
        updateOpacity();
        if (!mBackdrop.isVisible()) return;
        // Restore visibility/alpha before bounds: the framework only builds a visible hole path.
        mBackdrop.setBounds(0, 0, size, size);
        mBackdrop.setCornerRadius(cornerRadius);
        mBackdrop.setBlurRadius(Math.max(1, Math.round(size * .16f)));
        mBackdrop.setRefraction(size * .11f, size * .30f, size * .003f);
        mBackdrop.draw(canvas);
    }

    /** True while the live blur-behind is publishing, so callers can thin their tint over it. */
    boolean isShowing() {
        return mBackdrop != null && mBackdrop.isVisible();
    }

    private void updateOpacity() {
        if (mBackdrop == null || mView == null) return;
        float opacity = mHost.getAlpha() / 255f;
        for (View current = mView; current != null;) {
            opacity *= current.getAlpha();
            ViewParent parent = current.getParent();
            current = parent instanceof View view ? view : null;
        }
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255)));
        ViewRootImpl root = mView.getViewRootImpl();
        boolean show = mBlurEnabled && alpha > 0 && root != null && acquireSlot(root);
        if (!show) releaseSlot();
        mBackdrop.setAlpha(show ? alpha : 0);
        mBackdrop.setVisible(show, false);
    }

    private boolean acquireSlot(ViewRootImpl root) {
        if (mSlotRoot == root) return true;
        releaseSlot();
        int count = SLOTS.getOrDefault(root, 0);
        if (count >= MAX_LENSES_PER_WINDOW) return false;
        SLOTS.put(root, count + 1);
        mSlotRoot = root;
        return true;
    }

    private void releaseSlot() {
        if (mSlotRoot == null) return;
        int count = SLOTS.getOrDefault(mSlotRoot, 0);
        if (count <= 1) SLOTS.remove(mSlotRoot);
        else SLOTS.put(mSlotRoot, count - 1);
        // A waiting lens may have been visited earlier in this pre-draw. One extra frame lets it
        // acquire the released slot; there is no recurring invalidation while the scene is idle.
        View root = mSlotRoot.getView();
        if (root != null) root.postInvalidateOnAnimation();
        mSlotRoot = null;
    }

    private void hideBackdrop() {
        releaseSlot();
        if (mBackdrop != null) {
            mBackdrop.setVisible(false, false);
            mBackdrop.setAlpha(0);
        }
    }

    void release() {
        hideBackdrop();
        if (mBackdrop != null) {
            mBackdrop.setBlurRadius(0);
            mBackdrop.setRefraction(0, 0, 0);
            mBackdrop.setCallback(null);
            mBackdrop = null;
        }
        if (mView != null) {
            mView.removeOnAttachStateChangeListener(this);
            if (mView.getViewTreeObserver().isAlive()) {
                mView.getViewTreeObserver().removeOnPreDrawListener(mOwnerCheck);
            }
        }
        if (mWindowManager != null) {
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        }
        mWindowManager = null;
        mView = null;
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        mHost.invalidateSelf();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        release();
    }
}
