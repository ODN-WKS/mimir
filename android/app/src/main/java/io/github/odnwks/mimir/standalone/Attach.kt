package io.github.odnwks.mimir.standalone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Files attached to a message for the model on this phone: text files are read in (the start of a
 * long one); pictures and other files are turned away with the reason, since the phone's models
 * read only words for now.
 */
object Attach {
    /** The most of one file the model is given (its memory is small). */
    const val MAX_CHARS = 12_000
    /** The most read from a file to find those words. */
    const val MAX_BYTES = 256 * 1024
    /** The most files on one message. */
    const val MAX_FILES = 3

    /** The most of one file sent to the PC to read (bigger ones aren't sent). */
    const val MAX_PC_BYTES = 10 * 1024 * 1024

    enum class Problem { Picture, NotText, Unreadable, TooBig }

    sealed interface Result {
        data class Read(val file: TurnFile) : Result
        data class No(val problem: Problem) : Result
    }

    /** Reads [bytes] (the start of the file, or all of it when [whole]) as a file called [name]. */
    fun read(name: String, mime: String?, bytes: ByteArray, whole: Boolean): Result {
        val type = mime.orEmpty().lowercase()
        if (type.startsWith("image/") || type.startsWith("video/")) return Result.No(Problem.Picture)
        if (type.startsWith("audio/") || type == "application/pdf" || type.startsWith("application/zip")) return Result.No(Problem.NotText)
        // A file cut short may end in the middle of a letter: up to three bytes may be dropped.
        val text = (0..(if (whole) 0 else 3)).firstNotNullOfOrNull { drop -> utf8(bytes, bytes.size - drop) }
            ?: return Result.No(Problem.NotText)
        if (text.any { it == '\u0000' }) return Result.No(Problem.NotText)
        val clean = text.removePrefix("﻿").replace("\r\n", "\n")
        val cut = !whole || clean.length > MAX_CHARS
        return Result.Read(TurnFile(name, clean.take(MAX_CHARS).trimEnd(), cut))
    }

    /**
     * A file for the PC to read (PDF, document, sheet, zip, recording, text): its bytes go as they are, the PC
     * says how much it read. Pictures still go through Photo or video.
     */
    fun forPc(name: String, mime: String?, bytes: ByteArray): Result {
        val type = mime.orEmpty().lowercase()
        if (type.startsWith("image/") || type.startsWith("video/")) return Result.No(Problem.Picture)
        if (bytes.size > MAX_PC_BYTES) return Result.No(Problem.TooBig)
        if (bytes.isEmpty()) return Result.No(Problem.Unreadable)
        return Result.Read(TurnFile(name, "", false, bytes = bytes, mime = type.takeIf { it != "application/octet-stream" }.orEmpty()))
    }

    /** Reads a file the owner picked for the PC. Call off the main thread. */
    fun readForPc(context: Context, uri: Uri): Result = runCatching {
        val cr = context.contentResolver
        val name = nameOf(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = cr.getType(uri)
        if (mime.orEmpty().let { it.startsWith("image/") || it.startsWith("video/") }) return Result.No(Problem.Picture)
        val bytes = cr.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (buf.size() <= MAX_PC_BYTES) { val r = input.read(chunk); if (r < 0) break; buf.write(chunk, 0, r) }
            buf.toByteArray()
        } ?: return Result.No(Problem.Unreadable)
        forPc(name, mime, bytes)
    }.getOrElse { Result.No(Problem.Unreadable) }

    /** A picked file's name as the phone shows it. */
    fun nameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Reads a file the owner picked. Call off the main thread. */
    fun read(context: Context, uri: Uri): Result = runCatching {
        val cr = context.contentResolver
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = cr.getType(uri)
        val bytes = cr.openInputStream(uri)?.use { input ->
            val buf = ByteArray(MAX_BYTES + 1)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        } ?: return Result.No(Problem.Unreadable)
        val whole = bytes.size <= MAX_BYTES
        read(name, mime, if (whole) bytes else bytes.copyOf(MAX_BYTES), whole)
    }.getOrElse { Result.No(Problem.Unreadable) }

    private fun utf8(bytes: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length.coerceAtLeast(0))).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
