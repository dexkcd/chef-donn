package ph.chefdonn.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.DomainEvent
import ph.chefdonn.domain.DomainJson
import ph.chefdonn.domain.Role

const val WS_PATH = "/ws"
const val DEFAULT_HUB_PORT = 8577
const val NSD_SERVICE_TYPE = "_chefdonn._tcp"

/** An event with its hub-assigned position in the log. seq is the sync cursor. */
@Serializable
data class EventEnvelope(
    val seq: Long,
    val event: DomainEvent,
)

@Serializable
sealed interface ClientMessage {

    /** First frame after connect. lastSeenSeq=0 means "send me everything". */
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val role: Role,
        val lastSeenSeq: Long,
    ) : ClientMessage

    @Serializable
    @SerialName("Submit")
    data class Submit(val command: Command) : ClientMessage
}

@Serializable
sealed interface ServerMessage {

    /**
     * Reply to Hello. Catch-up events from lastSeenSeq+1 follow as Events frames,
     * then live broadcasts — one ordered stream, no gap between catch-up and live.
     */
    @Serializable
    @SerialName("Welcome")
    data class Welcome(
        val hubName: String,
        val currentSeq: Long,
    ) : ServerMessage

    @Serializable
    @SerialName("Events")
    data class Events(val events: List<EventEnvelope>) : ServerMessage

    /** Ack for a submitted command. Duplicates ack as accepted (idempotent). */
    @Serializable
    @SerialName("Ack")
    data class Ack(
        val commandId: String,
        val accepted: Boolean,
        /** Human-readable, user-facing. Only set when rejected. */
        val reason: String? = null,
    ) : ServerMessage
}

fun ClientMessage.encode(): String = DomainJson.encodeToString(ClientMessage.serializer(), this)
fun ServerMessage.encode(): String = DomainJson.encodeToString(ServerMessage.serializer(), this)
fun decodeClientMessage(text: String): ClientMessage = DomainJson.decodeFromString(ClientMessage.serializer(), text)
fun decodeServerMessage(text: String): ServerMessage = DomainJson.decodeFromString(ServerMessage.serializer(), text)
