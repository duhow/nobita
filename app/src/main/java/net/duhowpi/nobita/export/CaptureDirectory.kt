package net.duhowpi.nobita.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

object CaptureDirectory {
    private const val PREFS = "capture_directory"
    private const val URI = "tree_uri"
    private const val PATH = "path"

    fun uri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(URI, null)?.let(Uri::parse)

    fun path(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(PATH, null)

    fun save(context: Context, uri: Uri, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URI, uri.toString()).putString(PATH, path).apply()
    }

    fun isConfigured(context: Context): Boolean {
        val selected = uri(context) ?: return false
        val directory = path(context)?.let(::File) ?: return false
        return DocumentFile.fromTreeUri(context, selected)?.canWrite() == true &&
            directory.isDirectory
    }

    fun resolveTreeUri(uri: Uri): String? {
        val documentId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val separator = documentId.indexOf(':')
        val volume = if (separator >= 0) documentId.substring(0, separator) else ""
        val relative = if (separator >= 0) documentId.substring(separator + 1) else documentId
        val root: File
        val directory: File
        when {
            volume.equals("primary", ignoreCase = true) || volume.isEmpty() -> {
                root = Environment.getExternalStorageDirectory()
                directory = if (relative.isEmpty()) root else File(root, relative)
            }
            volume.equals("raw", ignoreCase = true) -> {
                root = File(relative)
                directory = root
            }
            else -> return null
        }
        val canonical = directory.canonicalFile
        require(canonical.path == root.canonicalFile.path || canonical.path.startsWith(root.canonicalFile.path + File.separator)) {
            "Selected capture folder is invalid"
        }
        return canonical.path
    }
}
