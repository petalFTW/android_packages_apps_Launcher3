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
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.graphics.ColorUtils
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.FastBitmapDrawableDelegate
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.icons.GraphicsUtils.resizeToContentSize
import com.android.launcher3.icons.IconShape
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.SourceHint
import com.android.launcher3.icons.ThemedBitmap
import com.android.launcher3.logging.StatsLogManager.StatsLogger
import com.android.launcher3.petalos.SubjectIconStore
import com.android.launcher3.petalos.SubjectIconStyle
import com.android.launcher3.util.WallpaperColorHints
import java.io.ByteArrayOutputStream

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
        private val shadow by lazy { SubjectIconShadow.create(bitmap) }
        override fun serialize(): ByteArray = ByteArrayOutputStream().use { stream ->
            stream.write(HEADER)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }

        override fun newDelegateFactory(info: BitmapInfo, context: Context):
            FastBitmapDrawableDelegate.DelegateFactory {
            val subjectShadow = shadow
            return FastBitmapDrawableDelegate.DelegateFactory { _, _, _, host ->
                SubjectIconDelegate(bitmap, style, monochrome, info, context, host, subjectShadow)
            }
        }
    }

    private class SubjectIconDelegate(
        private val subject: Bitmap,
        private val style: SubjectIconStyle,
        private val monochrome: Boolean,
        private val info: BitmapInfo,
        private val context: Context,
        private val host: FastBitmapDrawable,
        private val shadow: SubjectIconShadow,
    ) : FastBitmapDrawableDelegate {
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shadowBounds = RectF()
        private val compositePaint = Paint()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val localBounds = Rect()
        private val layerBounds = RectF()
        private var gradientsSize = -1
        private var matte: Shader? = null
        private var wallpaperSurface: Shader? = null
        private var wallpaperColor = 0
        private var glossy: Shader? = null
        private var gloss: Shader? = null
        private var sheenRim: Shader? = null
        private var glassTint: Shader? = null
        private var glassTintAlpha = -1
        private var glassRim: Shader? = null
        private var seeThroughTile: Shader? = null
        private val punchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        private val backdrop = if (style == SubjectIconStyle.REFRACTION) SubjectIconBackdrop(host) else null
        private var monochromeColor: Int? = null

        override fun onVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) backdrop?.release()
        }

        // Both styles let the wallpaper through, so an outline shadow would show under them.
        fun isBackdropOnly() =
            style == SubjectIconStyle.REFRACTION || style == SubjectIconStyle.SEE_THROUGH

        override fun drawContent(
            info: BitmapInfo,
            iconShape: IconShape,
            canvas: Canvas,
            bounds: Rect,
            paint: Paint,
        ) {
            if (!isBackdropOnly()) {
                canvas.drawBitmap(iconShape.shadowLayer, null, bounds, paint)
            }
            val size = iconShape.pathSize
            if (size <= 0) return
            canvas.resizeToContentSize(bounds, size.toFloat()) {
                clipPath(iconShape.path)
                localBounds.set(0, 0, size, size)
                layerBounds.set(0f, 0f, size.toFloat(), size.toFloat())
                compositePaint.alpha = paint.alpha
                compositePaint.colorFilter = paint.colorFilter
                // The punch-out clears its own layer only, never the window behind the icon.
                val layer = if (paint.alpha != 255 || paint.colorFilter != null
                    || style == SubjectIconStyle.SEE_THROUGH) {
                    saveLayer(layerBounds, compositePaint)
                } else save()
                try {
                    if (style != SubjectIconStyle.SOLID) prepareGradients(size)
                    when (style) {
                        SubjectIconStyle.FROSTED -> drawSurface(iconShape.path, matte)
                        SubjectIconStyle.SHEEN -> drawSurface(iconShape.path, glossy)
                        SubjectIconStyle.REFRACTION -> {
                            val shape = ThemeManager.INSTANCE[context].iconShape
                            val corner = if (shape is ShapeDelegate.RoundedSquare) {
                                size / 2f * shape.radiusRatio
                            } else size * .22f
                            backdrop?.draw(this, size, corner,
                                paint.alpha == 255 && paint.colorFilter == null)
                            drawGlassTint(iconShape.path, size, backdrop?.isShowing() == true)
                        }
                        SubjectIconStyle.SEE_THROUGH -> {
                            drawSurface(iconShape.path, seeThroughTile)
                            drawBitmap(subject, null, localBounds, punchPaint)
                        }
                        SubjectIconStyle.WALLPAPER -> drawSurface(iconShape.path, wallpaperSurface)
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
                        val color = if (style == SubjectIconStyle.WALLPAPER) {
                            context.getColor(if (night) android.R.color.system_neutral1_50
                                else android.R.color.system_neutral1_900)
                        } else if (night) Color.BLACK else Color.WHITE
                        if (monochromeColor != color) {
                            subjectPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                            monochromeColor = color
                        }
                    }
                    if (style != SubjectIconStyle.SEE_THROUGH) {
                        shadow.draw(this, size, shadowBounds, shadowPaint)
                        drawBitmap(subject, null, localBounds, subjectPaint)
                    }
                    if (style == SubjectIconStyle.SHEEN) {
                        // Gloss sits over the cutout too; hiding it underneath made both modes identical.
                        drawSurface(iconShape.path, gloss)
                    }
                    rimPaint.strokeWidth = size * .012f
                    rimPaint.color = if (style == SubjectIconStyle.FROSTED) 0x30FFFFFF else Color.WHITE
                    rimPaint.shader = when (style) {
                        SubjectIconStyle.FROSTED -> null
                        SubjectIconStyle.SHEEN -> sheenRim
                        SubjectIconStyle.REFRACTION -> null
                        SubjectIconStyle.WALLPAPER -> null
                        SubjectIconStyle.SOLID -> null
                        SubjectIconStyle.SEE_THROUGH -> null
                    }
                    if (style == SubjectIconStyle.REFRACTION) {
                        rimPaint.color = Color.WHITE
                        rimPaint.shader = glassRim
                    }
                    if (style == SubjectIconStyle.FROSTED || style == SubjectIconStyle.SHEEN
                        || style == SubjectIconStyle.REFRACTION) {
                        drawPath(iconShape.path, rimPaint)
                    }
                } finally {
                    restoreToCount(layer)
                }
            }
        }

        private fun prepareGradients(size: Int) {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val wallpaperTone = if (style == SubjectIconStyle.WALLPAPER) {
                val palette = WallpaperColorHints.get(context).paletteFor(host)
                if (night) palette.darkSurface else palette.lightSurface
            } else 0
            if (gradientsSize == size && wallpaperColor == wallpaperTone) return
            wallpaperColor = wallpaperTone
            val extent = size.toFloat()
            wallpaperSurface = LinearGradient(0f, 0f, 0f, extent,
                ColorUtils.blendARGB(wallpaperTone, Color.WHITE, if (night) .035f else .10f),
                wallpaperTone, Shader.TileMode.CLAMP)
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
            // Island-style rim: lit along the top, fading out before the bottom edge.
            glassRim = LinearGradient(0f, 0f, 0f, extent,
                intArrayOf(0x66FFFFFF, 0x10FFFFFF, 0x22FFFFFF),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            seeThroughTile = LinearGradient(0f, 0f, 0f, extent,
                ColorUtils.blendARGB(info.color, Color.WHITE, .08f),
                ColorUtils.blendARGB(info.color, Color.BLACK, .10f), Shader.TileMode.CLAMP)
            glassTintAlpha = -1
            gradientsSize = size
        }

        // The app's own background over the refracted wallpaper, as the island tints its blob.
        // Without a live blur behind it the tint goes opaque so the tile never reads as empty.
        private fun Canvas.drawGlassTint(path: Path, size: Int, overBlur: Boolean) {
            val alpha = if (overBlur) GLASS_TINT_ALPHA else 0xFF
            if (glassTintAlpha != alpha) {
                val base = info.color or (0xff shl 24)
                val top = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(base, Color.WHITE, .12f), alpha)
                val bottom = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(base, Color.BLACK, .08f), alpha)
                glassTint = LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom,
                    Shader.TileMode.CLAMP)
                glassTintAlpha = alpha
            }
            drawSurface(path, glassTint)
        }

        private fun Canvas.drawSurface(path: Path, shader: Shader?) {
            tilePaint.shader = shader
            tilePaint.alpha = 255
            drawPath(path, tilePaint)
            tilePaint.shader = null
        }

    }

    companion object {
        // Enough of the app colour to read as its tile while the refraction still shows.
        private const val GLASS_TINT_ALPHA = 0xA6

        @JvmStatic
        fun hasTransparentBackdrop(icon: FastBitmapDrawable): Boolean {
            val delegate = icon.delegate
            return delegate is SubjectIconDelegate && delegate.isBackdropOnly()
        }

        private val HEADER = ("PSI3:" + SubjectIconStore.MODEL_SHA256 + "\n")
            .toByteArray(Charsets.US_ASCII)
    }
}
