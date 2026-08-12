// Kitchen display — station rail, tap to advance, bump the ticket, kitchen
// voids. Always the night scheme: a kitchen tablet is dark regardless of what
// the device theme says.

import { DonnClient, LocalStorageStore } from "../../shared/client.js";
import { setLineStatus, voidLine } from "../../shared/decide.js";
import { canAdvanceTo, canBeVoided } from "../../shared/domain.js";
import {
  clear, deviceId, elapsed, h, hubUrl, modal, newId, queueBanner,
  registerServiceWorker, staffName, statusBadge, syncPill, toast,
} from "../common/ui.js";

const ROLE = "KITCHEN";
const ALL = "ALL";
const VOID_REASONS = ["Out of stock", "Remake", "Spoiled", "Wrong fire"];
/** Ticket age thresholds, in minutes, for the border/timer escalation. */
const WARN_MIN = 10;
const LATE_MIN = 20;

const screen = document.getElementById("screen");
const stationsNav = document.getElementById("stations");

const client = new DonnClient({
  deviceId: deviceId(),
  deviceName: "Kitchen display",
  role: ROLE,
  store: new LocalStorageStore("kitchen"),
});

let station = localStorage.getItem("chefdonn:station") ?? ALL;
let showRecall = false;

const pill = syncPill();
const banner = queueBanner();
document.getElementById("sync-slot").append(pill.el);
document.getElementById("banner-slot").append(banner.el);

client.on("state", render);
client.on("sync", refreshChrome);
client.on("ack", (ack) => {
  refreshChrome();
  // A bump that lost to a void lands here — loudly, never silently dropped.
  if (!ack.accepted) toast(ack.reason, "error");
});

client.connect(hubUrl());
registerServiceWorker();
render();
refreshChrome();
setInterval(render, 1000); // ticket age clocks

function refreshChrome() {
  pill.update(client.sync, client.pendingCount);
  banner.update(client.sync, client.pendingCount);
}

function send(command) {
  client.submit(command);
  refreshChrome();
}

const isOpen = (line) => line.status === "QUEUED" || line.status === "COOKING";

/** Lines this station is responsible for. */
function stationLines(order) {
  return order.lines.filter((l) => station === ALL || l.spec.station === station);
}

function render() {
  const state = client.state;
  renderStations(state);
  clear(screen);

  const tickets = Object.values(state.orders)
    .filter((order) => !order.billed)
    .map((order) => ({ order, lines: stationLines(order) }))
    .filter(({ lines }) => lines.length && (showRecall || lines.some(isOpen)))
    .sort((a, b) => a.order.openedAt - b.order.openedAt);

  if (!tickets.length) {
    screen.append(h("div.empty.display", { text: showRecall ? "Nothing to recall" : "No open tickets" }));
    return;
  }

  const rail = h("div.rail");
  for (const { order, lines } of tickets) rail.append(ticketCard(state, order, lines));
  screen.append(rail);
}

function renderStations(state) {
  const stations = [ALL, ...new Set(state.menu.map((m) => m.station))];
  clear(stationsNav);
  for (const name of stations) {
    const open = Object.values(state.orders)
      .filter((o) => !o.billed)
      .flatMap((o) => o.lines)
      .filter((l) => isOpen(l) && (name === ALL || l.spec.station === name)).length;
    stationsNav.append(
      h(
        "button.tab",
        {
          "aria-selected": name === station,
          onclick: () => {
            station = name;
            localStorage.setItem("chefdonn:station", name);
            render();
          },
        },
        h("span", {}, name, open ? h("span.tab__badge", { text: String(open) }) : null)
      )
    );
  }
  stationsNav.append(
    h("button.tab", {
      "aria-selected": showRecall,
      text: "Recall",
      onclick: () => { showRecall = !showRecall; render(); },
    })
  );
}

function ticketCard(state, order, lines) {
  const table = state.tables.find((t) => t.id === order.tableId);
  const oldest = Math.min(...lines.filter(isOpen).map((l) => l.statusAt), order.openedAt);
  const ageMin = (Date.now() - oldest) / 60000;
  const heat = ageMin >= LATE_MIN ? "late" : ageMin >= WARN_MIN ? "warn" : "";
  const openLines = lines.filter(isOpen);

  const card = h(
    `div.ticket${heat ? `.ticket--${heat}` : ""}`,
    {},
    h(
      "div.ticket__head",
      {},
      h("span.ticket__table", { text: table?.label ?? order.tableId }),
      h("span.ticket__covers", { text: `${order.covers} cov` }),
      h("span.ticket__timer", { text: elapsed(oldest) })
    )
  );

  const list = h("div.ticket__lines");
  for (const line of lines) list.append(kitchenLine(order, line));
  card.append(list);

  if (openLines.length) {
    const startable = openLines.filter((l) => l.status === "QUEUED");
    card.append(
      h(
        "div.ticket__foot",
        {},
        startable.length
          ? h("button.btn.btn--secondary.btn--md", {
              text: "Start All",
              onclick: () => bumpMany(order, startable, "COOKING"),
            })
          : null,
        h("button.btn.btn--md", {
          text: "Bump All",
          onclick: () => bumpMany(order, openLines, "READY"),
        })
      )
    );
  }
  return card;
}

function kitchenLine(order, line) {
  const { spec } = line;
  const done = !isOpen(line);
  const next = line.status === "QUEUED" ? "COOKING" : "READY";
  const sub = [...spec.modifiers, spec.note].filter(Boolean).join(" · ");

  return h(
    `div.kline${done ? ".kline--done" : ""}${line.status === "VOIDED" ? ".kline--voided" : ""}`,
    {
      onclick: () => {
        if (!isOpen(line)) return;
        send(setLineStatus({
          commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE),
          orderId: order.orderId, lineIds: [spec.lineId], status: next,
        }));
      },
    },
    h("span.kline__qty", { text: `${spec.qty}×` }),
    h(
      "div.grow",
      {},
      h("div.kline__name", { text: spec.name }),
      sub ? h("div.kline__note", { text: sub }) : null,
      station === ALL ? h("div.station-hint", { text: spec.station }) : null
    ),
    statusBadge(line.status),
    // Voidable right up until the plate reaches the guest — a dish dropped
    // after it hits the pass still has to come off the cashier's tab.
    canBeVoided(line.status)
      ? h("button.btn.btn--secondary.btn--md", {
          text: "86",
          onclick: (evt) => { evt.stopPropagation(); askVoid(order, line); },
        })
      : null
  );
}

function bumpMany(order, lines, status) {
  const lineIds = lines.filter((l) => canAdvanceTo(l.status, status)).map((l) => l.spec.lineId);
  if (!lineIds.length) return;
  send(setLineStatus({
    commandId: newId(), deviceId: client.deviceId, actor: staffName(ROLE),
    orderId: order.orderId, lineIds, status,
  }));
}

function askVoid(order, line) {
  modal((close) => [
    h("h2.display", { text: `86 ${line.spec.name}?` }),
    h("p.caption", { text: "The waiter and the cashier see this immediately." }),
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
