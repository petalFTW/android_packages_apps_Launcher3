/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos

import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.roundToInt

/** Pure postprocessing, independently testable without Android or the inference runtime. */
internal object SubjectMaskPixels {
    data class Output(val cutout: IntArray, val mask: IntArray)

    fun apply(original: IntArray, logits: FloatBuffer): Output? {
        require(logits.remaining() == original.size) { "Unexpected icon mask dimensions" }
        val cutout = IntArray(original.size)
        val mask = IntArray(original.size)
        var selected = 0
        for (i in original.indices) {
            val logit = logits.get(i)
            if (!logit.isFinite()) return null
            if (logit >= 0) selected++
            val probability = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
            // Narrow the edge ramp so the stored cutout does not look soft when the
            // launcher upscales it. The model already provides a clean probability edge.
            val t = ((probability - .46f) / .08f).coerceIn(0f, 1f)
            val opacity = (255 * t * t * (3 - 2 * t)).roundToInt()
            mask[i] = (opacity shl 24) or 0x00ffffff
            // Multiply alpha: a model must never resurrect originally transparent pixels.
            cutout[i] = (original[i] and 0x00ffffff) or
                (((original[i] ushr 24) * opacity / 255) shl 24)
        }
        // No connected-component filtering: isolated small dots can be part of the logo.
        if (selected < 4 || selected >= original.size * .998f) return null
        return Output(cutout, mask)
    }
}
