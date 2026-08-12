# Chef Donn's

Order ticketing for Philippine restaurants that already have a POS but run
everything before it on paper. Three apps, one local app server, zero changes to
the existing POS: **add tablets, remove paper.**

- **Waiter** (phone) — take orders table-side, fire to kitchen, live per-item status
- **Kitchen / KDS** (tablet, dark) — station rail, start, bump, 86, timers
- **Cashier** (tablet, read-only) — live running tab per table, one action: Mark Billed

All three are progressive web apps served off one small Node server that runs in
Docker on any PC in the restaurant. Staff open the server's address once, install
the app for their role to the home screen, and it behaves like a native app —
full screen, no browser chrome, working when the WiFi drops.

No payments, no receipts, no discounts, no split bills, no BIR CAS, no cloud
during service.

## Architecture

Event-sourced. Clients send commands; the server validates them (`decide`),
appends events to a SQLite log (durable before it acknowledges), and broadcasts
over WebSockets. Every app folds the same `reduce` function over the same event
stream, so no two screens can disagree. Offline devices queue commands
(idempotent by client-generated UUID, persisted in `localStorage`) and drain them
on rejoin; the server replays its log on reboot. Void-vs-bump conflicts resolve
deterministically: void wins, the bump is rejected loudly.

`shared/` is genuinely shared — the browser loads the exact same `domain.js`,
`decide.js`, and `client.js` files the server imports. There is no build step,
no bundler, and no transpiler anywhere in this repo.

| Path | What it is |
| --- | --- |
| `shared/domain.js` | Entities, events, `reduce` — the pure fold |
| `shared/decide.js` | Commands and `decide` — the only place a write is validated |
| `shared/protocol.js` | WebSocket messages (Hello / Submit / Welcome / Events / Ack) |
| `shared/client.js` | Sync engine: pending queue, catch-up, reconnect |
| `server/src/` | Event log (`node:sqlite`), engine, HTTP + WebSocket server |
| `web/waiter`, `web/kitchen`, `web/cashier` | The three PWAs |
| `web/common`, `web/fonts` | Design-system CSS, shared UI helpers, service worker, fonts |

## Running it

Requires Docker. On the restaurant's PC:

```bash
docker compose up -d
```

Then find that PC's LAN address (`ipconfig` on Windows) and open
`http://<that-address>:8577/` on each tablet. Pick the role, then use the
browser's **Install app** / **Add to Home Screen**.

The server prints the URLs staff should use when it starts:

```
docker compose logs hub
```

The order log lives in the `hub-data` Docker volume. It is the restaurant's
service history — back it up, don't delete it.

### Windows notes

Docker Desktop with the WSL2 backend is the tested setup. Two things matter:

- **Let port 8577 through Windows Firewall**, or tablets on the same WiFi can't
  reach the hub. Docker Desktop usually prompts on first run; if it didn't,
  add an inbound rule for TCP 8577 on the Private profile.
- **The PC needs a stable LAN address.** A DHCP reservation on the router is the
  low-effort option; otherwise every reboot can move the hub and the staff's
  home-screen shortcuts break.

The compose file uses a named volume rather than a bind mount on purpose:
SQLite's write-ahead log needs real file locking, which host-mounted Windows
directories do not reliably provide.

## Development

Requires Node 22.5 or newer (the event log uses the built-in `node:sqlite`).

```bash
npm install
npm start        # http://localhost:8577
npm test         # 40 tests: domain rules, engine, real-socket integration
```

`npm start` seeds a sample Filipino menu and tables T1–T10 the first time it runs
against an empty log.

The tests cover what actually breaks during service: three role clients staying
in sync, a void racing a bump, the hub rebooting mid-service with a ticket
already fired, and a tablet queueing commands off-mesh and draining them on
rejoin — exactly once.

## Design system

Source of truth is the Claude Design project
`4f624b38-8da4-471f-b3d2-387da1d795bf` ("Chef Donn's Design System"). The CSS
translation lives in `web/common/tokens.css` and `web/common/base.css`; the
fonts in `web/fonts/` are self-hosted so nothing is fetched from a CDN during
service.

Rules: Day scheme for waiter and cashier, Night for kitchen (chosen by role, not
by the device theme); status is always glyph + color + label, never color alone;
tap targets ≥56px, 72px by default; thick borders instead of shadows; no emoji in
staff apps; Archivo for display and buttons, Barlow for body, Barlow Condensed
for labels, IBM Plex Mono for every number.

Icons are generated from those tokens by `tools/make-icons.py`.
