// Entrypoint: open the log, replay it, seed on first boot, serve.
// Runs in Docker on the restaurant's Windows PC; the tablets reach it over the
// local WiFi.
//
// Two listeners, on purpose:
//   • HTTPS (8443) — the apps. Secure context, so service workers exist, so the
//     apps install to the home screen and open when the WiFi is down.
//   • HTTP  (8577) — setup only. Hands out the hub's CA certificate and then
//     redirects to HTTPS.

import path from "node:path";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { openEventLog } from "./db.js";
import { HubEngine } from "./engine.js";
import { createWebServer } from "./web.js";
import { createEnrollServer } from "./enroll.js";
import { ensureCertificates, lanAddresses, opensslAvailable } from "./certs.js";
import { seedIfEmpty } from "./seed.js";
import { DEFAULT_HUB_PORT } from "../../shared/protocol.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");

const setupPort = Number(process.env.CHEFDONN_PORT ?? DEFAULT_HUB_PORT);
const httpsPort = Number(process.env.CHEFDONN_HTTPS_PORT ?? 8443);
const dataDir = process.env.CHEFDONN_DATA ?? path.join(repoRoot, "data");
const hubName = process.env.CHEFDONN_HUB_NAME ?? "Chef Donn's Hub";
// Escape hatch for a plain-HTTP hub (development, or a site fronting the hub
// with its own TLS). Off by default: it silently disables offline and install.
const insecure = process.env.CHEFDONN_INSECURE === "1";

mkdirSync(dataDir, { recursive: true });
const dbPath = path.join(dataDir, "hub.db");

const log = openEventLog(dbPath);
const engine = new HubEngine(log);
const seeded = seedIfEmpty(engine);

const hosts = lanAddresses();
let tls = null;
if (!insecure) {
  if (!opensslAvailable()) {
    console.error("openssl not found — cannot issue the hub certificate.");
    console.error("Install openssl, or set CHEFDONN_INSECURE=1 to run without HTTPS");
    console.error("(no home-screen install and no offline start for the tablets).");
    process.exit(1);
  }
  tls = ensureCertificates({ dataDir, hosts });
}

const hub = createWebServer({
  engine,
  webDir: path.join(repoRoot, "web"),
  sharedDir: path.join(repoRoot, "shared"),
  hubName,
  tls,
});

const enroll = tls ? createEnrollServer({ caCertPem: tls.caCertPem, httpsPort, hosts }) : null;

hub.listen(insecure ? setupPort : httpsPort, "0.0.0.0", () => {
  const orders = Object.keys(engine.state.orders).length;
  console.log(`Chef Donn's hub — ${hubName}`);
  console.log(`  log      ${dbPath} (seq ${engine.currentSeq()}, ${orders} orders replayed)`);
  if (seeded) console.log("  seeded   sample menu + tables T1–T10");

  if (!tls) {
    console.log("  WARNING  running without HTTPS (CHEFDONN_INSECURE=1)");
    console.log("           tablets cannot install these apps or open them offline");
    for (const addr of displayHosts()) console.log(`  apps     http://${addr}:${setupPort}/`);
    return;
  }

  for (const note of tls.notes) console.log(`  tls      ${note}`);
  console.log(`  tls      server certificate valid for ${tls.expiresInDays} more days`);
  for (const addr of displayHosts()) console.log(`  apps     https://${addr}:${httpsPort}/`);
  console.log("");
  console.log("  New tablet? Open the setup page on it and install the certificate:");
  for (const addr of displayHosts()) console.log(`           http://${addr}:${setupPort}/`);
});

enroll?.listen(setupPort, "0.0.0.0");

function displayHosts() {
  return hosts.length ? hosts : ["localhost"];
}

// A fired ticket must survive shutdown: close the log so WAL is checkpointed.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, async () => {
    await Promise.all([hub.close(), enroll?.close()]);
    log.close();
    process.exit(0);
  });
}
