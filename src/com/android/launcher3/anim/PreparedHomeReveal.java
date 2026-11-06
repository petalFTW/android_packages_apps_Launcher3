/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.anim;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/** One clock and one transform owner for a prepared, staggered Home entrance or shade gesture. */
public final class PreparedHomeReveal {
    // UI-thread-only ownership; weak keys and values do not retain detached views or old reveals.
    private static final WeakHashMap<View, WeakReference<PreparedHomeReveal>> OWNERS =
            new WeakHashMap<>();
    public static final long ITEM_DURATION_MS = 270;
    private static final PathInterpolator EASE = new PathInterpolator(0f, 0f, .2f, 1f);

    private final List<Item> mItems = new ArrayList<>();
    private ValueAnimator mActive;
    private ValueAnimator mReturnAnimator;
    private long mMaxDelay;
    private float mProgress = 1f;
    private boolean mPrepared;
    private boolean mFinished;

    public void add(View view, long delay, float rise, float startScale) {
        if (mPrepared || mFinished) throw new IllegalStateException("Reveal already prepared");
        WeakReference<PreparedHomeReveal> reference = OWNERS.get(view);
        PreparedHomeReveal previous = reference == null ? null : reference.get();
        if (previous != null && previous != this) previous.cancel();
        OWNERS.put(view, new WeakReference<>(this));
        long boundedDelay = Math.max(0, delay);
        mItems.add(new Item(view, boundedDelay, rise, startScale));
        mMaxDelay = Math.max(mMaxDelay, boundedDelay);
    }

    /** Set the hidden frame while another surface still covers Home. */
    public void prepare() {
        if (mPrepared || mFinished) return;
        mPrepared = true;
        applyProgress(0f);
    }

    /** Belongs in the same parent AnimatorSet as the workspace, dock, scrim and depth tracks. */
    public ValueAnimator getAnimator() {
        if (mReturnAnimator == null) mReturnAnimator = createAnimator(1f);
        return mReturnAnimator;
    }

    /** Direct gesture input has no secondary timer, easing filter, or endpoint reset. */
    public void setProgress(float progress) {
        if (mFinished || !Float.isFinite(progress)) return;
        cancelActivePreservingFrame();
        mPrepared = true;
        applyProgress(Math.max(0f, Math.min(1f, progress)));
    }

    public void start() {
        settle(1f);
    }

    public void reverse() {
        settle(0f);
    }

    /**
     * Finishes the entrance on its own clock once the driving gesture lets go, stretched to at
     * least {@code minDurationMs} for a full run. {@code onEnd} runs only if it completes.
     */
    public void finish(long minDurationMs, Runnable onEnd) {
        if (mFinished) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel();
            onEnd.run();
            return;
        }
        ValueAnimator animator = createAnimator(1f, minDurationMs);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mFinished) onEnd.run();
            }
        });
        animator.start();
    }

    private void settle(float target) {
        if (mFinished) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel();
            return;
        }
        createAnimator(target).start();
    }

    private ValueAnimator createAnimator(float target) {
        return createAnimator(target, 0);
    }

    private ValueAnimator createAnimator(float target, long minDurationMs) {
        cancelActivePreservingFrame();
        mPrepared = true;
        ValueAnimator animator = ValueAnimator.ofFloat(mProgress, target);
        animator.setDuration(Math.round(Math.max(minDurationMs, ITEM_DURATION_MS + mMaxDelay)
                * Math.abs(target - mProgress)));
        animator.setInterpolator(EASE);
        animator.addUpdateListener(value -> {
            if (mActive == value) applyProgress((float) value.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mActive != animation) return;
                mActive = null;
                if (target == 1f) restore();
            }
        });
        mActive = animator;
        return animator;
    }

    public void cancel() {
        cancelActivePreservingFrame();
        restore();
    }

    private void cancelActivePreservingFrame() {
        ValueAnimator previous = mActive;
        mActive = null;
        if (previous != null) previous.cancel();
    }

    private void applyProgress(float progress) {
        mProgress = progress;
        float timeline = progress * (ITEM_DURATION_MS + mMaxDelay);
        for (Item item : mItems) {
            item.apply(Math.max(0f, Math.min(1f, (timeline - item.delay) / ITEM_DURATION_MS)));
        }
    }

    private void restore() {
        if (mFinished) return;
        mFinished = true;
        for (Item item : mItems) {
            WeakReference<PreparedHomeReveal> reference = OWNERS.get(item.view);
            if (reference != null && reference.get() == this) {
                item.restore();
                OWNERS.remove(item.view);
            }
        }
        mItems.clear();
        mProgress = 1f;
    }

    private static final class Item {
        final View view;
        final long delay;
        final float alpha, scaleX, scaleY, translationY, rise, startScale;

        Item(View view, long delay, float rise, float startScale) {
            this.view = view;
            this.delay = delay;
            this.rise = rise;
            this.startScale = startScale;
            alpha = view.getAlpha();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            translationY = view.getTranslationY();
        }

        void apply(float progress) {
            float remaining = 1f - progress;
            view.setTranslationY(translationY - rise * remaining);
            float scale = 1f + (startScale - 1f) * remaining;
            view.setScaleX(scaleX * scale);
            view.setScaleY(scaleY * scale);
            view.setAlpha(alpha * progress);
        }

        void restore() {
            view.setAlpha(alpha);
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
            view.setTranslationY(translationY);
        }
    }
}
