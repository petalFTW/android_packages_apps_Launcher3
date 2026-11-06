/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.animation.DecelerateInterpolator;

import com.android.launcher3.PetalLauncherPrefs;
import com.android.launcher3.icons.FastBitmapDrawable;

/** Finite tactile feedback on the drawable's existing scale channel, shared with launch/drag. */
public final class AtelierMotion {
    private static final float PRESS_SCALE = .965f;
    private static final TimeInterpolator PRESS = new DecelerateInterpolator();
    // Damped step response, normalized to end exactly at rest. Tiny overshoot on release only.
    private static final TimeInterpolator RECOVER = input -> {
        if (input == 0 || input == 1) return input;
        double t = input * 10;
        return (float) (1 - Math.exp(-t) * (Math.cos(t) + Math.sin(t)));
    };

    private AtelierMotion() { }

    public static void onPressedChanged(Context context, FastBitmapDrawable icon) {
        if (icon.scaleAnimation != null) icon.scaleAnimation.cancel();
        if (!icon.isAnimationEnabled() || !icon.isVisible() || !ValueAnimator.areAnimatorsEnabled()
                || PetalLauncherPrefs.enabled(context, PetalLauncherPrefs.REDUCED_MOTION)) {
            icon.resetScale();
            return;
        }
        // Begin on the first input frame, then settle. Retargeting reads the current scale, so a
        // release or a second press can interrupt without jumping to either endpoint.
        boolean pressed = icon.isPressed;
        if (pressed && FastBitmapDrawable.SCALE.get(icon) == 1f) {
            FastBitmapDrawable.SCALE.set(icon, .985f);
        }
        icon.scaleAnimation = ObjectAnimator.ofFloat(icon, FastBitmapDrawable.SCALE,
                pressed ? PRESS_SCALE : 1f);
        icon.scaleAnimation.setDuration(pressed ? 70 : 220);
        icon.scaleAnimation.setInterpolator(pressed ? PRESS : RECOVER);
        icon.scaleAnimation.start();
    }
}
