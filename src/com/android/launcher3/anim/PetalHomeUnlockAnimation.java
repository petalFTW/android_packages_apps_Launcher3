/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.anim;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.util.FloatProperty;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// Bloom the grid out of the unlock point; springs catch the landing.
public final class PetalHomeUnlockAnimation {
    private static final FloatProperty<Item> PROGRESS = new FloatProperty<Item>("unlockProgress") {
        @Override
        public Float get(Item item) {
            return item.progress;
        }

        @Override
        public void setValue(Item item, float progress) {
            item.apply(progress);
        }
    };

    private final List<Item> mItems = new ArrayList<>();
    private final List<Animator> mAnimations = new ArrayList<>();
    private final Map<ViewGroup, boolean[]> mClipping = new IdentityHashMap<>();
    private AnimatorSet mAnimator;

    public void add(View view, long delay, long duration, boolean widget) {
        View root = view.getRootView();
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        add(view, delay, duration, widget,
                location[0] + root.getWidth() / 2f, location[1] + root.getHeight());
    }

    public void add(View view, long delay, long duration, boolean widget,
            float originX, float originY) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0) return;
        view.animate().cancel();
        // The dock used to chop off anything travelling outside its row.
        for (ViewParent parent = view.getParent(); parent instanceof ViewGroup;
                parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (!mClipping.containsKey(group)) {
                mClipping.put(group,
                        new boolean[] {group.getClipChildren(), group.getClipToPadding()});
                group.setClipChildren(false);
                group.setClipToPadding(false);
            }
        }
        Item item = new Item(view, widget, originX, originY);
        ValueAnimator animator = new SpringAnimationBuilder(view.getContext())
                .setStartValue(0f)
                .setEndValue(1f)
                .setStartVelocity(0f)
                .setStiffness(240f)
                .setDampingRatio(widget ? 0.88f : 0.8f)
                .setMinimumVisibleChange(0.001f)
                .build(item, PROGRESS);
        animator.setStartDelay(Math.max(0, delay));
        animator.setDuration(duration);
        mItems.add(item);
        mAnimations.add(animator);
        item.apply(0f);
    }

    public void start() {
        if (mItems.isEmpty()) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel();
            return;
        }
        AnimatorSet animator = new AnimatorSet();
        animator.playTogether(mAnimations);
        mAnimator = animator;
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnimator != animation) return;
                mAnimator = null;
                restoreItems();
            }
        });
        animator.start();
    }

    public void cancel() {
        AnimatorSet animator = mAnimator;
        mAnimator = null;
        if (animator != null) animator.cancel();
        restoreItems();
    }

    private void restoreItems() {
        for (Item item : mItems) item.restore();
        for (Map.Entry<ViewGroup, boolean[]> entry : mClipping.entrySet()) {
            entry.getKey().setClipChildren(entry.getValue()[0]);
            entry.getKey().setClipToPadding(entry.getValue()[1]);
        }
        mClipping.clear();
        mItems.clear();
        mAnimations.clear();
    }

    private static final class Item {
        final View view;
        final float alpha;
        final float scaleX;
        final float scaleY;
        final float translationX;
        final float translationY;
        final float rotation;
        final float offsetX;
        final float offsetY;
        final float shrink;
        float progress;

        Item(View view, boolean widget, float originX, float originY) {
            this.view = view;
            alpha = view.getAlpha();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            translationX = view.getTranslationX();
            translationY = view.getTranslationY();
            rotation = view.getRotation();
            float[] origin = {originX, originY};
            if (view.getParent() instanceof View) {
                View parent = (View) view.getParent();
                Matrix toParent = new Matrix();
                parent.transformMatrixToLocal(toParent);
                toParent.mapPoints(origin);
                origin[0] += parent.getScrollX();
                origin[1] += parent.getScrollY();
            }
            float[] center = {view.getWidth() / 2f, view.getHeight() / 2f};
            view.getMatrix().mapPoints(center);
            offsetX = origin[0] - view.getLeft() - center[0];
            offsetY = origin[1] - view.getTop() - center[1];
            shrink = widget ? 0.85f : 0.92f;
        }

        void apply(float value) {
            progress = value;
            float remaining = 1f - value;
            float bounded = Math.max(0f, Math.min(1f, value));
            view.setTranslationX(translationX + offsetX * remaining);
            view.setTranslationY(translationY + offsetY * remaining);
            view.setScaleX(scaleX * (1f - shrink * remaining));
            view.setScaleY(scaleY * (1f - shrink * remaining));
            view.setAlpha(alpha * Math.min(1f, bounded * 1.8f));
        }

        void restore() {
            view.setAlpha(alpha);
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
            view.setTranslationX(translationX);
            view.setTranslationY(translationY);
            view.setRotation(rotation);
        }
    }
}
