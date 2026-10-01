package dev.greenwave.maps

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything greenWave has stored (tracks, stops, light events, known lights, rides) as one ZIP. */
internal object DataExport {
    /** Writes the ZIP to [out]; entries are paths relative to the app's files dir. */
    fun writeZip(context: Context, out: OutputStream) {
        val files = context.applicationContext.filesDir
        val sources = files.listFiles { f -> f.name.startsWith("greenwave") && !f.name.endsWith(".tmp") }.orEmpty()
        ZipOutputStream(out.buffered()).use { zip ->
            for (source in sources) {
                source.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(files).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Development option: after every ride the latest export replaces
     * Documents/greenWave/greenwave-latest.zip, where a sync app such as
     * Syncthing can pick it up (the app's own folder is out of other apps' reach
     * since Android 11). Needs Android 10+; does nothing below.
     */
    fun toDocuments(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(FOLDER, FILE),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            val uri = existing ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                },
            ) ?: return
            resolver.openOutputStream(uri, "wt")?.use { writeZip(context, it) }
            Log.i("GreenWave", "exported to $FOLDER$FILE")
        } catch (e: Exception) {
            Log.w("GreenWave", "export to Documents failed", e)
        }
    }

    private const val FOLDER = "Documents/greenWave/"
    private const val FILE = "greenwave-latest.zip"
}
