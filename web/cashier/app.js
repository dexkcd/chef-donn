// Cashier app — notification-only by design. A live read-only running tab per
// table, reflecting voids the instant they happen, with exactly one action:
// Mark Billed. No payments, no discounts, no split bills, no receipts — those
// are POS territory and BIR CAS accreditation, deliberately out of scope.
//
// The cashier keys the total into their existing accredited POS, same as they
// do today with paper tickets, but from a screen that cannot go stale.

import { DonnClient, LocalStorageStore } from "../../shared/client.js";
import { billOrder } from "../../shared/decide.js";
import { lineAmountCents, orderTotalCents } from "../../shared/domain.js";
import {
  clear, deviceId, h, hubUrl, modal, newId, peso, queueBanner,
  registerServiceWorker, staffName, statusBadge, syncPill, toast,
} from "../common/ui.js";

const ROLE = "CASHIER";

const screen = document.getElementById("screen");

const client = new DonnClient({
  deviceId: deviceId(),
  deviceName: "Cashier station",
  role: ROLE,
  store: new LocalStorageStore("cashier"),
});

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

function refreshChrome() {
  pill.update(client.sync, client.pendingCount);
  banner.update(client.sync, client.pendingCount);
}

function render() {
  const state = client.state;
  clear(screen);

  const open = Object.values(state.orders)
    .filter((order) => !order.billed)
    .sort((a, b) => a.openedAt - b.openedAt);

  if (!open.length) {
    screen.append(h("div.empty.display", { text: "No open tables" }));
    return;
  }

  const grid = h("div.tabs-grid");
  for (const order of open) grid.append(tabCard(state, order));
  screen.append(grid);
}

function tabCard(state, order) {
  const table = state.tables.find((t) => t.id === order.tableId);
  const live = order.lines.filter((l) => l.status !== "VOIDED");
  // Ready to settle only when every live item has actually reached the guest.
  const settleable = live.length > 0 && live.every((l) => l.status === "SERVED");
  const outstanding = live.filter((l) => l.status !== "SERVED").length;

  const card = h(
    `div.tab-card${settleable ? ".tab-card--settleable" : ""}`,
    {},
    h(
      "div.tab-card__head",
      {},
      h("span.tab-card__table", { text: table?.label ?? order.tableId }),
      h("span.tab-card__meta", { text: `${order.covers} covers` }),
      h("span.grow"),
      h("span.tab-card__meta", { text: `${live.length} item${live.length === 1 ? "" : "s"}` })
    )
  );

  const lines = h("div.tab-card__lines");
  for (const line of order.lines) {
    lines.append(
      h(
        `div.cline${line.status === "VOIDED" ? ".cline--voided" : ""}`,
        {},
        h("span.cline__qty", { text: `${line.spec.qty}×` }),
        h(
          "div.grow",
          {},
          h("div.cline__name", { text: line.spec.name }),
          line.status === "VOIDED" && line.voidReason
            ? h("div.caption", { text: `Voided: ${line.voidReason} (${line.voidedBy})` })
            : null
        ),
        statusBadge(line.status),
        h("span.cline__amount", { text: peso(lineAmountCents(line)) })
      )
    );
  }
  card.append(lines);

  card.append(
    h(
      "div.tab-card__total",
      {},
      h("span.tab-card__total-label", { text: "Total" }),
      h("span.tab-card__total-value", { text: peso(orderTotalCents(order)) })
    )
  );

  if (outstanding) {
    card.append(h("div.pending-note", {
      text: `${outstanding} item${outstanding === 1 ? "" : "s"} still with the kitchen`,
    }));
  }

  card.append(
    h(
      "div.tab-card__foot",
      {},
      h("button.btn.btn--block", {
        text: "Mark Billed",
        onclick: () => confirmBill(state, order),
      })
    )
  );
  return card;
}

function confirmBill(state, order) {
  const table = state.tables.find((t) => t.id === order.tableId);
  const outstanding = order.lines.filter((l) => l.status !== "VOIDED" && l.status !== "SERVED").length;

  modal((close) => [
    h("h2.display", { text: `Settle ${table?.label ?? order.tableId}?` }),
    h(
      "div.tab-card__total",
      {},
      h("span.tab-card__total-label", { text: "Total to key in" }),
      h("span.tab-card__total-value", { text: peso(orderTotalCents(order)) })
    ),
    h("p.caption", { text: "Key this total into your POS, then mark the table billed here. The table closes and cannot take more items." }),
    outstanding
      ? h("p.pending-note", { text: `Warning: ${outstanding} item${outstanding === 1 ? " is" : "s are"} not served yet` })
      : null,
    h("button.btn.btn--block", {
      text: "Mark Billed",
      onclick: () => {
        client.submit(billOrder({
          commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE), orderId: order.orderId,
        }));
        refreshChrome();
        toast(`${table?.label ?? "Table"} marked billed`, "ok");
        close();
      },
    }),
    h("button.btn.btn--secondary.btn--block", { text: "Cancel", onclick: close }),
  ]);
}
