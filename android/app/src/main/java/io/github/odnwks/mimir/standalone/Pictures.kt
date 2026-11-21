package io.github.odnwks.mimir.standalone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Pictures for a model that sees: photos and camera shots are made small (the longest side at
 * most [MAX_SIDE]) and kept as JPEGs; a video becomes a few still frames. All off the main thread.
 */
object Pictures {
    const val MAX_SIDE = 768
    /** Frames taken from a video, spread over its length. */
    const val VIDEO_FRAMES = 4
    const val FRAME_SIDE = 512
    /** The most pictures on one message (a video's frames count). */
    const val MAX = 4

    /** A photo from the phone (or the camera's file), upright and made small; null if it can't be read. */
    fun photo(context: Context, uri: Uri, name: String): TurnPicture? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val turn = cr.openInputStream(uri)?.use { exifDegrees(ExifInterface(it)) } ?: 0
        TurnPicture.of(name, jpeg(upright(small(raw, MAX_SIDE), turn)))
    }.getOrNull()

    /** A few still frames of a video, or none if it can't be read. */
    fun video(context: Context, uri: Uri, name: String): List<TurnPicture> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (0 until VIDEO_FRAMES).mapNotNull { i ->
                val us = ms * 1000 * (2 * i + 1) / (2 * VIDEO_FRAMES)
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                    TurnPicture.of("$name · ${i + 1} of $VIDEO_FRAMES", jpeg(small(f, FRAME_SIDE)))
                }
            }.distinct()
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(emptyList())

    /** A JPEG's pixels as RGB bytes for the model, or null if it can't be read. */
    fun rgb(jpeg: ByteArray): Llm.Rgb? {
        val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) {
            val c = px[i]
            out[i * 3] = (c shr 16).toByte()
            out[i * 3 + 1] = (c shr 8).toByte()
            out[i * 3 + 2] = c.toByte()
        }
        return Llm.Rgb(b.width, b.height, out)
    }

    private fun small(b: Bitmap, side: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= side) return b
        val k = side.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    private fun upright(b: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun exifDegrees(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
