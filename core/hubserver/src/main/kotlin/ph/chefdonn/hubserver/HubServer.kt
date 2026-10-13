package ph.chefdonn.hubserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ph.chefdonn.protocol.ClientMessage
import ph.chefdonn.protocol.EventEnvelope
import ph.chefdonn.protocol.MAX_PHOTO_BYTES
import ph.chefdonn.protocol.PHOTO_PATH
import ph.chefdonn.protocol.ServerMessage
import ph.chefdonn.protocol.WS_PATH
import ph.chefdonn.protocol.decodeClientMessage
import ph.chefdonn.protocol.encode
import ph.chefdonn.protocol.isValidPhotoId
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.visibleTo

@Serializable
data class ConnectedDevice(
    val deviceId: String,
    val deviceName: String,
    val role: Role,
    val connectedAt: Long,
)

class SessionRegistry {
    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val devices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    fun add(d: ConnectedDevice) {
        _devices.value = _devices.value.filter { it.deviceId != d.deviceId } + d
    }

    fun remove(deviceId: String) {
        _devices.value = _devices.value.filter { it.deviceId != deviceId }
    }
}

fun Application.hubModule(engine: HubEngine, hubName: String, registry: SessionRegistry, photos: PhotoStore) {
    install(WebSockets)
    routing {
        get("/health") { call.respondText("chef-donn-hub") }

        put("$PHOTO_PATH/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!isValidPhotoId(id)) {
                return@put call.respondText("Bad photo id", status = HttpStatusCode.BadRequest)
            }
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_PHOTO_BYTES) {
                return@put call.respondText("Photo too large", status = HttpStatusCode.PayloadTooLarge)
            }
            val packet = call.receiveChannel().readRemaining(MAX_PHOTO_BYTES + 1L)
            if (packet.remaining > MAX_PHOTO_BYTES) {
                packet.release()
                return@put call.respondText("Photo too large", status = HttpStatusCode.PayloadTooLarge)
            }
            val bytes = packet.readBytes()
            if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                return@put call.respondText("JPEG only", status = HttpStatusCode.UnsupportedMediaType)
            }
            photos.write(id, bytes)
            call.respond(HttpStatusCode.NoContent)
        }

        get("$PHOTO_PATH/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!isValidPhotoId(id)) {
                return@get call.respondText("Bad photo id", status = HttpStatusCode.BadRequest)
            }
            val bytes = photos.read(id)
                ?: return@get call.respondText("No such photo", status = HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }

        webSocket(WS_PATH) {
            val helloFrame = incoming.receive() as? Frame.Text ?: return@webSocket
            val hello = decodeClientMessage(helloFrame.readText()) as? ClientMessage.Hello ?: return@webSocket
            registry.add(ConnectedDevice(hello.deviceId, hello.deviceName, hello.role, System.currentTimeMillis()))
            try {
                send(Frame.Text(ServerMessage.Welcome(hubName, engine.currentSeq()).encode()))

                // Subscribe to live broadcasts BEFORE the catch-up query so nothing can
                // fall between them; overlap is deduped by seq below.
                val live = Channel<EventEnvelope>(Channel.UNLIMITED)
                val subscription = launch {
                    engine.broadcasts.collect { live.send(it) }
                }

                // Each role only receives the events it may see (no expense data on
                // service devices). The cursor still covers the skipped seqs.
                val catchUp = engine.eventsSince(hello.lastSeenSeq)
                catchUp.filter { it.event.visibleTo(hello.role) }.chunked(200).forEach { chunk ->
                    send(Frame.Text(ServerMessage.Events(chunk).encode()))
                }
                var lastSent = maxOf(hello.lastSeenSeq, catchUp.lastOrNull()?.seq ?: 0)

                val forwarder = launch {
                    for (env in live) {
                        if (env.seq > lastSent) {
                            if (env.event.visibleTo(hello.role)) {
                                send(Frame.Text(ServerMessage.Events(listOf(env)).encode()))
                            }
                            lastSent = env.seq
                        }
                    }
                }

                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        when (val msg = decodeClientMessage(frame.readText())) {
                            is ClientMessage.Hello -> Unit // only meaningful as the first frame
                            is ClientMessage.Submit -> {
                                val ack = engine.submit(msg.command)
                                send(Frame.Text((ack as ServerMessage).encode()))
                            }
                        }
                    }
                } finally {
                    subscription.cancel()
                    forwarder.cancel()
                }
            } finally {
                registry.remove(hello.deviceId)
            }
        }
    }
}

fun startHubServer(
    engine: HubEngine,
    port: Int,
    hubName: String,
    photos: PhotoStore,
    registry: SessionRegistry = SessionRegistry(),
): ApplicationEngine =
    embeddedServer(CIO, port = port) {
        hubModule(engine, hubName, registry, photos)
    }.start(wait = false)
