package ph.chefdonn.hubserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.client.DirectoryPhotoOutbox
import ph.chefdonn.client.PhotoSync
import ph.chefdonn.client.SyncState
import ph.chefdonn.domain.BillOrder
import ph.chefdonn.domain.ExpenseCategory
import ph.chefdonn.domain.ReceiptSpec
import ph.chefdonn.domain.RecordReceipt
import ph.chefdonn.domain.UpdateExpenseCategories
import ph.chefdonn.domain.FireOrder
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.SetLineStatus
import ph.chefdonn.domain.VoidLine
import ph.chefdonn.protocol.MAX_PHOTO_BYTES
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // Photos sit next to the db, so a restart on the same path keeps them too.
        val photos = FilePhotoStore(File(File(dbPath).parentFile, "photos"))
        val server = startHubServer(engine, port, "Test Hub", photos)
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

        // A bump racing the void is rejected loudly, not silently swallowed. Listen
        // before submitting: acks don't replay, so a fast rejection could be missed.
        val rejection = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { kitchen.acks.filter { !it.accepted }.first() }
        }
        kitchen.submit(SetLineStatus(UUID.randomUUID().toString(), "dev-Kusina", "Kusina", "o1", listOf(gone.lineId), LineStatus.READY))
        val ack = rejection.await()
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

    // ---- receipts ----

    private val fuel = ExpenseCategory("cat-fuel", "LPG & Fuel")

    private fun photoSync(): PhotoSync {
        val dir = Files.createTempDirectory("device").toFile()
        val sync = PhotoSync(scope, DirectoryPhotoOutbox(File(dir, "outbox"), File(dir, "cache")), retryDelayMs = 200)
        cleanups += { sync.close() }
        return sync
    }

    /** Smallest thing that passes the hub's JPEG check: SOI marker + payload. */
    private fun fakeJpeg(size: Int = 2048) = ByteArray(size) { (it % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
    }

    private fun receiptSpec(photoId: String) =
        ReceiptSpec(UUID.randomUUID().toString(), "Petron Banawe", 187435, "2026-10-09", fuel.id, listOf(photoId))

    @Test
    fun `office records a receipt with a photo, another office device sees both`(): Unit = runBlocking {
        val port = freePort()
        val (engine, _) = startServer(tmpDb(), port)
        engine.submit(UpdateExpenseCategories(UUID.randomUUID().toString(), "hub", "Hub", listOf(fuel)))

        val tablet = client(Role.OFFICE, "Tablet", port)
        val phone = client(Role.OFFICE, "Phone", port)
        val tabletPhotos = photoSync().also { it.start("127.0.0.1", port) }
        val phonePhotos = photoSync().also { it.start("127.0.0.1", port) }

        val photoId = UUID.randomUUID().toString()
        val jpeg = fakeJpeg()
        tabletPhotos.enqueue(photoId, jpeg)
        val spec = receiptSpec(photoId)
        tablet.submit(RecordReceipt(UUID.randomUUID().toString(), "dev-Tablet", "Tablet", spec))

        val seen = phone.state.await { it.receipts.containsKey(spec.receiptId) }.receipts.getValue(spec.receiptId)
        assertEquals(spec, seen.spec)
        tabletPhotos.pendingIds.await { it.isEmpty() }
        assertContentEquals(jpeg, phonePhotos.fetch(photoId))
    }

    @Test
    fun `service devices never receive expense data`(): Unit = runBlocking {
        val port = freePort()
        val (engine, _) = startServer(tmpDb(), port)
        val waiter = client(Role.WAITER, "Ana", port)
        val office = client(Role.OFFICE, "Tablet", port)
        waiter.sync.await { it == SyncState.ONLINE }

        engine.submit(UpdateExpenseCategories(UUID.randomUUID().toString(), "hub", "Hub", listOf(fuel)))
        office.submit(RecordReceipt(UUID.randomUUID().toString(), "dev-Tablet", "Tablet", receiptSpec(UUID.randomUUID().toString())))
        office.state.await { it.receipts.isNotEmpty() }

        // An order fired after the receipt reaches the waiter; the receipt never does.
        waiter.submit(FireOrder(UUID.randomUUID().toString(), "dev-Ana", "Ana", "o1", "t1", 2, listOf(testSpec())))
        val waiterState = waiter.state.await { it.orders.containsKey("o1") }
        assertTrue(waiterState.receipts.isEmpty())
        assertTrue(waiterState.expenseCategories.isEmpty())

        // A late-joining waiter catching up from seq 0 is filtered the same way.
        val late = client(Role.WAITER, "Ben", port)
        val lateState = late.state.await { it.orders.containsKey("o1") }
        assertTrue(lateState.receipts.isEmpty())
        // The office device still gets the order stream.
        office.state.await { it.orders.containsKey("o1") }
    }

    @Test
    fun `receipt and photo taken off mesh both land after the hub comes back`(): Unit = runBlocking {
        val port = freePort()
        val dbPath = tmpDb()
        val (engine, stop) = startServer(dbPath, port)
        engine.submit(UpdateExpenseCategories(UUID.randomUUID().toString(), "hub", "Hub", listOf(fuel)))
        val office = client(Role.OFFICE, "Tablet", port)
        val photos = photoSync().also { it.start("127.0.0.1", port) }
        office.state.await { it.expenseCategories.isNotEmpty() }

        stop()
        office.sync.await { it != SyncState.ONLINE }

        val photoId = UUID.randomUUID().toString()
        val jpeg = fakeJpeg()
        photos.enqueue(photoId, jpeg)
        val spec = receiptSpec(photoId)
        office.submit(RecordReceipt(UUID.randomUUID().toString(), "dev-Tablet", "Tablet", spec))
        office.sync.await { it == SyncState.QUEUING }
        assertEquals(setOf(photoId), photos.pendingIds.value)

        val (engine2, _) = startServer(dbPath, port)
        engine2.state.await { it.receipts.containsKey(spec.receiptId) }
        photos.pendingIds.await { it.isEmpty() }
        office.pendingCommands.await { it.isEmpty() }

        // Fresh device, nothing cached: the bytes come from the hub.
        assertContentEquals(jpeg, photoSync().also { it.start("127.0.0.1", port) }.fetch(photoId))
    }

    @Test
    fun `photo endpoint rejects bad ids, oversize bodies, and non-JPEGs`(): Unit = runBlocking {
        val port = freePort()
        startServer(tmpDb(), port)
        val http = HttpClient(CIO) { expectSuccess = false }
        cleanups += { http.close() }
        val base = "http://127.0.0.1:$port/photos"

        assertEquals(HttpStatusCode.BadRequest, http.put("$base/..%2Fhub.db") { setBody(fakeJpeg()) }.status)
        assertEquals(HttpStatusCode.BadRequest, http.get("$base/not-a-uuid").status)
        val id = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.PayloadTooLarge, http.put("$base/$id") { setBody(fakeJpeg(MAX_PHOTO_BYTES + 1)) }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, http.put("$base/$id") { setBody("hello".toByteArray()) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("$base/$id").status)

        // Write-once: a retried upload keeps the first bytes.
        val first = fakeJpeg(100)
        assertEquals(HttpStatusCode.NoContent, http.put("$base/$id") { setBody(first) }.status)
        assertEquals(HttpStatusCode.NoContent, http.put("$base/$id") { setBody(fakeJpeg(200)) }.status)
        assertNull(photoSync().fetch("not-a-uuid"))
        assertContentEquals(first, photoSync().also { it.start("127.0.0.1", port) }.fetch(id))
    }
}
