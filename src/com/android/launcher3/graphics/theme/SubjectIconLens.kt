/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.graphics.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import com.android.launcher3.icons.IconShape
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Share the lens geometry, not each app's artwork. The actual mask owns the bevel.
internal class SubjectIconLens private constructor(path: Path, pathSize: Int, val size: Int) {
    private val normalX = FloatArray(size * size)
    private val normalY = FloatArray(size * size)
    private val rim = FloatArray(size * size)
    private val field: Bitmap
    private val strength = size * .055f
    private val dispersion = size * .004f
    private val sigma = size * .012f

    init {
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            scale(size.toFloat() / pathSize, size.toFloat() / pathSize)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val pixels = IntArray(size * size)
        mask.getPixels(pixels, 0, size, 0, 0, size, size)
        mask.recycle()
        val distance = FloatArray(pixels.size) { if (Color.alpha(pixels[it]) >= 128) size.toFloat() else 0f }
        val diagonal = 1.41421356f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                if (distance[i] == 0f) continue
                var d = distance[i]
                d = min(d, if (x > 0) distance[i - 1] + 1f else .5f)
                d = min(d, if (y > 0) distance[i - size] + 1f else .5f)
                if (x > 0 && y > 0) d = min(d, distance[i - size - 1] + diagonal)
                if (x + 1 < size && y > 0) d = min(d, distance[i - size + 1] + diagonal)
                distance[i] = d
            }
        }
        for (y in size - 1 downTo 0) {
            for (x in size - 1 downTo 0) {
                val i = y * size + x
                if (distance[i] == 0f) continue
                var d = distance[i]
                d = min(d, if (x + 1 < size) distance[i + 1] + 1f else .5f)
                d = min(d, if (y + 1 < size) distance[i + size] + 1f else .5f)
                if (x > 0 && y + 1 < size) d = min(d, distance[i + size - 1] + diagonal)
                if (x + 1 < size && y + 1 < size) d = min(d, distance[i + size + 1] + diagonal)
                distance[i] = d
            }
        }
        val band = size * .18f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val dx = distance[y * size + max(0, x - 2)] - distance[y * size + min(size - 1, x + 2)]
                val dy = distance[max(0, y - 2) * size + x] - distance[min(size - 1, y + 2) * size + x]
                val length = hypot(dx, dy)
                val u = (1f - distance[i] / band).coerceIn(0f, 1f)
                if (length > .001f && distance[i] > 0f) {
                    normalX[i] = dx / length
                    normalY[i] = dy / length
                    rim[i] = u * u * (3f - 2f * u)
                }
                pixels[i] = Color.rgb(
                    ((normalX[i] * .5f + .5f) * 255f).roundToInt(),
                    ((normalY[i] * .5f + .5f) * 255f).roundToInt(),
                    (rim[i] * 255f).roundToInt(),
                )
            }
        }
        field = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // A real Gaussian runs before the lens; sparse taps were making a bloody mosaic.
    val effect: RenderEffect? by lazy {
        try {
            val shader = RuntimeShader(AGSL)
            shader.setInputBuffer("edgeField", BitmapShader(field, Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
            shader.setFloatUniform("extent", size.toFloat())
            shader.setFloatUniform("strength", strength)
            shader.setFloatUniform("dispersion", dispersion)
            RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "originalInput"),
                RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP),
            )
        } catch (error: RuntimeException) {
            Log.w("CutoutIcons", "Lens shader unavailable; using smooth bitmap fallback", error)
            null
        }
    }

    fun bitmap(source: Bitmap, color: Int): Bitmap {
        val base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else source
        Canvas(base).apply {
            drawColor(color or (0xff shl 24))
            if (software != null) drawBitmap(software, null, Rect(0, 0, size, size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        if (software != null && software !== source) software.recycle()
        val input = IntArray(size * size)
        base.getPixels(input, 0, size, 0, 0, size, size)
        base.recycle()
        blur(input)
        val output = IntArray(input.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val nx = normalX[i]
                val ny = normalY[i]
                val edge = rim[i]
                val sx = x - nx * edge * strength
                val sy = y - ny * edge * strength
                val split = edge * dispersion
                val red = sample(input, sx + nx * split, sy + ny * split, 16)
                val green = sample(input, sx, sy, 8)
                val blue = sample(input, sx - nx * split, sy - ny * split, 0)
                val lighting = -nx * .65f - ny * .75f
                val light = edge * (.04f + max(0f, lighting) * .22f)
                val shade = edge * max(0f, -lighting) * .12f
                fun lit(channel: Float) = (channel * (1f - shade) * (1f - light)
                    + 255f * light).roundToInt().coerceIn(0, 255)
                output[i] = Color.rgb(lit(red), lit(green), lit(blue))
            }
        }
        return Bitmap.createBitmap(output, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun blur(pixels: IntArray) {
        val radius = ceil(sigma * 3f).toInt()
        val weights = FloatArray(radius * 2 + 1) { index ->
            val x = index - radius
            exp(-x * x / (2f * sigma * sigma))
        }
        val total = weights.sum()
        weights.indices.forEach { weights[it] /= total }
        val scratch = IntArray(pixels.size)
        for (vertical in listOf(false, true)) {
            val input = if (vertical) scratch else pixels
            val output = if (vertical) pixels else scratch
            for (y in 0 until size) {
                for (x in 0 until size) {
                    var red = 0f
                    var green = 0f
                    var blue = 0f
                    for (offset in -radius..radius) {
                        val sx = if (vertical) x else (x + offset).coerceIn(0, size - 1)
                        val sy = if (vertical) (y + offset).coerceIn(0, size - 1) else y
                        val color = input[sy * size + sx]
                        val weight = weights[offset + radius]
                        red += Color.red(color) * weight
                        green += Color.green(color) * weight
                        blue += Color.blue(color) * weight
                    }
                    output[y * size + x] = Color.rgb(red.roundToInt(), green.roundToInt(), blue.roundToInt())
                }
            }
        }
    }

    private fun sample(pixels: IntArray, x: Float, y: Float, shift: Int): Float {
        val fx = x.coerceIn(0f, size - 1f)
        val fy = y.coerceIn(0f, size - 1f)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val tx = fx - x0
        val ty = fy - y0
        fun channel(x: Int, y: Int) = ((pixels[y * size + x] ushr shift) and 0xff).toFloat()
        return (channel(x0, y0) * (1 - tx) + channel(x1, y0) * tx) * (1 - ty) +
            (channel(x0, y1) * (1 - tx) + channel(x1, y1) * tx) * ty
    }

    companion object {
        private val cache = WeakHashMap<Path, MutableMap<Int, SubjectIconLens>>()

        @Synchronized
        fun forShape(shape: IconShape, size: Int): SubjectIconLens =
            cache.getOrPut(shape.path) { mutableMapOf() }.getOrPut(size) {
                SubjectIconLens(shape.path, shape.pathSize, size)
            }

        private const val AGSL = """
            uniform shader originalInput;
            uniform shader edgeField;
            uniform float extent;
            uniform float strength;
            uniform float dispersion;

            half4 sampleAt(float2 xy) {
                return originalInput.eval(clamp(xy, float2(0.5), float2(extent - 0.5)));
            }

            half4 main(float2 xy) {
                half4 field = edgeField.eval(xy);
                float2 normal = float2(field.rg) * 2.0 - 1.0;
                float edge = float(field.b);
                float2 source = xy - normal * edge * strength;
                float2 split = normal * edge * dispersion;
                half3 color = half3(sampleAt(source + split).r,
                    sampleAt(source).g, sampleAt(source - split).b);
                float lighting = dot(normal, float2(-0.65, -0.75));
                half light = half(edge * (0.04 + max(0.0, lighting) * 0.22));
                half shade = half(edge * max(0.0, -lighting) * 0.12);
                return half4(mix(color * (1.0 - shade), half3(1.0), light), 1.0);
            }
        """
    }
}
