/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.graphics.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.Log
import androidx.core.graphics.ColorUtils
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.FastBitmapDrawableDelegate
import com.android.launcher3.icons.GraphicsUtils.resizeToContentSize
import com.android.launcher3.icons.IconShape
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.SourceHint
import com.android.launcher3.icons.ThemedBitmap
import com.android.launcher3.logging.StatsLogManager.StatsLogger
import com.android.launcher3.petalos.SubjectIconStore
import com.android.launcher3.petalos.SubjectIconStyle
import java.io.ByteArrayOutputStream
import kotlin.math.max

class SubjectIconThemeFactory(private val context: Context) : IconThemeFactory {
    override fun createController(themeId: String): IconThemeController = Controller(context, themeId)
    override fun logThemeEvent(themeId: String, logger: StatsLogger) = Unit

    private class Controller(
        private val context: Context,
        override val themeID: String,
    ) : IconThemeController {
        private val generation: String
        private val style: SubjectIconStyle
        private val monochrome: Boolean

        init {
            val parts = themeID.split(':')
            generation = parts[0]
            style = SubjectIconStyle.parse(parts.getOrNull(1))
            monochrome = parts.getOrNull(2) == "1"
        }

        override fun createThemedBitmap(
            icon: AdaptiveIconDrawable,
            info: BitmapInfo,
            factory: BaseIconFactory,
            sourceHint: SourceHint?,
        ): ThemedBitmap {
            // Hash the same flattened icon the mask batch used, not a fresh render.
            val bitmap = SubjectIconStore.load(context, generation, SubjectIconStore.hash(info.icon))
            return bitmap?.let { SubjectBitmap(it, style, monochrome) } ?: ThemedBitmap.NOT_SUPPORTED
        }

        override fun decode(
            bytes: ByteArray,
            info: BitmapInfo,
            factory: BaseIconFactory,
            sourceHint: SourceHint,
        ): ThemedBitmap {
            // Keep old model payloads out of the icon DB.
            if (bytes.size <= HEADER.size ||
                !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
                return ThemedBitmap.NOT_SUPPORTED
            }
            return BitmapFactory.decodeByteArray(bytes, HEADER.size, bytes.size - HEADER.size)
                ?.let { SubjectBitmap(it, style, monochrome) } ?: ThemedBitmap.NOT_SUPPORTED
        }

        override fun createThemedAdaptiveIcon(
            context: Context,
            originalIcon: AdaptiveIconDrawable,
            info: BitmapInfo?,
        ): AdaptiveIconDrawable? = null
    }

    private class SubjectBitmap(
        private val bitmap: Bitmap,
        private val style: SubjectIconStyle,
        private val monochrome: Boolean,
    ) : ThemedBitmap {
        override fun serialize(): ByteArray = ByteArrayOutputStream().use { stream ->
            stream.write(HEADER)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }

        override fun newDelegateFactory(info: BitmapInfo, context: Context) =
            FastBitmapDrawableDelegate.DelegateFactory { _, _, _, _ ->
                SubjectIconDelegate(bitmap, style, monochrome, info, context)
            }
    }

    private class SubjectIconDelegate(
        private val subject: Bitmap,
        private val style: SubjectIconStyle,
        private val monochrome: Boolean,
        private val info: BitmapInfo,
        private val context: Context,
    ) : FastBitmapDrawableDelegate {
        private val textureSize = max(subject.width, info.icon.width).coerceIn(128, 512)
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val compositePaint = Paint()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val localBounds = Rect()
        private val layerBounds = RectF()
        private var gradientsSize = -1
        private var matte: Shader? = null
        private var glossy: Shader? = null
        private var gloss: Shader? = null
        private var sheenRim: Shader? = null
        private var rimLight: Shader? = null
        private var lensPath: Path? = null
        private var lens: SubjectIconLens? = null
        private var lensNode: RenderNode? = null
        private var hardwareFailed = false
        private var tile: Bitmap? = null
        private var monochromeColor: Int? = null

        override fun drawContent(
            info: BitmapInfo,
            iconShape: IconShape,
            canvas: Canvas,
            bounds: Rect,
            paint: Paint,
        ) {
            canvas.drawBitmap(iconShape.shadowLayer, null, bounds, paint)
            val size = iconShape.pathSize
            if (size <= 0) return
            canvas.resizeToContentSize(bounds, size.toFloat()) {
                clipPath(iconShape.path)
                localBounds.set(0, 0, size, size)
                layerBounds.set(0f, 0f, size.toFloat(), size.toFloat())
                compositePaint.alpha = paint.alpha
                compositePaint.colorFilter = paint.colorFilter
                val layer = if (paint.alpha != 255 || paint.colorFilter != null) {
                    saveLayer(layerBounds, compositePaint)
                } else save()
                try {
                    if (style != SubjectIconStyle.SOLID) prepareGradients(size)
                    when (style) {
                        SubjectIconStyle.FROSTED -> drawSurface(iconShape.path, matte)
                        SubjectIconStyle.SHEEN -> drawSurface(iconShape.path, glossy)
                        SubjectIconStyle.REFRACTION -> drawRefraction(iconShape)
                        SubjectIconStyle.SOLID -> {
                            // The launcher already picked the strongest hue from the original icon.
                            tilePaint.shader = null
                            tilePaint.color = info.color or (0xff shl 24)
                            drawPath(iconShape.path, tilePaint)
                        }
                    }
                    if (monochrome) {
                        val night = context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        val color = if (night) Color.BLACK else Color.WHITE
                        if (monochromeColor != color) {
                            subjectPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                            monochromeColor = color
                        }
                    }
                    drawBitmap(subject, null, localBounds, subjectPaint)
                    if (style == SubjectIconStyle.SHEEN) {
                        // Gloss sits over the cutout too; hiding it underneath made both modes identical.
                        drawSurface(iconShape.path, gloss)
                    }
                    rimPaint.strokeWidth = size * .012f
                    rimPaint.color = if (style == SubjectIconStyle.FROSTED) 0x30FFFFFF else Color.WHITE
                    rimPaint.shader = when (style) {
                        SubjectIconStyle.FROSTED -> null
                        SubjectIconStyle.SHEEN -> sheenRim
                        SubjectIconStyle.REFRACTION -> rimLight
                        SubjectIconStyle.SOLID -> null
                    }
                    if (style != SubjectIconStyle.SOLID) drawPath(iconShape.path, rimPaint)
                } finally {
                    restoreToCount(layer)
                }
            }
        }

        private fun prepareGradients(size: Int) {
            if (gradientsSize == size) return
            val extent = size.toFloat()
            // Just a little milk in the glass, not a grey paint job.
            val neutral = ColorUtils.blendARGB(info.color, 0xFFBFC5CA.toInt(), .18f)
            matte = LinearGradient(0f, 0f, 0f, extent,
                ColorUtils.blendARGB(neutral, Color.WHITE, .10f),
                ColorUtils.blendARGB(neutral, Color.BLACK, .04f), Shader.TileMode.CLAMP)
            glossy = LinearGradient(0f, 0f, extent, extent,
                ColorUtils.blendARGB(info.color, Color.WHITE, .06f),
                ColorUtils.blendARGB(info.color, Color.BLACK, .16f), Shader.TileMode.CLAMP)
            gloss = LinearGradient(0f, 0f, extent, extent,
                intArrayOf(0x04FFFFFF, 0x00FFFFFF, 0x28FFFFFF, 0x04FFFFFF,
                    0x05000000, 0x08FFFFFF),
                floatArrayOf(0f, .27f, .42f, .56f, .66f, 1f), Shader.TileMode.CLAMP)
            sheenRim = LinearGradient(0f, 0f, extent, extent,
                intArrayOf(0x55FFFFFF, 0x0CFFFFFF, 0x28FFFFFF),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            rimLight = LinearGradient(0f, 0f, extent, extent,
                intArrayOf(0xBBFFFFFF.toInt(), 0x20FFFFFF, 0x70FFFFFF),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            gradientsSize = size
        }

        private fun Canvas.drawSurface(path: Path, shader: Shader?) {
            tilePaint.shader = shader
            tilePaint.alpha = 255
            drawPath(path, tilePaint)
            tilePaint.shader = null
        }

        private fun Canvas.drawRefraction(shape: IconShape) {
            if (lensPath !== shape.path) {
                lensNode?.discardDisplayList()
                lensNode = null
                tile = null
                lensPath = shape.path
                lens = SubjectIconLens.forShape(shape, textureSize)
            }
            val geometry = checkNotNull(lens)
            val effect = if (isHardwareAccelerated && !hardwareFailed) geometry.effect else null
            if (effect != null) {
                try {
                    val node = lensNode ?: RenderNode("SubjectIconLens").also { fresh ->
                        fresh.setPosition(0, 0, textureSize, textureSize)
                        val recording = fresh.beginRecording(textureSize, textureSize)
                        try {
                            tilePaint.shader = null
                            tilePaint.alpha = 255
                            recording.drawColor(info.color or (0xff shl 24))
                            recording.drawBitmap(info.icon, null,
                                Rect(0, 0, textureSize, textureSize), tilePaint)
                        } finally {
                            fresh.endRecording()
                        }
                        fresh.setRenderEffect(effect)
                        lensNode = fresh
                    }
                    val saved = save()
                    try {
                        val factor = shape.pathSize.toFloat() / textureSize
                        scale(factor, factor)
                        drawRenderNode(node)
                    } finally {
                        restoreToCount(saved)
                    }
                    return
                } catch (error: RuntimeException) {
                    Log.w("CutoutIcons", "Lens rendering failed; using bitmap fallback", error)
                    hardwareFailed = true
                    lensNode?.discardDisplayList()
                    lensNode = null
                }
            }
            val bitmap = tile ?: geometry.bitmap(info.icon, info.color).also { tile = it }
            tilePaint.shader = null
            tilePaint.alpha = 255
            drawBitmap(bitmap, null, localBounds, tilePaint)
        }
    }

    companion object {
        private val HEADER = ("PSI3:" + SubjectIconStore.MODEL_SHA256 + "\n")
            .toByteArray(Charsets.US_ASCII)
    }
}
