package ph.chefdonn.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ph.chefdonn.protocol.PHOTO_PATH
import ph.chefdonn.protocol.isValidPhotoId
import java.io.File

/**
 * Device-side photo storage: photos waiting for the hub (the outbox) and photos the
 * hub already has (the cache). Disk-backed so a receipt photographed off mesh
 * survives an app kill, the same promise [PendingStore] makes for commands.
 */
interface PhotoOutbox {
    /** Oldest first. */
    fun pendingIds(): List<String>
    fun add(photoId: String, bytes: ByteArray)
    fun readPending(photoId: String): ByteArray?
    /** The hub has it: move it from the outbox to the cache. */
    fun markUploaded(photoId: String)
    fun readCached(photoId: String): ByteArray?
    fun cache(photoId: String, bytes: ByteArray)
}

class DirectoryPhotoOutbox(private val outboxDir: File, private val cacheDir: File) : PhotoOutbox {

    init {
        outboxDir.mkdirs()
        cacheDir.mkdirs()
    }

    private fun name(photoId: String): String {
        require(isValidPhotoId(photoId)) { "bad photo id" }
        return "$photoId.jpg"
    }

    override fun pendingIds(): List<String> =
        (outboxDir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .map { it.name.removeSuffix(".jpg") }

    override fun add(photoId: String, bytes: ByteArray) = writeAtomically(File(outboxDir, name(photoId)), bytes)

    override fun readPending(photoId: String): ByteArray? =
        File(outboxDir, name(photoId)).takeIf { it.isFile }?.readBytes()

    override fun markUploaded(photoId: String) {
        val from = File(outboxDir, name(photoId))
        val to = File(cacheDir, name(photoId))
        if (!from.renameTo(to) && from.isFile) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    override fun readCached(photoId: String): ByteArray? =
        File(cacheDir, name(photoId)).takeIf { it.isFile }?.readBytes()

    override fun cache(photoId: String, bytes: ByteArray) = writeAtomically(File(cacheDir, name(photoId)), bytes)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/**
 * Uploads queued receipt photos to the hub and fetches photos other devices took.
 * Runs beside [ChefDonnClient]: a receipt's command and its photos sync independently,
 * each idempotent by client-generated id, so either may land first.
 */
class PhotoSync(
    private val scope: CoroutineScope,
    private val outbox: PhotoOutbox,
    private val retryDelayMs: Long = 3000,
) {
    private val http = HttpClient(CIO) { expectSuccess = false }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile
    private var baseUrl: String? = null

    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())
    /** Photos still waiting for the hub — the "PHOTO PENDING" tag on a receipt. */
    val pendingIds: StateFlow<Set<String>> = _pendingIds.asStateFlow()

    fun start(host: String, port: Int) {
        baseUrl = "http://$host:$port$PHOTO_PATH"
        job?.cancel()
        job = scope.launch {
            _pendingIds.value = io { outbox.pendingIds().toSet() }
            while (isActive) {
                val allSent = drain()
                // Sleep until a new photo arrives; after a failure, also retry on a timer.
                if (allSent) wake.receive() else withTimeoutOrNull(retryDelayMs) { wake.receive() }
            }
        }
    }

    /** Durable before it returns; the upload happens whenever the hub is reachable. */
    suspend fun enqueue(photoId: String, bytes: ByteArray) {
        require(isValidPhotoId(photoId)) { "bad photo id" }
        io { outbox.add(photoId, bytes) }
        _pendingIds.update { it + photoId }
        wake.trySend(Unit)
    }

    /** Local copy first (this device took it, or saw it before), then the hub. */
    suspend fun fetch(photoId: String): ByteArray? {
        if (!isValidPhotoId(photoId)) return null
        io { outbox.readCached(photoId) ?: outbox.readPending(photoId) }?.let { return it }
        val base = baseUrl ?: return null
        return try {
            val resp = http.get("$base/$photoId")
            if (resp.status != HttpStatusCode.OK) return null
            resp.readBytes().also { bytes -> io { outbox.cache(photoId, bytes) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** True when the outbox is empty. One bad upload never blocks the ones behind it. */
    private suspend fun drain(): Boolean {
        val base = baseUrl ?: return false
        var allSent = true
        for (id in io { outbox.pendingIds() }) {
            val bytes = io { outbox.readPending(id) } ?: continue
            val sent = try {
                http.put("$base/$id") {
                    contentType(ContentType.Image.JPEG)
                    setBody(bytes)
                }.status.isSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (sent) {
                io { outbox.markUploaded(id) }
                _pendingIds.update { it - id }
            } else {
                allSent = false
            }
        }
        return allSent
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun close() {
        job?.cancel()
        job = null
        http.close()
    }
}
