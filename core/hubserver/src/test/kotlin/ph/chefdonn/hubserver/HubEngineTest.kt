package ph.chefdonn.hubserver

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ph.chefdonn.domain.AddLines
import ph.chefdonn.domain.BillOrder
import ph.chefdonn.domain.FireOrder
import ph.chefdonn.domain.LineSpec
import ph.chefdonn.domain.LineStatus
import ph.chefdonn.domain.Role
import ph.chefdonn.domain.SetLineStatus
import ph.chefdonn.domain.VoidLine
import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun testSpec(lineId: String = UUID.randomUUID().toString(), name: String = "Adobo") =
    LineSpec(lineId, "m1", name, 25000, 1, "MAINS")

fun fileDb(path: String) = hubDatabase(JdbcSqliteDriver("jdbc:sqlite:$path", Properties()))
fun memDb() = hubDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()))

class HubEngineTest {

    private fun fire(orderId: String, tableId: String = "t1", vararg lines: LineSpec) =
        FireOrder(UUID.randomUUID().toString(), "dev1", "Ana", orderId, tableId, 2, lines.toList())

    @Test
    fun `fire bump bill happy path`() = runTest {
        val engine = HubEngine(memDb())
        val l = testSpec()
        assertTrue(engine.submit(fire("o1", lines = arrayOf(l))).accepted)
        assertTrue(engine.submit(SetLineStatus("c2", "kds", "Kusina", "o1", listOf(l.lineId), LineStatus.READY)).accepted)
        assertTrue(engine.submit(BillOrder("c3", "cash", "Cashier", "o1")).accepted)
        val order = engine.state.value.orders.getValue("o1")
        assertEquals(LineStatus.READY, order.lines.single().status)
        assertTrue(order.billed)
        assertEquals(3 + 1, engine.currentSeq().toInt()) // OrderOpened, LinesAdded, LineStatusSet, OrderBilled
    }

    @Test
    fun `duplicate command is applied once`() = runTest {
        val engine = HubEngine(memDb())
        val cmd = fire("o1", lines = arrayOf(testSpec()))
        assertTrue(engine.submit(cmd).accepted)
        assertTrue(engine.submit(cmd).accepted)
        assertEquals(1, engine.state.value.orders.size)
        assertEquals(1, engine.state.value.orders.getValue("o1").lines.size)
    }

    @Test
    fun `state survives engine restart from the same file`() = runTest {
        val path = Files.createTempDirectory("hub").resolve("hub.db").toString()
        val l = testSpec()
        run {
            val engine = HubEngine(fileDb(path))
            engine.submit(fire("o1", lines = arrayOf(l)))
            engine.submit(VoidLine("c2", "dev1", "Ana", "o1", l.lineId, "Out of stock", Role.WAITER))
        }
        val reborn = HubEngine(fileDb(path))
        val line = reborn.state.value.line("o1", l.lineId)!!
        assertEquals(LineStatus.VOIDED, line.status)
        assertEquals("Out of stock", line.voidReason)
        // Idempotency set also survives: replaying the original command is a no-op.
        val replay = fire("o1", lines = arrayOf(l)).let { it.copy(commandId = it.commandId) }
        assertTrue(reborn.submit(replay).accepted)
    }

    @Test
    fun `void beats concurrent bump and the bump is rejected loudly`() = runTest {
        val engine = HubEngine(memDb())
        val l = testSpec()
        engine.submit(fire("o1", lines = arrayOf(l)))
        assertTrue(engine.submit(VoidLine("c2", "w1", "Ana", "o1", l.lineId, "86'd", Role.KITCHEN)).accepted)
        val bump = engine.submit(SetLineStatus("c3", "kds", "Kusina", "o1", listOf(l.lineId), LineStatus.READY))
        assertFalse(bump.accepted)
        assertEquals(LineStatus.VOIDED, engine.state.value.line("o1", l.lineId)!!.status)
    }

    @Test
    fun `second fire on same table merges into open order`() = runTest {
        val engine = HubEngine(memDb())
        engine.submit(fire("o1", lines = arrayOf(testSpec())))
        engine.submit(fire("o2", lines = arrayOf(testSpec(name = "Lumpia"))))
        assertEquals(1, engine.state.value.orders.size)
        assertEquals(2, engine.state.value.orders.getValue("o1").lines.size)
    }

    @Test
    fun `add lines to billed order is rejected`() = runTest {
        val engine = HubEngine(memDb())
        engine.submit(fire("o1", lines = arrayOf(testSpec())))
        engine.submit(BillOrder("c2", "cash", "Cashier", "o1"))
        val ack = engine.submit(AddLines("c3", "dev1", "Ana", "o1", listOf(testSpec())))
        assertFalse(ack.accepted)
        assertTrue(ack.reason!!.contains("billed"))
    }
}
