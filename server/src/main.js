// Entrypoint: open the log, replay it, seed on first boot, serve.
// Runs in Docker on the restaurant's Windows PC; the tablets reach it over the
// local WiFi at http://<pc-ip>:8577/.

import path from "node:path";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { networkInterfaces } from "node:os";
import { openEventLog } from "./db.js";
import { HubEngine } from "./engine.js";
import { createWebServer } from "./web.js";
import { seedIfEmpty } from "./seed.js";
import { DEFAULT_HUB_PORT } from "../../shared/protocol.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");

const port = Number(process.env.CHEFDONN_PORT ?? DEFAULT_HUB_PORT);
const dataDir = process.env.CHEFDONN_DATA ?? path.join(repoRoot, "data");
const hubName = process.env.CHEFDONN_HUB_NAME ?? "Chef Donn's Hub";

mkdirSync(dataDir, { recursive: true });
const dbPath = path.join(dataDir, "hub.db");

const log = openEventLog(dbPath);
const engine = new HubEngine(log);
const seeded = seedIfEmpty(engine);

const hub = createWebServer({
  engine,
  webDir: path.join(repoRoot, "web"),
  sharedDir: path.join(repoRoot, "shared"),
  hubName,
});

hub.listen(port, "0.0.0.0", () => {
  const orders = Object.keys(engine.state.orders).length;
  console.log(`Chef Donn's hub — ${hubName}`);
  console.log(`  log      ${dbPath} (seq ${engine.currentSeq()}, ${orders} orders replayed)`);
  if (seeded) console.log("  seeded   sample menu + tables T1–T10");
  for (const addr of lanAddresses()) console.log(`  serving  http://${addr}:${port}/`);
});

/** Best-effort LAN addresses, so staff know what to type into the tablets. */
function lanAddresses() {
  const found = Object.values(networkInterfaces())
    .flat()
    .filter((ni) => ni && ni.family === "IPv4" && !ni.internal)
    .map((ni) => ni.address);
  return found.length ? found : ["localhost"];
}

// A fired ticket must survive shutdown: close the log so WAL is checkpointed.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, async () => {
    await hub.close();
    log.close();
    process.exit(0);
  });
}
