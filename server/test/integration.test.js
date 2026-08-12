// Real sockets, real server, the real browser client. These cover the failure
// modes that matter during a Saturday dinner rush: three roles staying in sync,
// a void racing a bump, the hub rebooting mid-service, and a tablet that walked
// out of WiFi range with actions queued.

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { openEventLog } from "../src/db.js";
import { HubEngine } from "../src/engine.js";
import { createWebServer } from "../src/web.js";
import { DonnClient, MemoryStore } from "../../shared/client.js";
import { billOrder, fireOrder, setLineStatus, voidLine } from "../../shared/decide.js";
import { getLine, orderTotalCents } from "../../shared/domain.js";

const spec = (name = "Adobo", priceCents = 25000) =>
  ({ lineId: randomUUID(), menuItemId: `m-${name}`, name, priceCents, qty: 1, station: "MAINS", modifiers: [], note: "", course: 1 });

const tempDbPath = () => path.join(mkdtempSync(path.join(tmpdir(), "hub-")), "hub.db");

/** Starts a hub on an ephemeral port (or a given one) and returns its handle. */
async function startHub({ dbPath = ":memory:", port = 0 } = {}) {
  const log = openEventLog(dbPath);
  const engine = new HubEngine(log);
  const hub = createWebServer({ engine, webDir: process.cwd(), sharedDir: process.cwd() });
  await new Promise((resolve) => hub.listen(port, "127.0.0.1", resolve));
  return {
    engine,
    port: hub.server.address().port,
    async stop() {
      await hub.close();
      log.close();
    },
  };
}

function connectClient(port, role, name = role) {
  const client = new DonnClient({
    deviceId: randomUUID(),
    deviceName: name,
    role,
    store: new MemoryStore(),
    reconnectDelayMs: 50,
  });
  client.connect(`ws://127.0.0.1:${port}/ws`);
  return client;
}

/** Polls until `predicate` holds — the sync path is async by nature. */
async function until(predicate, { timeoutMs = 4000, label = "condition" } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.fail(`timed out waiting for ${label}`);
}

const online = (client) => until(() => client.sync === "ONLINE", { label: "client online" });

test("three role clients converge on the same state", async () => {
  const hub = await startHub();
  const waiter = connectClient(hub.port, "WAITER");
  const kitchen = connectClient(hub.port, "KITCHEN");
  const cashier = connectClient(hub.port, "CASHIER");
  try {
    await Promise.all([online(waiter), online(kitchen), online(cashier)]);

    const sinigang = spec("Sinigang", 32000);
    const beer = spec("San Miguel", 9000);
    waiter.submit(fireOrder({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", tableId: "t1", covers: 2, lines: [sinigang, beer],
    }));

    // The kitchen sees the ticket without ever talking to the waiter device.
    await until(() => kitchen.state.orders.o1?.lines.length === 2, { label: "kitchen sees the ticket" });
    kitchen.submit(setLineStatus({
      commandId: randomUUID(), deviceId: kitchen.deviceId, actor: "Kusina",
      orderId: "o1", lineIds: [sinigang.lineId], status: "READY",
    }));

    // …and the status flows back to the waiter and on to the cashier.
    await until(
      () => [waiter, cashier].every((c) => getLine(c.state, "o1", sinigang.lineId)?.status === "READY"),
      { label: "READY reaches waiter and cashier" }
    );
    assert.equal(orderTotalCents(cashier.state.orders.o1), 41000);

    cashier.submit(billOrder({
      commandId: randomUUID(), deviceId: cashier.deviceId, actor: "Cashier", orderId: "o1",
    }));
    await until(() => waiter.state.orders.o1?.billed === true, { label: "bill reaches the waiter" });
  } finally {
    for (const c of [waiter, kitchen, cashier]) c.close();
    await hub.stop();
  }
});

test("void propagates to every screen and beats a concurrent bump", async () => {
  const hub = await startHub();
  const waiter = connectClient(hub.port, "WAITER");
  const kitchen = connectClient(hub.port, "KITCHEN");
  const cashier = connectClient(hub.port, "CASHIER");
  try {
    await Promise.all([online(waiter), online(kitchen), online(cashier)]);

    const line = spec("Kare-Kare", 38000);
    waiter.submit(fireOrder({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", tableId: "t1", covers: 2, lines: [line],
    }));
    await until(() => kitchen.state.orders.o1, { label: "ticket on the rail" });

    const rejections = [];
    kitchen.on("ack", (ack) => { if (!ack.accepted) rejections.push(ack); });

    // The waiter voids while the kitchen bumps the same item.
    waiter.submit(voidLine({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", lineId: line.lineId, reason: "Guest cancelled", role: "WAITER",
    }));
    await until(() => getLine(kitchen.state, "o1", line.lineId)?.status === "VOIDED", { label: "void on the rail" });

    kitchen.submit(setLineStatus({
      commandId: randomUUID(), deviceId: kitchen.deviceId, actor: "Kusina",
      orderId: "o1", lineIds: [line.lineId], status: "READY",
    }));

    // The bump is refused out loud, not dropped in silence.
    await until(() => rejections.length === 1, { label: "bump rejection" });
    assert.match(rejections[0].reason, /voided/);

    // Every screen agrees, and the voided item is off the cashier's total.
    for (const client of [waiter, kitchen, cashier]) {
      assert.equal(getLine(client.state, "o1", line.lineId).status, "VOIDED");
    }
    assert.equal(orderTotalCents(cashier.state.orders.o1), 0);
  } finally {
    for (const c of [waiter, kitchen, cashier]) c.close();
    await hub.stop();
  }
});

test("a fired ticket survives a hub reboot and clients catch up", async () => {
  const dbPath = tempDbPath();
  const first = await startHub({ dbPath });
  const port = first.port;
  const waiter = connectClient(port, "WAITER");
  try {
    await online(waiter);
    const line = spec("Crispy Pata", 49500);
    waiter.submit(fireOrder({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", tableId: "t3", covers: 4, lines: [line],
    }));
    await until(() => waiter.state.orders.o1, { label: "order acknowledged" });
    const seqAtCrash = waiter.lastSeenSeq;

    // Power cut: the hub dies with the tablet still holding the ticket.
    await first.stop();
    await until(() => waiter.sync !== "ONLINE", { label: "client notices the hub is gone" });

    // The hub comes back on the same log file and the same port.
    const reborn = await startHub({ dbPath, port });
    try {
      assert.equal(getLine(reborn.engine.state, "o1", line.lineId).status, "QUEUED");

      // A kitchen tablet booting fresh gets the whole log from seq 0.
      const kitchen = connectClient(port, "KITCHEN");
      try {
        await until(() => getLine(kitchen.state, "o1", line.lineId), { label: "cold client replays the log" });
        assert.equal(kitchen.state.orders.o1.covers, 4);

        // The waiter reconnects and resumes from its cursor, not from zero.
        await online(waiter);
        assert.equal(waiter.lastSeenSeq, seqAtCrash);
      } finally {
        kitchen.close();
      }
    } finally {
      await reborn.stop();
    }
  } finally {
    waiter.close();
  }
});

test("commands queued off-mesh drain on rejoin, exactly once", async () => {
  const dbPath = tempDbPath();
  // Claim a port, then free it: the client starts with nowhere to connect.
  const placeholder = await startHub({ dbPath });
  const port = placeholder.port;
  await placeholder.stop();

  const waiter = connectClient(port, "WAITER");
  try {
    await until(() => waiter.sync === "OFFLINE", { label: "client offline" });

    const line = spec("Sisig", 24000);
    const fireCmd = fireOrder({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", tableId: "t2", covers: 2, lines: [line],
    });
    const voidCmd = voidLine({
      commandId: randomUUID(), deviceId: waiter.deviceId, actor: "Ana",
      orderId: "o1", lineId: line.lineId, reason: "Guest left", role: "WAITER",
    });
    waiter.submit(fireCmd);
    waiter.submit(voidCmd);

    // Off the mesh, the queue is visible to the waiter — nothing is lost.
    assert.equal(waiter.sync, "QUEUING");
    assert.equal(waiter.pendingCount, 2);

    const hub = await startHub({ dbPath, port });
    try {
      await until(() => waiter.pendingCount === 0, { label: "queue drains" });
      assert.equal(getLine(hub.engine.state, "o1", line.lineId).status, "VOIDED");
      assert.equal(waiter.sync, "ONLINE");

      // Re-submitting the same commands (a duplicated replay) changes nothing.
      const seqBefore = hub.engine.currentSeq();
      waiter.submit(fireCmd);
      waiter.submit(voidCmd);
      await until(() => waiter.pendingCount === 0, { label: "replay acked" });
      assert.equal(hub.engine.currentSeq(), seqBefore);
      assert.equal(hub.engine.state.orders.o1.lines.length, 1);
    } finally {
      await hub.stop();
    }
  } finally {
    waiter.close();
  }
});

test("a rejected command clears from the queue and reports why", async () => {
  const hub = await startHub();
  const cashier = connectClient(hub.port, "CASHIER");
  try {
    await online(cashier);
    const acks = [];
    cashier.on("ack", (ack) => acks.push(ack));

    cashier.submit(billOrder({
      commandId: randomUUID(), deviceId: cashier.deviceId, actor: "Cashier", orderId: "ghost",
    }));
    await until(() => acks.length === 1, { label: "ack for an unknown order" });

    assert.equal(acks[0].accepted, false);
    assert.match(acks[0].reason, /not found/);
    // A rejected command must not sit in the queue retrying forever.
    assert.equal(cashier.pendingCount, 0);
  } finally {
    cashier.close();
    await hub.stop();
  }
});
