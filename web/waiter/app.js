// Waiter app — take orders table-side, fire to kitchen, watch per-item status,
// add items, void items. Three views: floor → table → menu picker.

import { DonnClient, LocalStorageStore } from "../../shared/client.js";
import { addLines, fireOrder, setLineStatus, voidLine } from "../../shared/decide.js";
import { openOrderForTable, orderTotalCents } from "../../shared/domain.js";
import {
  clear, deviceId, elapsed, h, hubUrl, modal, newId, peso, queueBanner,
  registerServiceWorker, setStaffName, staffName, statusBadge, syncPill, toast,
} from "../common/ui.js";

const ROLE = "WAITER";
const VOID_REASONS = ["Guest cancelled", "Wrong item", "Out of stock", "Remake"];
const COVER_OPTIONS = [1, 2, 3, 4, 5, 6, 8, 10];

const screen = document.getElementById("screen");
const actionbar = document.getElementById("actionbar");
const title = document.querySelector(".topbar__title");

const client = new DonnClient({
  deviceId: deviceId(),
  deviceName: "Waiter device",
  role: ROLE,
  store: new LocalStorageStore("waiter"),
});

// view: { name: "floor" } | { name: "table", tableId } | { name: "menu", tableId }
let view = { name: "floor" };
/** Draft lines not yet fired, keyed by menuItemId. Never sent until FIRE. */
let cart = new Map();
let covers = 2;

const pill = syncPill();
const banner = queueBanner();
document.getElementById("sync-slot").append(pill.el);
document.getElementById("banner-slot").append(banner.el);

client.on("state", render);
client.on("sync", refreshChrome);
client.on("ack", (ack) => {
  refreshChrome();
  if (!ack.accepted) toast(ack.reason, "error");
});

client.connect(hubUrl());
registerServiceWorker();
render();
refreshChrome();
// Ticket ages tick even when no event arrives.
setInterval(() => { if (view.name === "table") render(); }, 1000);

function refreshChrome() {
  pill.update(client.sync, client.pendingCount);
  banner.update(client.sync, client.pendingCount);
}

function send(command) {
  client.submit(command);
  refreshChrome();
}

function go(next) {
  view = next;
  render();
}

// ── Render ────────────────────────────────────────────────────────────

function render() {
  const state = client.state;
  clear(screen);
  clear(actionbar).classList.add("hidden");
  if (view.name === "floor") renderFloor(state);
  else if (view.name === "table") renderTable(state);
  else renderMenu(state);
}

function renderFloor(state) {
  title.textContent = "Tables";
  if (!state.tables.length) {
    screen.append(h("div.empty", { text: "Waiting for the hub to send the floor plan…" }));
    return;
  }
  const grid = h("div.floor");
  for (const table of state.tables) {
    const order = openOrderForTable(state, table.id);
    const live = order ? order.lines.filter((l) => l.status !== "VOIDED") : [];
    const ready = live.filter((l) => l.status === "READY").length;
    grid.append(
      h(
        `div.floor__cell${order ? ".floor__cell--occupied" : ""}`,
        { onclick: () => go({ name: "table", tableId: table.id }) },
        h("span.floor__label", { text: table.label }),
        order
          ? h("span.floor__meta", { text: `${live.length} item${live.length === 1 ? "" : "s"}` })
          : h("span.floor__meta.label", { text: "Open" }),
        order ? h("span.floor__meta", { text: peso(orderTotalCents(order)) }) : null,
        ready ? h("span.tag", { text: `${ready} ready` }) : null
      )
    );
  }
  screen.append(grid);
}

function renderTable(state) {
  const table = state.tables.find((t) => t.id === view.tableId);
  const order = openOrderForTable(state, view.tableId);
  title.textContent = table?.label ?? "Table";

  screen.append(
    h(
      "div.row",
      {},
      h("button.btn.btn--ghost.btn--md", { text: "‹ Tables", onclick: () => go({ name: "floor" }) }),
      h("span.grow"),
      order ? h("span.caption", { text: `${order.covers} covers · opened ${elapsed(order.openedAt)}` }) : null
    )
  );

  if (!order) {
    screen.append(
      h("p.section-title", { text: "Covers" }),
      h("div.covers", {}, ...COVER_OPTIONS.map((n) =>
        h("button.covers__opt", {
          text: String(n),
          "aria-pressed": n === covers,
          onclick: () => { covers = n; render(); },
        })
      )),
      h("div.empty", { text: "No open order for this table yet." })
    );
  } else {
    const card = h("div.card");
    for (const line of order.lines) card.append(lineRow(order, line));
    card.append(
      h(
        "div.total-row",
        {},
        h("span.label-lg", { text: "Total" }),
        h("span.mono-lg", { text: peso(orderTotalCents(order)) })
      )
    );
    screen.append(card);
  }

  actionbar.classList.remove("hidden");
  actionbar.append(
    h("button.btn.btn--block", {
      text: order ? "Add Items" : "Start Order",
      onclick: () => { cart = new Map(); go({ name: "menu", tableId: view.tableId }); },
    })
  );
}

function lineRow(order, line) {
  const { spec } = line;
  const sub = [spec.station, ...spec.modifiers, spec.note].filter(Boolean).join(" · ");
  const canVoid = line.status !== "SERVED" && line.status !== "VOIDED";
  // Two rows, not one: on a phone, item + price + status + two buttons on a
  // single line pushes the amount off the edge of the card.
  return h(
    `div.line${line.status === "VOIDED" ? ".line--voided" : ""}`,
    {},
    h(
      "div.line__main",
      {},
      h("span.line__qty", { text: `${spec.qty}×` }),
      h(
        "div.grow",
        {},
        h("div.line__name", { text: spec.name }),
        sub ? h("div.line__sub", { text: sub }) : null,
        line.status === "VOIDED" && line.voidReason
          ? h("div.line__sub", { text: `Voided: ${line.voidReason} (${line.voidedBy})` })
          : null
      ),
      h("span.line__price", { text: peso(line.status === "VOIDED" ? 0 : spec.priceCents * spec.qty) })
    ),
    h(
      "div.line__actions",
      {},
      statusBadge(line.status),
      h("span.grow"),
      // Running the plate to the guest is the waiter's bump.
      line.status === "READY"
        ? h("button.btn.btn--md", {
            text: "Served",
            onclick: () => send(setLineStatus({
              commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE),
              orderId: order.orderId, lineIds: [spec.lineId], status: "SERVED",
            })),
          })
        : null,
      canVoid
        ? h("button.btn.btn--secondary.btn--md", { text: "Void", onclick: () => askVoid(order, line) })
        : null
    )
  );
}

function askVoid(order, line) {
  modal((close) => [
    h("h2.display", { text: `Void ${line.spec.name}?` }),
    h("p.caption", { text: "The kitchen and the cashier see this immediately." }),
    ...VOID_REASONS.map((reason) =>
      h("button.btn.btn--danger.btn--block", {
        text: reason,
        onclick: () => {
          send(voidLine({
            commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE),
            orderId: order.orderId, lineId: line.spec.lineId, reason, role: ROLE,
          }));
          close();
        },
      })
    ),
    h("button.btn.btn--secondary.btn--block", { text: "Cancel", onclick: close }),
  ]);
}

function renderMenu(state) {
  const table = state.tables.find((t) => t.id === view.tableId);
  title.textContent = `${table?.label ?? "Table"} — Add`;
  const categories = [...new Set(state.menu.filter((m) => m.active).map((m) => m.category))];

  screen.append(
    h(
      "div.row",
      {},
      h("button.btn.btn--ghost.btn--md", {
        text: "‹ Back",
        onclick: () => go({ name: "table", tableId: view.tableId }),
      })
    )
  );

  for (const category of categories) {
    screen.append(h("p.section-title", { text: category }));
    const list = h("div.menu-list");
    for (const item of state.menu.filter((m) => m.active && m.category === category)) {
      const qty = cart.get(item.id)?.qty ?? 0;
      list.append(
        h(
          `button.menu-item${qty ? ".menu-item--in-cart" : ""}`,
          { onclick: () => bump(item, +1) },
          h(
            "div.grow",
            {},
            h("div.menu-item__name", { text: item.name }),
            h("div.menu-item__station", { text: item.station })
          ),
          h("span.menu-item__price", { text: peso(item.priceCents) }),
          h("span.menu-item__count", { text: qty ? `${qty}×` : "" })
        )
      );
    }
    screen.append(list);
  }

  if (cart.size) {
    screen.append(h("p.section-title", { text: "To fire" }));
    const card = h("div.card");
    for (const entry of cart.values()) {
      card.append(
        h(
          "div.cart-line",
          {},
          h("span.cart-line__name", { text: entry.item.name }),
          h(
            "div.stepper",
            {},
            h("button.stepper__key", { text: "−", onclick: () => bump(entry.item, -1) }),
            h("span.stepper__value.mono-lg", { text: String(entry.qty).padStart(2, "0") }),
            h("button.stepper__key", { text: "+", onclick: () => bump(entry.item, +1) })
          ),
          h("input.input", {
            placeholder: "Note (e.g. no onions)",
            value: entry.note,
            oninput: (evt) => { entry.note = evt.target.value; },
          })
        )
      );
    }
    screen.append(card);
  }

  actionbar.classList.remove("hidden");
  actionbar.append(
    h("button.btn.btn--block", {
      text: cart.size ? `Fire ${totalCartQty()} Item${totalCartQty() === 1 ? "" : "s"}` : "Fire",
      disabled: !cart.size,
      onclick: () => fire(state),
    })
  );
}

function bump(item, delta) {
  const entry = cart.get(item.id) ?? { item, qty: 0, note: "" };
  entry.qty += delta;
  if (entry.qty <= 0) cart.delete(item.id);
  else cart.set(item.id, entry);
  render();
}

const totalCartQty = () => [...cart.values()].reduce((sum, e) => sum + e.qty, 0);

function fire(state) {
  // Name and price are snapshotted here: a later menu edit must never change a
  // ticket that is already on the kitchen rail.
  const lines = [...cart.values()].map((entry) => ({
    lineId: newId(),
    menuItemId: entry.item.id,
    name: entry.item.name,
    priceCents: entry.item.priceCents,
    qty: entry.qty,
    station: entry.item.station,
    modifiers: [],
    note: entry.note.trim(),
    course: 1,
  }));

  const open = openOrderForTable(state, view.tableId);
  const common = { commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE) };
  send(
    open
      ? addLines({ ...common, orderId: open.orderId, lines })
      : fireOrder({ ...common, orderId: newId(), tableId: view.tableId, covers, lines })
  );

  cart = new Map();
  toast(`Fired ${lines.length} item${lines.length === 1 ? "" : "s"} to the kitchen`, "ok");
  go({ name: "table", tableId: view.tableId });
}

// Long-press the title to set who is on shift — it stamps every command.
title.addEventListener("contextmenu", (evt) => {
  evt.preventDefault();
  modal((close) => {
    const input = h("input.input", { value: staffName(ROLE), placeholder: "Your name" });
    return [
      h("h2.display", { text: "Who is on shift?" }),
      input,
      h("button.btn.btn--block", {
        text: "Save",
        onclick: () => {
          if (input.value.trim()) setStaffName(input.value.trim());
          close();
        },
      }),
    ];
  });
});
