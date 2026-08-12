# Chef Donn's — agent notes

Three-app restaurant ticketing (waiter / kitchen KDS / cashier) as progressive
web apps, served by one small Node app server running in Docker on a PC in the
restaurant's network.

Product spec v2 rules: cashier is read-only + Mark Billed only; NO payments,
discounts, split bills, receipts, BIR features, or cloud-during-service. Do not
add POS features — they are explicitly out of scope until Module 3.

## Toolchain

Node ≥22.5 (the event log uses built-in `node:sqlite`) and Docker. One runtime
dependency, `ws`. No build step, no bundler, no transpiler — do not add one.

- `npm start` — serve on :8577
- `npm test` — domain, engine, web, and real-socket integration tests
- `docker compose up -d` — how it actually runs in the restaurant

## Architecture invariants

- The app server is the single authority. All state = fold of `reduce(state, event)`
  over the SQLite event log (`server/src`). Clients run the *same* `reduce`.
- `shared/` is loaded verbatim by both server and browser (served at `/shared/`).
  Keep it dependency-free and framework-free or it stops being shareable.
- Command validation lives only in `decide()` (`shared/decide.js`). Deterministic;
  void beats bump; rejections are surfaced (Ack.reason), never silent.
- Commands/orders/lines carry client-generated UUIDs; the server dedupes by
  commandId (idempotent replay after offline queueing). Never reuse or regenerate
  ids on retry.
- Money is `priceCents` (centavos, integer). Names/prices are snapshotted into the
  line spec at order time — menu edits must not change fired tickets.
- Line status: QUEUED → COOKING → READY → SERVED forward-only (jumps ok);
  VOIDED terminal, only pre-SERVED.
- The wire format matches the original Kotlin apps (discriminator `type`), so an
  Android client could still speak to this hub. Don't casually rename event fields.

## Design system

Source of truth: Claude Design project `4f624b38-8da4-471f-b3d2-387da1d795bf`
("Chef Donn's Design System") — fetch via DesignSync. CSS translation lives in
`web/common/tokens.css` (tokens, day/night schemes) and `web/common/base.css`
(components). Rules: Day scheme for waiter/cashier, Night for kitchen (set by
`data-scheme` per app, by role — never by system theme); status = glyph + color +
label, never color alone; tap targets ≥56px (default 72px); thick borders over
shadows; no emoji in staff apps; Archivo for display/buttons, Barlow body, Barlow
Condensed labels, IBM Plex Mono for all numerics.

Fonts are self-hosted woff2 in `web/fonts/` — never load a CDN font, the
restaurant has no internet during service. Icons regenerate with
`python3 tools/make-icons.py`.

## PWA specifics

- Each app owns `manifest.webmanifest` + `sw.js`; shared SW logic is in
  `web/common/sw-core.js` (stale-while-revalidate app shell).
- Bump the `cacheName` in an app's `sw.js` when its shell assets change, or
  tablets keep serving the old cache.
- Offline queue persists in `localStorage` (`shared/client.js`), so a queued void
  survives an app kill.

## Testing

- `npm test` — all of it; `node --test server/test/integration.test.js` for the
  socket-level cases (3 role clients, void-beats-bump, hub restart replay,
  offline queue drain).
- Integration tests use the real `DonnClient` over real sockets, not a stub —
  Node ≥22 has a global `WebSocket`, which is why that works.
