/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.anim.PreparedHomeReveal;
import com.android.launcher3.anim.ShadeIconProgress;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 36)
public class PreparedHomeRevealTest {
    @Test
    public void firstFrameExistsBeforeAnimationClockStarts() {
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal reveal = new PreparedHomeReveal();
            reveal.add(icon, 40, 70, 1.35f);
            reveal.prepare();

            assertEquals(0, icon.getAlpha(), 0);
            assertEquals(-63, icon.getTranslationY(), 0);
            assertEquals(.9f * 1.35f, icon.getScaleX(), .0001f);
            reveal.cancel();
        });
    }

    @Test
    public void cancellationBeforeStartRestoresAllProperties() {
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal reveal = new PreparedHomeReveal();
            reveal.add(icon, 40, 70, 1.35f);
            reveal.prepare();
            reveal.cancel();
            assertRestored(icon);
        });
    }

    @Test
    public void cancellationRestoresDelayedEntriesAsWellAsFirstEntry() {
        onMain(() -> {
            View first = icon(), delayed = icon();
            PreparedHomeReveal reveal = new PreparedHomeReveal();
            reveal.add(first, 0, 70, 1.35f);
            reveal.add(delayed, 64, 70, 1.35f);
            reveal.prepare();
            reveal.start();
            reveal.cancel();
            assertRestored(first);
            assertRestored(delayed);
        });
    }

    @Test
    public void repeatedPreparationAndCancellationDoesNotAccumulateOffsets() {
        onMain(() -> {
            View icon = icon();
            for (int attempt = 0; attempt < 3; attempt++) {
                PreparedHomeReveal reveal = new PreparedHomeReveal();
                reveal.add(icon, 0, 70, 1.35f);
                reveal.prepare();
                reveal.cancel();
                assertRestored(icon);
            }
        });
    }

    @Test
    public void fingerProgressKeepsStaggerAndInterpolates() {
        onMain(() -> {
            View first = icon(), last = icon();
            PreparedHomeReveal reveal = new PreparedHomeReveal();
            reveal.add(first, 0, 70, 1.35f);
            reveal.add(last, 64, 70, 1.35f);

            reveal.setProgress(0f);
            assertEquals(0, first.getAlpha(), 0);
            assertEquals(0, last.getAlpha(), 0);

            reveal.setProgress(0.4f);
            // Both move with the finger; the first item stays ahead of the staggered last one.
            assertTrue(first.getTranslationY() > last.getTranslationY());
            assertTrue(first.getAlpha() > last.getAlpha());
            assertTrue(first.getAlpha() < .6f);

            reveal.setProgress(1f);
            assertRestored(first);
            assertRestored(last);
            reveal.cancel();
        });
    }

    @Test
    public void progressAfterCompletionIsIgnored() {
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal reveal = new PreparedHomeReveal();
            reveal.add(icon, 0, 70, 1.35f);
            reveal.prepare();
            reveal.cancel(); // retires the reveal

            reveal.setProgress(0f);
            assertRestored(icon);
        });
    }

    @Test
    public void newGestureCannotCaptureAnOldEntrancesPartialPose() {
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal entrance = new PreparedHomeReveal();
            entrance.add(icon, 0, 70, 1.35f);
            entrance.prepare();
            entrance.setProgress(.4f);
            PreparedHomeReveal gesture = new PreparedHomeReveal();
            gesture.add(icon, 0, 70, 1.35f);
            gesture.setProgress(.2f);
            entrance.cancel(); // A stale parent completion must not reset the new gesture.
            assertTrue(icon.getTranslationY() < 7);
            gesture.cancel();
            assertRestored(icon);
        });
    }

    @Test
    public void shadeMappingStartsAtEightyPercentAndIsReversible() {
        assertEquals(0f, ShadeIconProgress.fromExpansion(1f), 0);
        assertEquals(0f, ShadeIconProgress.fromExpansion(.8f), 0);
        assertEquals(.5f, ShadeIconProgress.fromExpansion(.4f), .0001f);
        assertEquals(1f, ShadeIconProgress.fromExpansion(0f), 0);
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal gesture = new PreparedHomeReveal();
            gesture.add(icon, 0, 70, 1.35f);
            gesture.setProgress(ShadeIconProgress.fromExpansion(.4f));
            float halfway = icon.getTranslationY();
            gesture.setProgress(ShadeIconProgress.fromExpansion(.7f));
            gesture.setProgress(ShadeIconProgress.fromExpansion(.4f));
            assertEquals(halfway, icon.getTranslationY(), 0);
            gesture.cancel();
        });
    }

    @Test
    public void malformedProgressCannotCorruptViewProperties() {
        onMain(() -> {
            View icon = icon();
            PreparedHomeReveal gesture = new PreparedHomeReveal();
            gesture.add(icon, 0, 70, 1.35f);
            gesture.setProgress(Float.NaN);
            gesture.setProgress(Float.POSITIVE_INFINITY);
            assertRestored(icon);
            gesture.cancel();
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static View icon() {
        View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
        view.layout(100, 200, 180, 280);
        view.setAlpha(.6f);
        view.setScaleX(.9f);
        view.setScaleY(.95f);
        view.setTranslationY(7);
        return view;
    }

    private static void assertRestored(View icon) {
        assertEquals(.6f, icon.getAlpha(), 0);
        assertEquals(.9f, icon.getScaleX(), 0);
        assertEquals(.95f, icon.getScaleY(), 0);
        assertEquals(7, icon.getTranslationY(), 0);
    }
}
