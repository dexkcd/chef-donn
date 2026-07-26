package ph.chefdonn.hubserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.client.SyncState
import ph.chefdonn.domain.BillOrder
import ph.chefdonn.domain.FireOrder
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.SetLineStatus
import ph.chefdonn.domain.VoidLine
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 15_000L

class HubServerIntegrationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.forEach { runCatching(it) }
        scope.cancel()
    }

    private fun startServer(dbPath: String, port: Int): Pair<HubEngine, () -> Unit> {
        val engine = HubEngine(fileDb(dbPath))
        val server = startHubServer(engine, port, "Test Hub")
        val stop = { server.stop(100, 500) }
        cleanups += stop
        return engine to stop
    }

    private fun client(role: Role, name: String, port: Int): ChefDonnClient {
        val c = ChefDonnClient(scope, "dev-$name", name, role, reconnectDelayMs = 200)
        c.connect("127.0.0.1", port)
        cleanups += { c.close() }
        return c
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(TIMEOUT_MS) { filter(predicate).first() }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun tmpDb() = Files.createTempDirectory("hub-it").resolve("hub.db").toString()

    @Test
    fun `waiter fires, kitchen bumps, cashier sees and bills — across real sockets`(): Unit = runBlocking {
        val port = freePort()
        startServer(tmpDb(), port)
        val waiter = client(Role.WAITER, "Ana", port)
        val kitchen = client(Role.KITCHEN, "Kusina", port)
        val cashier = client(Role.CASHIER, "Caja", port)

        waiter.sync.await { it == SyncState.ONLINE }

        val line = testSpec(name = "Kare-Kare")
        waiter.submit(FireOrder(UUID.randomUUID().toString(), "dev-Ana", "Ana", "o1", "t4", 3, listOf(line)))

        // Kitchen sees the queued ticket.
        val kitchenOrder = kitchen.state
            .await { it.orders["o1"]?.lines?.isNotEmpty() == true }
            .orders.getValue("o1")
        assertEquals(LineStatus.QUEUED, kitchenOrder.lines.single().status)

        // Kitchen bumps → waiter and cashier both see READY.
        kitchen.submit(SetLineStatus(UUID.randomUUID().toString(), "dev-Kusina", "Kusina", "o1", listOf(line.lineId), LineStatus.READY))
        waiter.state.await { it.line("o1", line.lineId)?.status == LineStatus.READY }
        cashier.state.await { it.line("o1", line.lineId)?.status == LineStatus.READY }

        // Cashier's tab total is the fired amount; billing closes the tab everywhere.
        assertEquals(25000, cashier.state.value.orders.getValue("o1").totalCents)
        cashier.submit(BillOrder(UUID.randomUUID().toString(), "dev-Caja", "Caja", "o1"))
        waiter.state.await { it.orders.getValue("o1").billed }
    }

    @Test
    fun `void propagates to all apps and beats a concurrent bump`(): Unit = runBlocking {
        val port = freePort()
        startServer(tmpDb(), port)
        val waiter = client(Role.WAITER, "Ana", port)
        val kitchen = client(Role.KITCHEN, "Kusina", port)
        val cashier = client(Role.CASHIER, "Caja", port)
        waiter.sync.await { it == SyncState.ONLINE }

        val keep = testSpec(name = "Sisig")
        val gone = testSpec(name = "Crispy Pata")
        waiter.submit(FireOrder(UUID.randomUUID().toString(), "dev-Ana", "Ana", "o1", "t2", 2, listOf(keep, gone)))
        kitchen.state.await { it.orders["o1"]?.lines?.size == 2 }

        // Kitchen voids (out of stock) …
        kitchen.submit(VoidLine(UUID.randomUUID().toString(), "dev-Kusina", "Kusina", "o1", gone.lineId, "Out of stock", Role.KITCHEN))
        cashier.state.await { it.line("o1", gone.lineId)?.status == LineStatus.VOIDED }

        // … cashier's tab drops the amount instantly.
        assertEquals(25000, cashier.state.value.orders.getValue("o1").totalCents)

        // A bump racing the void is rejected loudly, not silently swallowed.
        kitchen.submit(SetLineStatus(UUID.randomUUID().toString(), "dev-Kusina", "Kusina", "o1", listOf(gone.lineId), LineStatus.READY))
        val ack = withTimeout(TIMEOUT_MS) { kitchen.acks.filter { !it.accepted }.first() }
        assertTrue(ack.reason!!.contains("voided"))
        assertEquals(LineStatus.VOIDED, waiter.state.await { it.line("o1", gone.lineId) != null }.line("o1", gone.lineId)!!.status)
    }

    @Test
    fun `commands queued while hub is down survive and sync on reconnect`(): Unit = runBlocking {
        val port = freePort()
        val dbPath = tmpDb()
        val (_, stop) = startServer(dbPath, port)
        val waiter = client(Role.WAITER, "Ana", port)
        waiter.sync.await { it == SyncState.ONLINE }

        // Hub dies mid-service (brownout).
        stop()
        waiter.sync.await { it != SyncState.ONLINE }

        // Waiter keeps taking the order — command queues, app reports QUEUING.
        val line = testSpec(name = "Halo-Halo")
        waiter.submit(FireOrder(UUID.randomUUID().toString(), "dev-Ana", "Ana", "o1", "t7", 2, listOf(line)))
        waiter.sync.await { it == SyncState.QUEUING }
        assertEquals(1, waiter.pendingCount.value)

        // Hub reboots from the same log; queued command drains, everyone converges.
        val (engine2, _) = startServer(dbPath, port)
        waiter.sync.await { it == SyncState.ONLINE }
        waiter.state.await { it.orders.containsKey("o1") }
        assertEquals(0, waiter.pendingCount.await { it == 0 })
        assertTrue(engine2.state.await { it.orders.containsKey("o1") }.orders.getValue("o1").lines.isNotEmpty())

        // A late-joining cashier catches up from seq 0 and sees the same order.
        val cashier = client(Role.CASHIER, "Caja", port)
        val order = cashier.state.await { it.orders.containsKey("o1") }.orders.getValue("o1")
        assertEquals("t7", order.tableId)
        assertFalse(order.billed)
    }
}
