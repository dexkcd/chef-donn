package ph.chefdonn.hubserver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.Decision
import ph.chefdonn.domain.DomainEvent
import ph.chefdonn.domain.DomainJson
import ph.chefdonn.domain.decide
import ph.chefdonn.domain.reduce
import ph.chefdonn.hubserver.db.HubDatabase
import ph.chefdonn.protocol.EventEnvelope
import ph.chefdonn.protocol.ServerMessage

/**
 * The single authority over the order stream. All writes are serialized through one
 * mutex: decide → append to SQLite (durable before ack) → fold into state → broadcast.
 * On construction it replays the whole log, so a hub reboot loses nothing.
 */
class HubEngine(
    private val db: HubDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val appliedCommandIds: MutableSet<String>

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _broadcasts = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 4096)
    val broadcasts: SharedFlow<EventEnvelope> = _broadcasts.asSharedFlow()

    init {
        var s = AppState()
        db.eventLogQueries.eventsSince(0).executeAsList().forEach { row ->
            s = reduce(s, decodeEvent(row.payload))
        }
        _state.value = s
        appliedCommandIds = db.eventLogQueries.appliedCommandIds()
            .executeAsList().filterNotNull().toMutableSet()
    }

    fun currentSeq(): Long = db.eventLogQueries.currentSeq().executeAsOne()

    fun eventsSince(afterSeq: Long): List<EventEnvelope> =
        db.eventLogQueries.eventsSince(afterSeq).executeAsList().map { row ->
            EventEnvelope(row.seq, decodeEvent(row.payload))
        }

    suspend fun submit(cmd: Command): ServerMessage.Ack = mutex.withLock {
        if (cmd.commandId in appliedCommandIds) {
            // Replayed from an offline queue after the original got through — idempotent.
            return ServerMessage.Ack(cmd.commandId, accepted = true)
        }
        when (val decision = decide(_state.value, cmd, clock())) {
            is Decision.Rejected -> ServerMessage.Ack(cmd.commandId, accepted = false, reason = decision.reason)
            is Decision.Accepted -> {
                val envelopes = append(cmd.commandId, decision.events)
                var s = _state.value
                envelopes.forEach { s = reduce(s, it.event) }
                _state.value = s
                appliedCommandIds += cmd.commandId
                envelopes.forEach { _broadcasts.tryEmit(it) }
                ServerMessage.Ack(cmd.commandId, accepted = true)
            }
        }
    }

    private fun append(commandId: String, events: List<DomainEvent>): List<EventEnvelope> =
        db.eventLogQueries.transactionWithResult {
            events.map { event ->
                db.eventLogQueries.insertEvent(commandId, event.at, encodeEvent(event))
                EventEnvelope(db.eventLogQueries.lastInsertSeq().executeAsOne(), event)
            }
        }

    private fun encodeEvent(e: DomainEvent) = DomainJson.encodeToString(DomainEvent.serializer(), e)
    private fun decodeEvent(s: String) = DomainJson.decodeFromString(DomainEvent.serializer(), s)
}
