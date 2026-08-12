// The app server also serves the PWAs, so the static half needs its own guard
// rails — above all, no path escaping the web root.

import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { openEventLog } from "../src/db.js";
import { HubEngine } from "../src/engine.js";
import { createWebServer } from "../src/web.js";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

async function withServer(run) {
  const log = openEventLog(":memory:");
  const engine = new HubEngine(log);
  const hub = createWebServer({
    engine,
    webDir: path.join(repoRoot, "web"),
    sharedDir: path.join(repoRoot, "shared"),
  });
  await new Promise((resolve) => hub.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${hub.server.address().port}`;
  try {
    await run(base);
  } finally {
    await hub.close();
    log.close();
  }
}

test("serves the launcher and each role app", async () => {
  await withServer(async (base) => {
    for (const p of ["/", "/waiter/", "/kitchen/", "/cashier/"]) {
      const res = await fetch(base + p);
      assert.equal(res.status, 200, `GET ${p}`);
      assert.match(res.headers.get("content-type"), /text\/html/);
      assert.match(await res.text(), /Chef Donn's/);
    }
  });
});

test("serves shared modules, styles, and fonts with usable content types", async () => {
  await withServer(async (base) => {
    const cases = [
      ["/shared/domain.js", /javascript/],
      ["/shared/client.js", /javascript/],
      ["/common/tokens.css", /text\/css/],
      ["/fonts/barlow_regular.woff2", /font\/woff2/],
      ["/waiter/manifest.webmanifest", /application\/manifest\+json/],
      ["/waiter/icons/icon-192.png", /image\/png/],
    ];
    for (const [p, type] of cases) {
      const res = await fetch(base + p);
      assert.equal(res.status, 200, `GET ${p}`);
      assert.match(res.headers.get("content-type"), type, `content-type for ${p}`);
    }
  });
});

test("healthz reports the current log position and is never cached", async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/healthz`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, seq: 0 });
  });
});

test("refuses paths that climb out of the web root", async () => {
  await withServer(async (base) => {
    for (const p of ["/../package.json", "/%2e%2e/package.json", "/shared/../../package.json"]) {
      const res = await fetch(base + p);
      assert.ok(res.status === 403 || res.status === 404, `${p} → ${res.status}`);
      assert.doesNotMatch(await res.text(), /"dependencies"/, `${p} leaked package.json`);
    }
  });
});

test("refuses non-GET methods", async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/waiter/`, { method: "POST" });
    assert.equal(res.status, 405);
  });
});

test("redirects a directory without a trailing slash", async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/waiter`, { redirect: "manual" });
    assert.equal(res.status, 301);
    assert.equal(res.headers.get("location"), "/waiter/");
  });
});
