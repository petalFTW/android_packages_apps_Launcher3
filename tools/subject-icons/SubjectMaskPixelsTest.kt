/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos

import java.nio.FloatBuffer

fun main() {
    val source = IntArray(1024) { 0xff123456.toInt() }
    source[0] = 0x00123456
    source[1] = 0x80123456.toInt()
    val values = FloatArray(1024) { -1000f }
    // Four disconnected foreground pixels are a legitimate small-dot logo (<2% coverage).
    for (index in intArrayOf(0, 1, 300, 900)) values[index] = 1000f
    val result = checkNotNull(SubjectMaskPixels.apply(source, FloatBuffer.wrap(values)))
    check(result.cutout[0] ushr 24 == 0) { "Transparent source was resurrected" }
    check(result.cutout[1] ushr 24 == 128) { "Original partial alpha was lost" }
    check(result.cutout[300] == source[300]) { "Foreground colour was changed" }
    check(result.cutout[301] ushr 24 == 0) { "Background leaked through" }
    check(result.mask.count { it ushr 24 == 255 } == 4) { "Disconnected dots were removed" }
    check(source[300] == 0xff123456.toInt()) { "Input pixels were mutated" }
    check(SubjectMaskPixels.apply(source, FloatBuffer.wrap(FloatArray(1024) { 1000f })) == null)
    check(SubjectMaskPixels.apply(source, FloatBuffer.wrap(FloatArray(1024) { -1000f })) == null)
    values[999] = Float.NaN
    check(SubjectMaskPixels.apply(source, FloatBuffer.wrap(values)) == null)
    values[999] = Float.POSITIVE_INFINITY
    check(SubjectMaskPixels.apply(source, FloatBuffer.wrap(values)) == null)
    println("SubjectMaskPixels: alpha, colours, tiny dots, full/empty and non-finite masks passed")
}
