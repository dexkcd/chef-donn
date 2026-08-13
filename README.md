# Chef Donn's

Order ticketing for Philippine restaurants that already have a POS but run
everything before it on paper. Three apps, one local app server, zero changes to
the existing POS: **add tablets, remove paper.**

- **Waiter** (phone) — take orders table-side, fire to kitchen, live per-item status
- **Kitchen / KDS** (tablet, dark) — station rail, start, bump, 86, timers
- **Cashier** (tablet, read-only) — live running tab per table, one action: Mark Billed

All three are progressive web apps served off one small Node server that runs in
Docker on any PC in the restaurant. Staff install the app for their role to the
home screen once, and it behaves like a native app — full screen, no browser
chrome, working when the WiFi drops.

That last part is why the hub serves HTTPS. Browsers only expose service
workers on a secure origin, and a plain `http://192.168.x.x` address is not
one — no service worker means no home-screen install and a blank screen the
moment a tablet reloads off-mesh. Since the restaurant has no internet during
service, the hub is its own certificate authority: each device installs its
certificate once during setup, and never again.

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
| `server/src/` | Event log (`node:sqlite`), engine, HTTPS + WebSocket server |
| `server/src/certs.js` | The hub's own CA and its auto-rotating server certificate |
| `server/src/enroll.js` | HTTP setup page that gets the CA onto a tablet |
| `web/waiter`, `web/kitchen`, `web/cashier` | The three PWAs |
| `web/common`, `web/fonts` | Design-system CSS, shared UI helpers, service worker, fonts |

## Running it

Requires Docker. On the restaurant's PC:

```bash
docker compose up -d
```

Find that PC's LAN address (`ipconfig` on Windows) — or just read it off the
startup log, which prints every address staff can use:

```
docker compose logs hub
```

### Setting up a tablet (once per device)

1. Open `http://<that-address>:8577/` — the setup page.
2. Tap **Download the certificate**, then install it. The page has the exact
   taps for Android, iPad, and Windows.
   - **iPad/iPhone:** after installing, you must also switch on full trust in
     Settings → General → About → Certificate Trust Settings. Safari refuses
     the hub until you do.
3. Open `https://<that-address>:8443/`, pick the role, and use the browser's
   **Install app** / **Add to Home Screen**.

From then on staff just tap the home-screen icon. The old HTTP address keeps
working as a redirect, so a stale bookmark still lands in the right place.

The `hub-data` Docker volume holds both the order log and the hub's certificate
authority. It is the restaurant's service history — back it up, don't delete it.
Deleting it also means every tablet has to install a new certificate.

### Certificate renewal

Nothing to do. The certificate authority lasts ten years; the server
certificate it signs lasts 397 days (Safari rejects anything longer) and the
hub reissues it automatically within 30 days of expiry, and whenever the PC's
LAN address changes. Because reissuing never touches the CA, tablets keep
working across it without re-enrolling.

### Windows notes

Docker Desktop with the WSL2 backend is the tested setup. Two things matter:

- **Let ports 8443 and 8577 through Windows Firewall**, or tablets on the same
  WiFi can't reach the hub. Docker Desktop usually prompts on first run; if it
  didn't, add inbound rules for TCP 8443 and 8577 on the Private profile.
- **The PC needs a stable LAN address.** A DHCP reservation on the router is the
  low-effort option; otherwise every reboot can move the hub and the staff's
  home-screen shortcuts break. (The hub reissues its certificate for a new
  address automatically, so this costs a re-bookmark, not a re-enrollment.)

The compose file uses a named volume rather than a bind mount on purpose:
SQLite's write-ahead log needs real file locking, which host-mounted Windows
directories do not reliably provide.

## Development

Requires Node 22.5 or newer (the event log uses the built-in `node:sqlite`) and
`openssl` on PATH for certificate issuance.

```bash
npm install
npm start        # apps on https://localhost:8443, setup on http://localhost:8577
npm test         # 49 tests: domain rules, engine, TLS, real-socket integration

CHEFDONN_INSECURE=1 npm start   # plain HTTP on 8577, no TLS
```

`CHEFDONN_INSECURE=1` is for development only. `http://localhost` happens to be
a secure context, so service workers still work there — but the same build on a
LAN address silently loses installability and offline start, which is the whole
reason the certificate machinery exists.

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
