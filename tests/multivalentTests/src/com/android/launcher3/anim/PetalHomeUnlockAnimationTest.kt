/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.anim

import android.view.View
import android.widget.FrameLayout
import androidx.test.annotation.UiThreadTest
import androidx.test.filters.SmallTest
import com.android.launcher3.util.LauncherMultivalentJUnit
import com.android.launcher3.util.SandboxApplication
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@UiThreadTest
@RunWith(LauncherMultivalentJUnit::class)
class PetalHomeUnlockAnimationTest {
    @get:Rule val context = SandboxApplication()
    private val animation = PetalHomeUnlockAnimation()

    @After
    fun tearDown() {
        animation.cancel()
    }

    @Test
    fun cancelledBeforeStart_restoresOriginalIconTransforms() {
        val icon = iconAtRest()
        animation.add(icon, 80L, 480L, false)
        assertThat(icon.alpha).isEqualTo(0f)
        assertThat(icon.scaleX).isLessThan(0.95f)

        animation.cancel()

        assertRestored(icon)
    }

    @Test
    fun cancelledDuringUnlock_restoresDelayedWidgetsToo() {
        val icon = iconAtRest()
        val widget = iconAtRest()
        animation.add(icon, 0L, 480L, false)
        animation.add(widget, 100L, 480L, true)
        animation.start()

        animation.cancel()

        assertRestored(icon)
        assertRestored(widget)
    }

    @Test
    fun fingerprintOrigin_iconsStartAtTheSameScreenPoint() {
        val root = FrameLayout(context).apply { layout(0, 0, 1080, 2400) }
        val first = View(context)
        val second = View(context)
        root.addView(first)
        root.addView(second)
        first.layout(100, 200, 180, 280)
        second.layout(700, 1200, 780, 1280)

        animation.add(first, 0L, 480L, false, 540f, 2100f)
        animation.add(second, 0L, 480L, false, 540f, 2100f)

        for (icon in listOf(first, second)) {
            assertThat(icon.left + icon.translationX + icon.width / 2f).isEqualTo(540f)
            assertThat(icon.top + icon.translationY + icon.height / 2f).isEqualTo(2100f)
            assertThat(icon.alpha).isEqualTo(0f)
            assertThat(icon.scaleX).isLessThan(0.2f)
        }
    }

    @Test
    fun defaultOrigin_startsAtBottomCentre() {
        val root = FrameLayout(context).apply { layout(0, 0, 1080, 2400) }
        val icon = View(context)
        root.addView(icon)
        icon.layout(100, 200, 180, 280)

        animation.add(icon, 0L, 480L, false)

        assertThat(icon.left + icon.translationX + icon.width / 2f).isEqualTo(540f)
        assertThat(icon.top + icon.translationY + icon.height / 2f).isEqualTo(2400f)
    }

    @Test
    fun cancellation_restoresParentClipping() {
        val root = FrameLayout(context).apply {
            layout(0, 0, 1080, 2400)
            clipChildren = true
            clipToPadding = false
        }
        val icon = View(context)
        root.addView(icon)
        icon.layout(100, 200, 180, 280)
        animation.add(icon, 0L, 480L, false, 540f, 2100f)
        assertThat(root.clipChildren).isFalse()

        animation.cancel()

        assertThat(root.clipChildren).isTrue()
        assertThat(root.clipToPadding).isFalse()
    }

    @Test
    fun repeatedUnlockAndCancellation_doesNotAccumulateOffsets() {
        val icon = iconAtRest()
        repeat(3) {
            animation.add(icon, 0L, 480L, false)
            animation.start()
            animation.cancel()
            assertRestored(icon)
        }
    }

    private fun iconAtRest() = View(context).apply {
        layout(100, 200, 180, 280)
        alpha = 0.6f
        scaleX = 0.95f
        scaleY = 0.9f
        translationX = 12f
        translationY = 7f
        rotation = 2f
    }

    private fun assertRestored(view: View) {
        assertThat(view.alpha).isEqualTo(0.6f)
        assertThat(view.scaleX).isEqualTo(0.95f)
        assertThat(view.scaleY).isEqualTo(0.9f)
        assertThat(view.translationX).isEqualTo(12f)
        assertThat(view.translationY).isEqualTo(7f)
        assertThat(view.rotation).isEqualTo(2f)
    }
}
