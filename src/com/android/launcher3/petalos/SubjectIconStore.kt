/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos

import android.content.Context
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.android.launcher3.dagger.LauncherComponentProvider.appComponent
import com.android.launcher3.graphics.theme.ThemePreference
import java.io.File
import java.security.MessageDigest

enum class SubjectIconStyle(val key: String) {
    FROSTED("frosted"),
    SHEEN("sheen"),
    REFRACTION("refraction"),
    SOLID("solid");

    companion object {
        fun parse(value: String?): SubjectIconStyle =
            entries.firstOrNull { it.key == value } ?: FROSTED
    }
}

/** Disk-only repository. Inference belongs to SubjectIconService, never to icon loading. */
object SubjectIconStore {
    const val FACTORY = "petal-subject"
    const val SIZE = 256
    const val MODEL = "icon_subject_mask.onnx"
    const val MODEL_SHA256 = "e1f64ff84c13fa0ab0926199a0db1ef47da33422bc35e819c870d9d808d986ad"
    const val VERSION = "e1f64ff8-v3"
    private const val PREFS = "petal_subject_icons"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun root(context: Context) = File(context.noBackupFilesDir, "subject-icons/$VERSION")
    fun style(context: Context): SubjectIconStyle =
        SubjectIconStyle.parse(prefs(context).getString("style", null))
    fun monochrome(context: Context): Boolean =
        prefs(context).getBoolean("monochrome", false)

    private fun software(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        else bitmap

    fun resize(icon: Bitmap): Bitmap {
        val source = software(icon)
        if (source.width == SIZE && source.height == SIZE) return source
        return Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
            .also { if (source !== icon) source.recycle() }
    }

    /** Hash the icon at the launcher's own bitmap size; the batch and theme must agree. */
    fun hash(bitmap: Bitmap): String {
        val source = software(bitmap)
        try {
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { index, pixel ->
                for (channel in 0..3) bytes[index * 4 + channel] = (pixel ushr (channel * 8)).toByte()
            }
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    fun image(directory: File, hash: String) = File(directory, "$hash.png")
    fun current(context: Context): File? = prefs(context).getString("generation", null)
        ?.takeIf { it.matches(Regex("g[0-9]+")) }?.let { File(root(context), it) }

    fun load(context: Context, hash: String): Bitmap? {
        val directory = current(context) ?: return null
        return load(context, directory.name, hash)
    }

    /** Load the exact generation the theme was activated with, not the latest prefs value. */
    fun load(context: Context, generation: String, hash: String): Bitmap? {
        if (!generation.matches(Regex("g[0-9]+"))) return null
        return BitmapFactory.decodeFile(image(File(root(context), generation), hash).path)
    }

    fun write(file: File, bitmap: Bitmap) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @JvmStatic
    fun query(context: Context): MatrixCursor {
        val prefs = prefs(context)
        val active = context.appComponent.themePreference.value?.factoryId == FACTORY &&
            current(context)?.isDirectory == true && !IconPackManager.get(context).isPackActive
        val state = if (prefs.getString("state", "idle") == "processing" &&
            !SubjectIconService.isRunning) "interrupted" else prefs.getString("state", "idle")
        val styleKey = style(context).key
        return MatrixCursor(arrayOf("enabled", "state", "done", "total", "fallbacks", "cached",
            "error", "style", "monochrome"))
            .apply {
                addRow(arrayOf(if (active) 1 else 0, state, prefs.getInt("done", 0),
                    prefs.getInt("total", 0), prefs.getInt("fallbacks", 0),
                    if (current(context)?.isDirectory == true) 1 else 0,
                    prefs.getString("error", ""), styleKey,
                    if (monochrome(context)) 1 else 0))
            }
    }

    fun enable(context: Context, generation: String) {
        val preference = context.appComponent.themePreference
        if (preference.value?.factoryId != FACTORY) {
            prefs(context).edit().putString("previous", preference.value?.toString() ?: "").apply()
        }
        preference.setValue(ThemePreference.ThemeValue(FACTORY,
            "$generation:${style(context).key}:${if (monochrome(context)) 1 else 0}"))
        notify(context)
    }

    @JvmStatic
    fun setStyle(context: Context, styleKey: String?) {
        val style = SubjectIconStyle.parse(styleKey)
        prefs(context).edit().putString("style", style.key).apply()
        val generation = prefs(context).getString("generation", null)
        if (generation != null && isEnabled(context)) enable(context, generation) else notify(context)
    }

    @JvmStatic
    fun setMonochrome(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("monochrome", enabled).apply()
        val generation = prefs(context).getString("generation", null)
        if (generation != null && isEnabled(context)) enable(context, generation) else notify(context)
    }

    fun disable(context: Context) {
        val preference = context.appComponent.themePreference
        if (preference.value?.factoryId == FACTORY) {
            val previous = ThemePreference.parsePrefValue(prefs(context).getString("previous", "") ?: "")
            preference.setValue(previous?.takeIf { it.factoryId != FACTORY })
        }
        notify(context)
    }

    /** Tell Wallpaper & style that the subject theme/provider state changed. */
    fun notify(context: Context) {
        runCatching {
            context.contentResolver.notifyChange(
                Uri.parse("content://${context.packageName}.grid_control/subject_icons"), null)
            context.contentResolver.notifyChange(
                Uri.parse("content://${context.packageName}.grid_control/icon_themed"), null)
        }
    }

    @JvmStatic fun isEnabled(context: Context) =
        context.appComponent.themePreference.value?.factoryId == FACTORY &&
            !IconPackManager.get(context).isPackActive
}
