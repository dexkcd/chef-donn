// TLS material for the hub.
//
// Service workers only exist in a secure context. A tablet reaching the hub at
// http://192.168.x.x has no `navigator.serviceWorker` at all — no offline
// shell, no home-screen install. So the hub runs HTTPS, and since the
// restaurant has no internet during service, it is its own certificate
// authority.
//
// Two certs, deliberately:
//   • the CA is long-lived and is the only thing staff install on a tablet;
//   • the server cert is short-lived (iOS rejects server certs valid for more
//     than 398 days, even from a privately installed root) and is reissued
//     automatically from that CA.
// Because reissuing the leaf does not touch the CA, the yearly rotation costs
// nobody a re-enrollment — the tablets keep trusting the hub across it.
//
// The leaf is also reissued when the PC's LAN address changes, since a cert
// that doesn't name the address staff typed is a cert the browser rejects.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import path from "node:path";
import { networkInterfaces } from "node:os";

const CA_DAYS = 3650; // 10 years — the thing staff install, so make it stick
const LEAF_DAYS = 397; // under Safari's 398-day ceiling for server certs
const RENEW_WITHIN_DAYS = 30;

/** Every IPv4 address a tablet could plausibly use to reach this hub. */
export function lanAddresses() {
  const found = Object.values(networkInterfaces())
    .flat()
    .filter((ni) => ni && ni.family === "IPv4" && !ni.internal)
    .map((ni) => ni.address);
  return [...new Set(found)];
}

function openssl(args, opts = {}) {
  return execFileSync("openssl", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...opts });
}

export function opensslAvailable() {
  try {
    openssl(["version"]);
    return true;
  } catch {
    return false;
  }
}

/** SAN list: localhost plus every LAN address, so one cert covers all routes in. */
function sanEntries(hosts) {
  const dns = ["localhost", ...hosts.filter((h) => !isIp(h))];
  const ips = ["127.0.0.1", ...hosts.filter(isIp)];
  return [
    ...[...new Set(dns)].map((d) => `DNS:${d}`),
    ...[...new Set(ips)].map((ip) => `IP:${ip}`),
  ].join(",");
}

const isIp = (host) => /^\d{1,3}(\.\d{1,3}){3}$/.test(host);

function daysUntilExpiry(certPath) {
  try {
    const out = openssl(["x509", "-in", certPath, "-noout", "-enddate"]);
    const notAfter = new Date(out.replace("notAfter=", "").trim());
    return (notAfter.getTime() - Date.now()) / 86_400_000;
  } catch {
    return -1;
  }
}

/**
 * Canonical, comparable SAN set. `openssl x509 -text` prints "IP Address:1.2.3.4"
 * while the extension we write says "IP:1.2.3.4"; normalising both through here
 * is what stops the hub reissuing its certificate on every single boot.
 */
function canonicalSans(sans) {
  return sans
    .split(",")
    .map((entry) => entry.trim().replace(/^IP Address:/i, "IP:"))
    .filter(Boolean)
    .sort()
    .join(",");
}

/** The SANs actually baked into an existing leaf, for change detection. */
function certSans(certPath) {
  try {
    const text = openssl(["x509", "-in", certPath, "-noout", "-text"]);
    const match = text.match(/X509v3 Subject Alternative Name:[^\n]*\n\s*([^\n]+)/);
    return match ? canonicalSans(match[1]) : "";
  } catch {
    return "";
  }
}

function makeCa(dir, caKey, caCert) {
  mkdirSync(dir, { recursive: true });
  openssl(["genrsa", "-out", caKey, "2048"]);
  openssl([
    "req", "-x509", "-new", "-nodes", "-key", caKey, "-sha256",
    "-days", String(CA_DAYS),
    "-subj", "/O=Chef Donn's/CN=Chef Donn's Hub CA",
    "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
    "-addext", "keyUsage=critical,keyCertSign,cRLSign",
    "-out", caCert,
  ]);
}

function makeLeaf(dir, { caKey, caCert, key, cert, sans }) {
  const csr = path.join(dir, "server.csr");
  const extFile = path.join(dir, "server.ext");
  writeFileSync(
    extFile,
    [
      "basicConstraints=CA:FALSE",
      "keyUsage=critical,digitalSignature,keyEncipherment",
      "extendedKeyUsage=serverAuth",
      `subjectAltName=${sans}`,
      "",
    ].join("\n")
  );
  try {
    openssl(["genrsa", "-out", key, "2048"]);
    openssl(["req", "-new", "-key", key, "-subj", "/O=Chef Donn's/CN=Chef Donn's Hub", "-out", csr]);
    openssl([
      "x509", "-req", "-in", csr,
      "-CA", caCert, "-CAkey", caKey, "-CAcreateserial",
      "-days", String(LEAF_DAYS), "-sha256",
      "-extfile", extFile,
      "-out", cert,
    ]);
  } finally {
    rmSync(csr, { force: true });
    rmSync(extFile, { force: true });
  }
}

/**
 * Returns { key, cert, caCertPem, caCertPath, hosts, rotated } — generating or
 * reissuing whatever is missing or stale. Safe to call on every boot.
 */
export function ensureCertificates({ dataDir, hosts = lanAddresses() }) {
  const dir = path.join(dataDir, "tls");
  const caKey = path.join(dir, "ca.key");
  const caCert = path.join(dir, "ca.crt");
  const key = path.join(dir, "server.key");
  const cert = path.join(dir, "server.crt");

  mkdirSync(dir, { recursive: true });

  const notes = [];
  if (!existsSync(caKey) || !existsSync(caCert)) {
    makeCa(dir, caKey, caCert);
    // A new CA invalidates any leaf signed by the old one.
    rmSync(cert, { force: true });
    notes.push("created a new hub certificate authority");
  }

  const sans = sanEntries(hosts);
  const expiringSoon = daysUntilExpiry(cert) < RENEW_WITHIN_DAYS;
  const addressesChanged = existsSync(cert) && certSans(cert) !== canonicalSans(sans);

  let rotated = false;
  if (!existsSync(key) || !existsSync(cert) || expiringSoon || addressesChanged) {
    makeLeaf(dir, { caKey, caCert, key, cert, sans });
    rotated = true;
    notes.push(
      addressesChanged && !expiringSoon
        ? "reissued the server certificate for a changed LAN address"
        : "issued a server certificate"
    );
  }

  return {
    key: readFileSync(key),
    cert: readFileSync(cert),
    caCertPem: readFileSync(caCert),
    caCertPath: caCert,
    hosts,
    rotated,
    notes,
    expiresInDays: Math.floor(daysUntilExpiry(cert)),
  };
}
