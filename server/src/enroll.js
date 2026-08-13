// The plain-HTTP side of the hub: a setup page whose only job is getting the
// hub's CA certificate onto a tablet, after which everything moves to HTTPS.
//
// This has to be HTTP, because a tablet that does not yet trust the hub cannot
// safely be sent to the HTTPS address first — it would meet a full-page
// security warning, which is exactly the screen we don't want staff learning to
// click through. So: type the plain address once, install the certificate, then
// never use this page again.
//
// It serves nothing but the certificate and instructions. All order data lives
// on the HTTPS side.

import http from "node:http";

const CA_PATH = "/chefdonn-ca.crt";

const escape = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function setupPage({ host, httpsPort, hosts }) {
  const appOrigin = `https://${host}:${httpsPort}`;
  // Every style here is inline and every font is a system font, deliberately.
  // This is the one page a tablet loads *before* it trusts the hub, so pulling
  // CSS or woff2 from the HTTPS origin would leave staff staring at an unstyled
  // page — on the exact screen that has to inspire enough confidence to install
  // a certificate.
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Chef Donn's — Set up this tablet</title>
<style>
  *, *::before, *::after { box-sizing: border-box; }
  body {
    margin: 0; background: #f4f1ea; color: #1a1a1a;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    font-size: 16px; line-height: 1.45; -webkit-text-size-adjust: 100%;
  }
  .setup { max-width: 640px; margin: 0 auto; padding: 32px 16px; display: flex; flex-direction: column; gap: 24px; }
  .wordmark { font-size: 34px; font-weight: 800; line-height: 1.1; letter-spacing: -0.01em; }
  .muted { color: #6b6963; }
  .btn {
    display: flex; align-items: center; justify-content: center;
    min-height: 72px; padding: 0 24px; width: 100%;
    background: #0f4d3a; border: 3px solid #0f4d3a; border-radius: 8px;
    color: #f4f1ea; font-size: 18px; font-weight: 700; text-decoration: none;
  }
  .btn--secondary { background: transparent; border-color: #1a1a1a; color: #1a1a1a; }
  ol { padding-left: 24px; display: flex; flex-direction: column; gap: 12px; margin: 0; }
  li { font-size: 17px; }
  .step-title { font-weight: 700; }
  details { border: 3px solid #1a1a1a; border-radius: 8px; padding: 16px; background: #fff; }
  summary { font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; cursor: pointer; min-height: 56px; display: flex; align-items: center; }
  code, .addr { font-family: ui-monospace, "Courier New", monospace; }
  code { font-size: 15px; background: #e8e2d3; padding: 2px 6px; border-radius: 4px; }
  .addr { font-size: 16px; }
  .caption { font-size: 14px; color: #6b6963; }
</style>
</head>
<body>
<main class="setup">
  <div>
    <div class="wordmark">Set up this tablet</div>
    <p class="muted">One time per device, about a minute. After this the apps install to the home screen and keep working when the WiFi drops.</p>
  </div>

  <a class="btn btn--block" href="${CA_PATH}" download="chefdonn-ca.crt">Download the certificate</a>

  <ol>
    <li><span class="step-title">Download</span> the certificate using the button above.</li>
    <li><span class="step-title">Install it</span> — see the instructions for this device below.</li>
    <li><span class="step-title">Open the apps</span> at <span class="addr">${escape(appOrigin)}</span> and add the one for this device's role to the home screen.</li>
  </ol>

  <details>
    <summary>Android</summary>
    <p>Settings → Security → Encryption &amp; credentials → <strong>Install a certificate</strong> → <strong>CA certificate</strong> → Install anyway → pick <code>chefdonn-ca.crt</code> from Downloads.</p>
    <p class="caption">Some devices put this under Settings → Security → More security settings. Android warns that a third party may monitor traffic — that "third party" is this restaurant's own hub.</p>
  </details>

  <details>
    <summary>iPad / iPhone</summary>
    <p>Settings → <strong>Profile Downloaded</strong> → Install (enter passcode) → Install.</p>
    <p><strong>Then the step everyone misses:</strong> Settings → General → About → <strong>Certificate Trust Settings</strong> → switch on full trust for "Chef Donn's Hub CA". Without it Safari still refuses the hub.</p>
  </details>

  <details>
    <summary>Windows PC (for the cashier screen)</summary>
    <p>Double-click <code>chefdonn-ca.crt</code> → Install Certificate → <strong>Local Machine</strong> → Place all certificates in the following store → Browse → <strong>Trusted Root Certification Authorities</strong>.</p>
  </details>

  <a class="btn btn--secondary btn--block" href="${appOrigin}/">Open the apps</a>

  <p class="caption">This hub is reachable at: ${hosts.map((h) => `<span class="addr">${escape(h)}</span>`).join(", ")}</p>
</main>
</body>
</html>`;
}

/**
 * The HTTP listener. Serves the CA and the setup page; sends anything that
 * looks like an app path over to HTTPS so old bookmarks still land correctly.
 */
export function createEnrollServer({ caCertPem, httpsPort, hosts }) {
  const server = http.createServer((req, res) => {
    const host = (req.headers.host ?? "").split(":")[0] || hosts[0] || "localhost";

    if (req.url === CA_PATH) {
      res.writeHead(200, {
        // The MIME type Android and iOS both recognise as an installable CA.
        "content-type": "application/x-x509-ca-cert",
        "content-disposition": 'attachment; filename="chefdonn-ca.crt"',
        "cache-control": "no-store",
      });
      res.end(caCertPem);
      return;
    }

    const urlPath = (req.url ?? "/").split("?")[0];
    if (urlPath === "/" || urlPath === "/setup" || urlPath === "/index.html") {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
      res.end(setupPage({ host, httpsPort, hosts }));
      return;
    }

    // Anything else is an app path typed or bookmarked against the old HTTP
    // address — send it to the secure origin rather than 404.
    res.writeHead(302, { location: `https://${host}:${httpsPort}${req.url}` }).end();
  });

  return {
    server,
    listen: (...args) => server.listen(...args),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

export const CA_DOWNLOAD_PATH = CA_PATH;
