package ph.chefdonn.hubserver

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ph.chefdonn.protocol.ClientMessage
import ph.chefdonn.protocol.EventEnvelope
import ph.chefdonn.protocol.ServerMessage
import ph.chefdonn.protocol.WS_PATH
import ph.chefdonn.protocol.decodeClientMessage
import ph.chefdonn.protocol.encode
import ph.chefdonn.domain.Role

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

fun Application.hubModule(engine: HubEngine, hubName: String, registry: SessionRegistry) {
    install(WebSockets)
    routing {
        get("/health") { call.respondText("chef-donn-hub") }

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

                val catchUp = engine.eventsSince(hello.lastSeenSeq)
                catchUp.chunked(200).forEach { chunk ->
                    send(Frame.Text(ServerMessage.Events(chunk).encode()))
                }
                var lastSent = maxOf(hello.lastSeenSeq, catchUp.lastOrNull()?.seq ?: 0)

                val forwarder = launch {
                    for (env in live) {
                        if (env.seq > lastSent) {
                            send(Frame.Text(ServerMessage.Events(listOf(env)).encode()))
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
    registry: SessionRegistry = SessionRegistry(),
): ApplicationEngine =
    embeddedServer(CIO, port = port) {
        hubModule(engine, hubName, registry)
    }.start(wait = false)
