package ph.chefdonn.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.reduce
import ph.chefdonn.protocol.ClientMessage
import ph.chefdonn.protocol.ServerMessage
import ph.chefdonn.protocol.WS_PATH
import ph.chefdonn.protocol.decodeServerMessage
import ph.chefdonn.protocol.encode

enum class SyncState {
    /** Connected to the hub, stream live. */
    ONLINE,
    /** Off mesh with actions waiting to sync — the gold banner state. */
    QUEUING,
    /** Off mesh, nothing queued. */
    OFFLINE,
}

/**
 * Where unsynced commands and the sync cursor live. In-memory by default; the staff
 * app supplies a disk-backed store so a queued void survives an app kill (M3).
 */
interface PendingStore {
    fun loadPending(): List<Command>
    fun addPending(command: Command)
    fun removePending(commandId: String)
    fun loadLastSeenSeq(): Long
    fun saveLastSeenSeq(seq: Long)
}

class InMemoryPendingStore : PendingStore {
    private val pending = LinkedHashMap<String, Command>()
    private var lastSeen = 0L
    override fun loadPending() = pending.values.toList()
    override fun addPending(command: Command) { pending[command.commandId] = command }
    override fun removePending(commandId: String) { pending.remove(commandId) }
    override fun loadLastSeenSeq() = lastSeen
    override fun saveLastSeenSeq(seq: Long) { lastSeen = seq }
}

/**
 * One long-lived client per device. [submit] never blocks on the network and never
 * loses a command: it queues, updates the optimistic overlay, and the connection loop
 * drains the queue whenever the hub is reachable. State is a fold of hub events —
 * identical [reduce] to the hub, so a synced client and the hub can never disagree.
 */
class ChefDonnClient(
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val role: Role,
    private val store: PendingStore = InMemoryPendingStore(),
    private val reconnectDelayMs: Long = 2000,
) {
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, Command>()

    private val _state = MutableStateFlow(AppState())
    /** Authoritative state as of lastSeenSeq (no optimistic overlay). */
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _sync = MutableStateFlow(SyncState.OFFLINE)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    private val _acks = MutableSharedFlow<ServerMessage.Ack>(extraBufferCapacity = 256)
    /** Rejections surface here — "never silently drop an item". */
    val acks: SharedFlow<ServerMessage.Ack> = _acks.asSharedFlow()

    @Volatile
    var lastSeenSeq: Long = 0
        private set

    private var connectJob: Job? = null
    private val wakeSender = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        store.loadPending().forEach { pending[it.commandId] = it }
        lastSeenSeq = store.loadLastSeenSeq()
        _pendingCount.value = pending.size
        refreshDisconnectedSyncState()
    }

    fun connect(host: String, port: Int) {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (isActive) {
                try {
                    http.webSocket(host = host, port = port, path = WS_PATH) {
                        send(Frame.Text(ClientMessage.Hello(deviceId, deviceName, role, lastSeenSeq).encode()))
                        val welcome = decodeServerMessage((incoming.receive() as Frame.Text).readText())
                        check(welcome is ServerMessage.Welcome) { "expected Welcome" }
                        _sync.value = SyncState.ONLINE

                        val sender = launch {
                            replayPending { send(Frame.Text(ClientMessage.Submit(it).encode())) }
                            for (unused in wakeSender) {
                                replayPending { send(Frame.Text(ClientMessage.Submit(it).encode())) }
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                onServerMessage(decodeServerMessage(frame.readText()))
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                } catch (_: Exception) {
                    // fall through to retry
                }
                markDisconnected()
                delay(reconnectDelayMs)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        markDisconnected()
    }

    /** Queue-and-forget. The ack (or rejection) arrives on [acks]. */
    suspend fun submit(command: Command) {
        mutex.withLock {
            pending[command.commandId] = command
            store.addPending(command)
            _pendingCount.value = pending.size
        }
        if (_sync.value != SyncState.ONLINE) refreshDisconnectedSyncState()
        wakeSender.trySend(Unit)
    }

    private suspend fun replayPending(sendFrame: suspend (Command) -> Unit) {
        val snapshot = mutex.withLock { pending.values.toList() }
        snapshot.forEach { sendFrame(it) }
    }

    private suspend fun onServerMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Welcome -> Unit
            is ServerMessage.Events -> {
                var s = _state.value
                msg.events.forEach { env ->
                    if (env.seq > lastSeenSeq) {
                        s = reduce(s, env.event)
                        lastSeenSeq = env.seq
                    }
                }
                _state.value = s
                store.saveLastSeenSeq(lastSeenSeq)
            }
            is ServerMessage.Ack -> {
                mutex.withLock {
                    pending.remove(msg.commandId)
                    store.removePending(msg.commandId)
                    _pendingCount.value = pending.size
                }
                _acks.emit(msg)
            }
        }
    }

    private fun markDisconnected() {
        _sync.value = if (pending.isEmpty()) SyncState.OFFLINE else SyncState.QUEUING
    }

    private fun refreshDisconnectedSyncState() {
        if (_sync.value != SyncState.ONLINE) markDisconnected()
    }

    fun close() {
        disconnect()
        http.close()
    }
}
