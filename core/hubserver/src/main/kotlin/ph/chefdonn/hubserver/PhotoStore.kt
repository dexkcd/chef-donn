package ph.chefdonn.hubserver

import ph.chefdonn.protocol.isValidPhotoId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Receipt photos, kept beside the event log rather than in it: the log stays small
 * and replays fast, while photos are write-once blobs addressed by photo id.
 */
interface PhotoStore {
    fun read(photoId: String): ByteArray?
    /** Write-once: a second write of the same id keeps the first bytes. */
    fun write(photoId: String, bytes: ByteArray)
    fun count(): Int
}

class FilePhotoStore(private val dir: File) : PhotoStore {

    init {
        dir.mkdirs()
    }

    private fun fileFor(photoId: String): File {
        require(isValidPhotoId(photoId)) { "bad photo id" }
        return File(dir, "$photoId.jpg")
    }

    override fun read(photoId: String): ByteArray? =
        fileFor(photoId).takeIf { it.isFile }?.readBytes()

    @Synchronized
    override fun write(photoId: String, bytes: ByteArray) {
        val target = fileFor(photoId)
        if (target.exists()) return
        // Temp file + atomic rename: a power cut never leaves a half-written photo.
        val tmp = File(dir, "$photoId.part")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun count(): Int = dir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0
}
