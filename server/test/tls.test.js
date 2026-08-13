// Certificate lifecycle and the enrollment listener.
//
// The rule these protect: staff install the CA once, and nothing the hub does
// afterwards — reboots, yearly cert expiry, a new LAN address — may force them
// to install anything again.

import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import https from "node:https";
import { ensureCertificates, opensslAvailable } from "../src/certs.js";
import { createEnrollServer } from "../src/enroll.js";
import { openEventLog } from "../src/db.js";
import { HubEngine } from "../src/engine.js";
import { createWebServer } from "../src/web.js";

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");
const tempDir = () => mkdtempSync(path.join(tmpdir(), "chefdonn-tls-"));
const skip = opensslAvailable() ? false : "openssl is not installed";

test("issues a CA and a server certificate that chains to it", { skip }, () => {
  const dir = tempDir();
  const result = ensureCertificates({ dataDir: dir, hosts: ["192.168.1.50"] });

  assert.match(result.caCertPem.toString(), /BEGIN CERTIFICATE/);
  const verify = execFileSync(
    "openssl",
    ["verify", "-CAfile", path.join(dir, "tls/ca.crt"), path.join(dir, "tls/server.crt")],
    { encoding: "utf8" }
  );
  assert.match(verify, /OK/);
});

test("the server certificate stays under Safari's 398-day ceiling", { skip }, () => {
  const result = ensureCertificates({ dataDir: tempDir(), hosts: ["192.168.1.50"] });
  assert.ok(result.expiresInDays > 300, `expected a usefully long cert, got ${result.expiresInDays}`);
  assert.ok(result.expiresInDays < 398, `iOS rejects server certs over 398 days, got ${result.expiresInDays}`);
});

test("names every LAN address, so any route to the hub validates", { skip }, () => {
  const dir = tempDir();
  ensureCertificates({ dataDir: dir, hosts: ["192.168.1.50", "10.0.0.9"] });
  const text = execFileSync("openssl", ["x509", "-in", path.join(dir, "tls/server.crt"), "-noout", "-text"], {
    encoding: "utf8",
  });
  for (const expected of ["192.168.1.50", "10.0.0.9", "127.0.0.1", "localhost"]) {
    assert.ok(text.includes(expected), `SAN missing ${expected}`);
  }
});

test("does not reissue on an unchanged reboot", { skip }, () => {
  const dir = tempDir();
  const hosts = ["192.168.1.50"];
  assert.equal(ensureCertificates({ dataDir: dir, hosts }).rotated, true);
  assert.equal(ensureCertificates({ dataDir: dir, hosts }).rotated, false);
  // Address order must not matter either.
  assert.equal(ensureCertificates({ dataDir: dir, hosts: [...hosts] }).rotated, false);
});

test("reissues for a changed LAN address but keeps the same CA", { skip }, () => {
  const dir = tempDir();
  const first = ensureCertificates({ dataDir: dir, hosts: ["192.168.1.50"] });
  const moved = ensureCertificates({ dataDir: dir, hosts: ["192.168.1.77"] });

  assert.equal(moved.rotated, true);
  // The CA is what tablets trust: it must survive, or every device re-enrolls.
  assert.equal(moved.caCertPem.toString(), first.caCertPem.toString());
});

// ── enrollment listener ────────────────────────────────────────────────

async function withEnrollServer(run) {
  const enroll = createEnrollServer({
    caCertPem: Buffer.from("-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----\n"),
    httpsPort: 8443,
    hosts: ["192.168.1.50"],
  });
  await new Promise((resolve) => enroll.listen(0, "127.0.0.1", resolve));
  try {
    await run(`http://127.0.0.1:${enroll.server.address().port}`);
  } finally {
    await enroll.close();
  }
}

test("serves the CA with the MIME type phones recognise", async () => {
  await withEnrollServer(async (base) => {
    const res = await fetch(`${base}/chefdonn-ca.crt`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/x-x509-ca-cert");
    assert.match(res.headers.get("content-disposition"), /chefdonn-ca\.crt/);
    assert.match(await res.text(), /BEGIN CERTIFICATE/);
  });
});

test("the setup page is self-contained, for a device that trusts nothing yet", async () => {
  await withEnrollServer(async (base) => {
    const res = await fetch(base);
    assert.equal(res.status, 200);
    const html = await res.text();
    // No subresource may come from the HTTPS origin the device cannot verify.
    assert.doesNotMatch(html, /<link[^>]+href="https:/, "setup page pulls CSS from the untrusted origin");
    assert.doesNotMatch(html, /<script[^>]+src="https:/, "setup page pulls JS from the untrusted origin");
    // Both platforms' instructions, including the step iOS users miss.
    assert.match(html, /Android/);
    assert.match(html, /Certificate Trust Settings/);
  });
});

test("redirects app paths to HTTPS so old bookmarks still work", async () => {
  await withEnrollServer(async (base) => {
    const res = await fetch(`${base}/waiter/`, { redirect: "manual" });
    assert.equal(res.status, 302);
    assert.match(res.headers.get("location"), /^https:\/\/127\.0\.0\.1:8443\/waiter\/$/);
  });
});

// ── the apps over TLS ──────────────────────────────────────────────────

test("serves the apps over HTTPS with the issued certificate", { skip }, async () => {
  const dir = tempDir();
  const tls = ensureCertificates({ dataDir: dir, hosts: ["127.0.0.1"] });
  const log = openEventLog(":memory:");
  const engine = new HubEngine(log);
  const hub = createWebServer({
    engine,
    webDir: path.join(repoRoot, "web"),
    sharedDir: path.join(repoRoot, "shared"),
    tls,
  });
  await new Promise((resolve) => hub.listen(0, "127.0.0.1", resolve));
  const port = hub.server.address().port;

  try {
    const body = await new Promise((resolve, reject) => {
      https
        .get(
          {
            host: "127.0.0.1",
            port,
            path: "/healthz",
            // Verified against the hub's own CA — no rejectUnauthorized escape.
            ca: readFileSync(path.join(dir, "tls/ca.crt")),
          },
          (res) => {
            let data = "";
            res.on("data", (chunk) => { data += chunk; });
            res.on("end", () => resolve(data));
          }
        )
        .on("error", reject);
    });
    assert.deepEqual(JSON.parse(body), { ok: true, seq: 0 });
  } finally {
    await hub.close();
    log.close();
  }
});
