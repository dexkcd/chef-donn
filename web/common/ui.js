// Small DOM + formatting helpers shared by the three apps. No framework: the
// apps re-render their list on every state change, which at one restaurant's
// order volume is far cheaper than a diffing library and never goes stale.

/** h("div.card", { onclick }, ...children) */
export function h(spec, props = {}, ...children) {
  const [tag, ...classes] = spec.split(".");
  const el = document.createElement(tag || "div");
  if (classes.length) el.className = classes.join(" ");
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value == null || value === false) continue;
    if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
    else if (key === "class") el.className = [el.className, value].filter(Boolean).join(" ");
    else if (key === "text") el.textContent = value;
    else if (key === "html") el.innerHTML = value;
    else el.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function clear(el) {
  el.replaceChildren();
  return el;
}

/** Centavos → ₱ string. Money is integer centavos everywhere; this is the only
 *  place it becomes a decimal, and only for display. */
export function peso(cents) {
  return `₱${(cents / 100).toLocaleString("en-PH", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** Status is never color-alone: fixed glyph + color + label. */
export const STATUS_GLYPH = {
  QUEUED: "●",
  COOKING: "▲",
  READY: "✓",
  SERVED: "■",
  VOIDED: "✕",
};

export const STATUS_LABEL = {
  QUEUED: "Queued",
  COOKING: "Cooking",
  READY: "Ready",
  SERVED: "Served",
  VOIDED: "Void",
};

export function statusBadge(status, { large = false } = {}) {
  return h(
    `span.badge.badge--${status}${large ? ".badge--lg" : ""}`,
    {},
    h("span", { text: STATUS_GLYPH[status] }),
    h("span", { text: STATUS_LABEL[status].toUpperCase() })
  );
}

/** mm:ss since a timestamp — the KDS age clock. */
export function elapsed(sinceMs, now = Date.now()) {
  const total = Math.max(0, Math.floor((now - sinceMs) / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

// ── Device identity ───────────────────────────────────────────────────
// Ids are client-generated and stable per device, so a replayed command is
// recognised by the hub as the same command, never a duplicate order.

export function deviceId() {
  let id = localStorage.getItem("chefdonn:deviceId");
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem("chefdonn:deviceId", id);
  }
  return id;
}

export function staffName(role) {
  return localStorage.getItem("chefdonn:staffName") || defaultName(role);
}

export function setStaffName(name) {
  localStorage.setItem("chefdonn:staffName", name);
}

function defaultName(role) {
  return { WAITER: "Waiter", KITCHEN: "Kitchen", CASHIER: "Cashier" }[role] ?? "Staff";
}

/** The hub is whatever served this page — no IP typing, no mDNS discovery. */
export function hubUrl() {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${location.host}/ws`;
}

export const newId = () => crypto.randomUUID();

// ── Toasts — every rejection surfaces, none are silently dropped ──────

let toastHost = null;

export function toast(message, kind = "") {
  toastHost ??= document.body.appendChild(h("div.toasts"));
  const el = h(`div.toast${kind ? `.toast--${kind}` : ""}`, { text: message });
  toastHost.append(el);
  setTimeout(() => el.remove(), kind === "error" ? 6000 : 3000);
}

// ── Modal ─────────────────────────────────────────────────────────────

/** Opens a modal; `render(close)` builds its contents. Returns close(). */
export function modal(render) {
  const backdrop = h("div.modal-backdrop");
  const close = () => backdrop.remove();
  backdrop.addEventListener("click", (evt) => {
    if (evt.target === backdrop) close();
  });
  backdrop.append(h("div.modal", {}, ...[render(close)].flat()));
  document.body.append(backdrop);
  return close;
}

// ── Sync pill + queued-writes banner ──────────────────────────────────

export function syncPill() {
  const el = h("span.sync.sync--OFFLINE", {}, h("span.sync__dot"), h("span", { text: "OFFLINE" }));
  return {
    el,
    update(sync, pendingCount) {
      el.className = `sync sync--${sync}`;
      const label = sync === "QUEUING" ? `QUEUING ${pendingCount}` : sync;
      el.lastChild.textContent = label;
    },
  };
}

export function queueBanner() {
  const el = h("div.queue-banner");
  return {
    el,
    update(sync, pendingCount) {
      const show = sync !== "ONLINE" && pendingCount > 0;
      el.classList.toggle("queue-banner--visible", show);
      el.textContent = show
        ? `Off the network — ${pendingCount} action${pendingCount === 1 ? "" : "s"} waiting to sync`
        : "";
    },
  };
}

/** Registers the app's service worker; harmless if unsupported. */
export function registerServiceWorker(path = "sw.js") {
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register(path).catch(() => {});
  }
}
