package net.duhowpi.nobita.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object CaptureFileStore {
    fun importPcapng(context: Context, path: String): Uri {
        val stagingRoot = File(requireNotNull(context.getExternalFilesDir(null)), "BluetoothCaptures").canonicalFile
        val source = File(path).canonicalFile
        require(source.parentFile == stagingRoot && source.name.endsWith(".pcapng")) {
            "Exported capture is outside Nobita staging"
        }
        require(source.isFile) { "Exported capture does not exist: $path" }
        if (Build.VERSION.SDK_INT < 29) return FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.tcpdump.pcap")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BluetoothCaptures")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a Downloads item")
        try {
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to open Downloads item")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            source.delete()
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
