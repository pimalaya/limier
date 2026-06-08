package org.pimalaya.limier

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import org.pimalaya.limier.client.MessagePart

/**
 * Minimal read-only provider that vends a fetched attachment from the cache as a `content://` URI,
 * so another installed app can open a part (a PDF, say) without limier pulling in AndroidX's
 * FileProvider. A `file://` URI would throw FileUriExposedException on the supported API levels.
 */
class AttachmentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(cacheRoot(context!!), uri.lastPathSegment.orEmpty())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null

    /** Backs the OpenableColumns a viewer reads to label the file. */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val name = uri.lastPathSegment.orEmpty()
        val size = File(cacheRoot(context!!), name).length()
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> size
                    else -> null
                }
            }
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        /** Writes [part] to the cache and returns a shareable URI for it. */
        fun cache(context: Context, part: MessagePart): Uri {
            val name = safeName(part.filename ?: context.getString(R.string.attachment_default_name))
            val root = cacheRoot(context)
            root.mkdirs()
            File(root, name).writeBytes(part.data ?: ByteArray(0))
            return Uri.parse("content://${context.packageName}.attachments/$name")
        }

        private fun cacheRoot(context: Context): File = File(context.cacheDir, "attachments")

        /** Strips path separators so the name maps to a single cache file. */
        private fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "attachment" }
    }
}
