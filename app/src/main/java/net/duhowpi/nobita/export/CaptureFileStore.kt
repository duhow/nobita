package net.duhowpi.nobita.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object CaptureFileStore {
    @Synchronized
    fun importPcapng(context: Context, path: String): Uri {
        val source = File(path).canonicalFile
        require(source.isFile && source.name.endsWith(".pcapng")) { "Exported capture does not exist: $path" }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", source)
    }
}
