/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.security.DigestInputStream

/** Worker-confined runtime; closed at the end of each explicit generation batch. */
class SubjectIconSegmenter(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val directory = File(context.noBackupFilesDir, "subject-model").apply { mkdirs() }
        val file = File(directory, SubjectIconStore.MODEL)
        val expected = SubjectIconStore.MODEL_SHA256
        fun valid(): Boolean {
            if (!file.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream(), digest).use { input ->
                val scratch = ByteArray(65536)
                while (input.read(scratch) != -1) { /* Hash without a second model-sized buffer. */ }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected
        }
        if (!valid()) {
            val staging = File(directory, "model.tmp")
            context.assets.open(SubjectIconStore.MODEL).use { input ->
                staging.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(staging.renameTo(file)) { "Cannot install icon model" }
            check(valid()) { "Icon model checksum mismatch" }
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            session = environment.createSession(file.path, options)
        }
    }

    /** Returns null only for structurally unusable output. Confidence is not a semantic score. */
    fun cutout(icon: Bitmap): Pair<Bitmap, Bitmap>? {
        val size = SubjectIconStore.SIZE
        val plane = size * size
        val pixels = IntArray(plane)
        icon.getPixels(pixels, 0, size, 0, 0, size, size)
        val tensor = FloatArray(plane * 3)
        val mean = floatArrayOf(.485f, .456f, .406f)
        val std = floatArrayOf(.229f, .224f, .225f)
        for (i in pixels.indices) {
            tensor[i] = (Color.red(pixels[i]) / 255f - mean[0]) / std[0]
            tensor[plane + i] = (Color.green(pixels[i]) / 255f - mean[1]) / std[1]
            tensor[2 * plane + i] = (Color.blue(pixels[i]) / 255f - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 256, 256)).use { input ->
            session.run(mapOf("input" to input)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                check(output.remaining() == plane) { "Unexpected icon mask dimensions" }
                val processed = SubjectMaskPixels.apply(pixels, output) ?: return null
                return Pair(Bitmap.createBitmap(processed.cutout, size, size, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(processed.mask, size, size, Bitmap.Config.ARGB_8888))
            }
        }
    }

    override fun close() = session.close()
}
